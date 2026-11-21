// SPDX-License-Identifier: Apache-2.0
package org.atp.adapter;

import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.json.JsonCodec;
import org.atp.model.Types;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.TableDiff;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/** Schema-per-transaction SQL branch. The same statements run on H2 and PostgreSQL. */
public final class JdbcOltp implements PhasePort {
    private final String url;
    private final String user;
    private final String password;
    Connection injected;

    public JdbcOltp(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        exec("""
                CREATE TABLE IF NOT EXISTS customer_ledger (
                  account VARCHAR(64) PRIMARY KEY,
                  balance DECIMAL(18, 2) NOT NULL
                )""");
        exec("""
                CREATE TABLE IF NOT EXISTS atp_effect (
                  effect_key VARCHAR(400) PRIMARY KEY,
                  body VARCHAR(8000) NOT NULL
                )""");
    }

    public void seed(Map<String, BigDecimal> balances) {
        work(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("DELETE FROM customer_ledger");
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO customer_ledger (account, balance) VALUES (?, ?)")) {
                for (Map.Entry<String, BigDecimal> entry : balances.entrySet()) {
                    ps.setString(1, entry.getKey());
                    ps.setBigDecimal(2, entry.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
            return null;
        });
    }

    public Map<String, BigDecimal> balances() {
        return read("customer_ledger");
    }

    @Override
    public EffectResult shadow(Dispatch dispatch) {
        EffectResult prior = stored("SHADOW:" + dispatch.idempotencyKey());
        if (prior != null) {
            return prior;
        }
        Transfer transfer = transfer(dispatch);
        if (transfer.error != null) {
            return EffectResult.fail(transfer.error, false);
        }
        String schema = schema(dispatch.txId());
        return work(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS %s.customer_ledger (
                          account VARCHAR(64) PRIMARY KEY,
                          balance DECIMAL(18, 2) NOT NULL
                        )""".formatted(schema));
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + schema + ".customer_ledger")) {
                    rs.next();
                    if (rs.getInt(1) == 0) {
                        st.execute("INSERT INTO " + schema + ".customer_ledger SELECT account, balance FROM customer_ledger");
                    }
                }
            }
            String error = applyTransfer(connection, schema, transfer);
            if (error != null) {
                connection.rollback();
                return EffectResult.fail(error, false);
            }
            EffectResult result = EffectResult.ok(Map.of("applied", true, "found", true),
                    "jdbc:" + schema + ":open", "jdbc:" + schema + ":branch");
            store(connection, "SHADOW:" + dispatch.idempotencyKey(), result);
            connection.commit();
            return result;
        });
    }

    @Override
    public EffectResult live(Dispatch dispatch) {
        EffectResult prior = stored("LIVE:" + dispatch.idempotencyKey());
        if (prior != null) {
            return prior;
        }
        String schema = schema(dispatch.txId());
        String pre = schema(dispatch.txId()) + "_pre";
        return work(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE SCHEMA IF NOT EXISTS " + pre);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS %s.customer_ledger (
                          account VARCHAR(64) PRIMARY KEY,
                          balance DECIMAL(18, 2) NOT NULL
                        )""".formatted(pre));
                st.execute("DELETE FROM " + pre + ".customer_ledger");
                st.execute("INSERT INTO " + pre + ".customer_ledger SELECT account, balance FROM customer_ledger");
                st.execute("DELETE FROM customer_ledger");
                st.execute("INSERT INTO customer_ledger SELECT account, balance FROM " + schema + ".customer_ledger");
            }
            EffectResult result = EffectResult.ok(Map.of("published", true, "found", true),
                    "jdbc:" + pre, "jdbc:" + schema + ":main");
            store(connection, "LIVE:" + dispatch.idempotencyKey(), result);
            connection.commit();
            return result;
        });
    }

    @Override
    public EffectResult compensate(Dispatch dispatch) {
        String schema = schema(dispatch.txId());
        String pre = schema + "_pre";
        return work(connection -> {
            try (Statement st = connection.createStatement()) {
                if ("INVERSE".equals(dispatch.action()) && schemaExists(connection, pre)) {
                    st.execute("DELETE FROM customer_ledger");
                    st.execute("INSERT INTO customer_ledger SELECT account, balance FROM " + pre + ".customer_ledger");
                }
                if (schemaExists(connection, schema)) {
                    st.execute("DROP SCHEMA " + schema + " CASCADE");
                }
                if (schemaExists(connection, pre)) {
                    st.execute("DROP SCHEMA " + pre + " CASCADE");
                }
            }
            connection.commit();
            return EffectResult.ok(Map.of("restored", true), "jdbc:" + schema, "jdbc:main");
        });
    }

    @Override
    public EffectResult reconcile(Dispatch dispatch) {
        EffectResult prior = stored(dispatch.phase() + ":" + dispatch.idempotencyKey());
        if (prior == null) {
            return EffectResult.ok(Map.of("found", false), null, null);
        }
        Map<String, Object> output = new LinkedHashMap<>(prior.output());
        output.put("found", true);
        return EffectResult.ok(output, prior.preState(), prior.postState());
    }

    @Override
    public DiffReport diff(String txId) {
        String schema = schema(txId);
        if (!schemaExists(schema)) {
            return DiffReport.empty();
        }
        Map<String, BigDecimal> before = read("customer_ledger");
        Map<String, BigDecimal> after = read(schema + ".customer_ledger");
        long changed = 0;
        BigDecimal money = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : after.entrySet()) {
            BigDecimal was = before.get(entry.getKey());
            if (was == null || was.compareTo(entry.getValue()) != 0) {
                changed++;
                if (was != null && entry.getValue().compareTo(was) < 0) {
                    money = money.add(was.subtract(entry.getValue()));
                }
            }
        }
        return new DiffReport(java.util.List.of(new TableDiff(
                "customer_ledger", before.size(), after.size(), changed, false, money)));
    }

    private static String applyTransfer(Connection connection, String schema, Transfer transfer) throws SQLException {
        BigDecimal source = balance(connection, schema, transfer.source);
        BigDecimal target = balance(connection, schema, transfer.target);
        if (source == null || target == null) {
            return "unknown account";
        }
        if (source.compareTo(transfer.amount) < 0) {
            return "insufficient funds";
        }
        update(connection, schema, transfer.source, source.subtract(transfer.amount));
        update(connection, schema, transfer.target, target.add(transfer.amount));
        return null;
    }

    private static BigDecimal balance(Connection connection, String schema, String account) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT balance FROM " + schema + ".customer_ledger WHERE account = ?")) {
            ps.setString(1, account);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    private static void update(Connection connection, String schema, String account, BigDecimal balance) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "UPDATE " + schema + ".customer_ledger SET balance = ? WHERE account = ?")) {
            ps.setBigDecimal(1, balance);
            ps.setString(2, account);
            ps.executeUpdate();
        }
    }

    private Map<String, BigDecimal> read(String table) {
        return work(connection -> {
            Map<String, BigDecimal> rows = new LinkedHashMap<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT account, balance FROM " + table + " ORDER BY account")) {
                while (rs.next()) {
                    rows.put(rs.getString(1), rs.getBigDecimal(2));
                }
            }
            connection.commit();
            return rows;
        });
    }

    private boolean schemaExists(String schema) {
        try {
            return work(connection -> {
                boolean exists = schemaExists(connection, schema);
                connection.commit();
                return exists;
            });
        } catch (AtpException ex) {
            return false;
        }
    }

    private static boolean schemaExists(Connection connection, String schema) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?")) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    private EffectResult stored(String key) {
        return work(connection -> {
            EffectResult result = null;
            try (PreparedStatement ps = connection.prepareStatement("SELECT body FROM atp_effect WHERE effect_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        result = decode(rs.getString(1));
                    }
                }
            }
            connection.commit();
            return result;
        });
    }

    private static void store(Connection connection, String key, EffectResult result) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO atp_effect (effect_key, body) VALUES (?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, encode(result));
            ps.executeUpdate();
        }
    }

    private static String encode(EffectResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("output", result.output());
        body.put("pre", result.preState());
        body.put("post", result.postState());
        return JsonCodec.canonical(body);
    }

    @SuppressWarnings("unchecked")
    private static EffectResult decode(String json) {
        Map<String, Object> body = JsonCodec.asMap(JsonCodec.parse(json));
        Map<String, Object> output = body.get("output") instanceof Map<?, ?> map
                ? new LinkedHashMap<>((Map<String, Object>) map) : Map.of();
        return EffectResult.ok(output, body.get("pre") == null ? null : String.valueOf(body.get("pre")),
                body.get("post") == null ? null : String.valueOf(body.get("post")));
    }

    public static String schema(String txId) {
        String cleaned = txId.replaceAll("[^A-Za-z0-9]", "_");
        if (!cleaned.matches("[A-Za-z][A-Za-z0-9_]{0,40}")) {
            throw new AtpException(AtpErrorCode.INVALID, "txId cannot be used as a SQL schema");
        }
        return "b_" + cleaned;
    }

    private static Transfer transfer(Dispatch dispatch) {
        Object source = dispatch.arguments().get("source");
        Object target = dispatch.arguments().get("target");
        Object amount = dispatch.arguments().get("amount");
        if (!(source instanceof String) || !(target instanceof String) || !(amount instanceof Number)) {
            return new Transfer(null, null, null, "ledger arguments must include source, target, and amount");
        }
        return new Transfer((String) source, (String) target, Types.asDecimal(amount), null);
    }

    private void exec(String sql) {
        work(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute(sql);
            }
            connection.commit();
            return null;
        });
    }

    private Connection connect() {
        if (injected != null) {
            Connection current = injected;
            injected = null;
            return current;
        }
        try {
            return DriverManager.getConnection(url, user, password);
        } catch (SQLException ex) {
            throw new AtpException(AtpErrorCode.INVALID, "jdbc: " + ex.getMessage());
        }
    }

    private <T> T work(SqlWork<T> work) {
        try (Connection connection = connect()) {
            connection.setAutoCommit(false);
            try {
                return work.run(connection);
            } catch (SQLException ex) {
                connection.rollback();
                throw new AtpException(AtpErrorCode.INVALID, "sql: " + ex.getMessage());
            }
        } catch (AtpException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new AtpException(AtpErrorCode.INVALID, "jdbc: " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }

    private record Transfer(String source, String target, BigDecimal amount, String error) {
    }
}
