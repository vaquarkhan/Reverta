// SPDX-License-Identifier: Apache-2.0
package org.atp.log;

import org.atp.json.JsonCodec;
import org.atp.ledger.LedgerEntry;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class JdbcDurableLog implements DurableLog {
    private final String url;
    private final String user;
    private final String password;

    public JdbcDurableLog(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
        withConnection(connection -> {
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS atp_record (
                          seq BIGINT PRIMARY KEY,
                          kind VARCHAR(1) NOT NULL,
                          body TEXT NOT NULL
                        )""");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS atp_anchor (
                          id INT PRIMARY KEY,
                          tip VARCHAR(128) NOT NULL
                        )""");
            }
            connection.commit();
            return null;
        });
    }

    @Override
    public void persist(Map<String, Object> snapshot, LedgerEntry entry) {
        withConnection(connection -> {
            long seq = nextSeq(connection);
            insert(connection, seq, "S", JsonCodec.canonical(snapshot));
            insert(connection, seq + 1, "L", JsonCodec.canonical(entry.toMap()));
            try (PreparedStatement del = connection.prepareStatement("DELETE FROM atp_anchor WHERE id = 1")) {
                del.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO atp_anchor (id, tip) VALUES (1, ?)")) {
                ps.setString(1, entry.entryHash);
                ps.executeUpdate();
            }
            connection.commit();
            return null;
        });
    }

    @Override
    public void anchor(String tip) {
        withConnection(connection -> {
            try (PreparedStatement del = connection.prepareStatement("DELETE FROM atp_anchor WHERE id = 1")) {
                del.executeUpdate();
            }
            try (PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO atp_anchor (id, tip) VALUES (1, ?)")) {
                ps.setString(1, tip);
                ps.executeUpdate();
            }
            connection.commit();
            return null;
        });
    }

    @Override
    public Loaded load() {
        return withConnection(connection -> {
            List<Map<String, Object>> snapshots = new ArrayList<>();
            List<LedgerEntry> ledger = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT kind, body FROM atp_record ORDER BY seq")) {
                while (rs.next()) {
                    String kind = rs.getString(1);
                    Object parsed = JsonCodec.parse(rs.getString(2));
                    if ("S".equals(kind)) {
                        snapshots.add(JsonCodec.asMap(parsed));
                    } else if ("L".equals(kind)) {
                        ledger.add(LedgerEntry.fromMap(JsonCodec.asMap(parsed)));
                    } else {
                        throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "unknown jdbc frame");
                    }
                }
            }
            String anchor = null;
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT tip FROM atp_anchor WHERE id = 1")) {
                if (rs.next()) {
                    anchor = rs.getString(1);
                }
            }
            return new Loaded(snapshots, ledger, anchor);
        });
    }

    private static long nextSeq(Connection connection) throws SQLException {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM atp_record")) {
            rs.next();
            return rs.getLong(1) + 1;
        }
    }

    private static void insert(Connection connection, long seq, String kind, String body) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO atp_record (seq, kind, body) VALUES (?, ?, ?)")) {
            ps.setLong(1, seq);
            ps.setString(2, kind);
            ps.setString(3, body);
            ps.executeUpdate();
        }
    }

    private <T> T withConnection(SqlWork<T> work) {
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            connection.setAutoCommit(false);
            return work.run(connection);
        } catch (AtpException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new AtpException(AtpErrorCode.INVALID, "jdbc wal: " + ex.getMessage());
        }
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
