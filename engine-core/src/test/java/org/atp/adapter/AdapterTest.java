package org.atp.adapter;

import com.sun.net.httpserver.HttpServer;
import org.atp.adapter.HttpClients.CatalogBacking;
import org.atp.adapter.HttpClients.JdkSimpleHttp;
import org.atp.adapter.HttpClients.NeonBacking;
import org.atp.adapter.HttpClients.NeonClient;
import org.atp.adapter.HttpClients.NessieClient;
import org.atp.adapter.HttpClients.SimpleHttp;
import org.atp.adapter.HttpClients.SimpleResponse;
import org.atp.demo.DemoWorld;
import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.effect.Effects.Runtime;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.CompensationSpec;
import org.atp.model.Types.EffectStatus;
import org.atp.model.Types.Reversibility;
import org.atp.model.Types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdapterTest {
    @Test
    void memoryBranchesReconcileAndApiVirtualizer() {
        DemoWorld world = DemoWorld.create();
        ToolSpec ledger = world.manifest.tool("adjust_ledger");
        MemoryOltp oltp = world.oltp;
        Dispatch shadow = new Dispatch("m1", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 10), "k", ledger, null, "SHADOW");
        assertEquals(EffectStatus.OK, oltp.shadow(shadow).status());
        assertEquals(EffectStatus.OK, oltp.shadow(shadow).status());
        assertTrue(oltp.reconcile(shadow).found());
        assertFalse(oltp.reconcile(new Dispatch("m1", 0, "adjust_ledger", Map.of(), "missing", ledger, null, "LIVE")).found());
        assertEquals(EffectStatus.OK, oltp.live(new Dispatch("m1", 0, "adjust_ledger", Map.of(), "live", ledger, null, "LIVE")).status());
        assertEquals(EffectStatus.OK, oltp.live(new Dispatch("m1", 0, "adjust_ledger", Map.of(), "live", ledger, null, "LIVE")).status());
        assertEquals(EffectStatus.OK, oltp.compensate(new Dispatch("m1", 0, "adjust_ledger", Map.of(), "live", ledger, "INVERSE", "COMPENSATE")).status());
        assertEquals(EffectStatus.OK, oltp.compensate(new Dispatch("m2", 0, "adjust_ledger", Map.of(), "x", ledger, "DISCARD", "COMPENSATE")).status());
        assertEquals(1000, oltp.mainIdentity().get("A").intValue());
        Dispatch bad = new Dispatch("m3", 0, "adjust_ledger", Map.of("source", 1), "bad", ledger, null, "SHADOW");
        assertEquals(EffectStatus.FAILURE, oltp.shadow(bad).status());

        MemoryLakehouse lake = world.lake;
        ToolSpec stock = world.manifest.tool("move_stock");
        assertEquals(EffectStatus.FAILURE, lake.shadow(new Dispatch("l0", 0, "move_stock", Map.of("sku", "SKU-1", "qty", 1), "u", stockOf("missing"), null, "SHADOW")).status());
        Dispatch move = new Dispatch("l1", 0, "move_stock", Map.of("sku", "SKU-1", "qty", 1), "lk", stock, null, "SHADOW");
        lake.shadow(move);
        assertTrue(lake.reconcile(move).found());
        assertFalse(lake.reconcile(new Dispatch("l1", 0, "move_stock", Map.of(), "no", stock, null, "SHADOW")).found());
        assertEquals(EffectStatus.OK, lake.live(new Dispatch("l1", 0, "move_stock", Map.of(), "ll", stock, null, "LIVE")).status());
        assertEquals(EffectStatus.OK, lake.live(new Dispatch("l1", 0, "move_stock", Map.of(), "ll", stock, null, "LIVE")).status());
        assertEquals(0, lake.diff("l1").tables().size());
        assertEquals(EffectStatus.FAILURE, lake.live(new Dispatch("none", 0, "move_stock", Map.of(), "zz", stock, null, "LIVE")).status());
        lake.shadow(new Dispatch("l2", 0, "move_stock", Map.of("sku", "SKU-2", "qty", 1), "l2", stock, null, "SHADOW"));
        assertTrue(lake.diff("l2").tables().get(0).rowsChanged() >= 1);
        lake.shadow(new Dispatch("l2", 1, "move_stock", Map.of("sku", "SKU-2", "qty", 0), "l2b", stock, null, "SHADOW"));
        assertEquals(EffectStatus.OK, lake.compensate(new Dispatch("l1", 0, "move_stock", Map.of(), "ll", stock, "INVERSE", "COMPENSATE")).status());
        assertEquals(EffectStatus.OK, lake.compensate(new Dispatch("l2", 0, "move_stock", Map.of(), "l2", stock, "DISCARD", "COMPENSATE")).status());
        assertEquals(EffectStatus.FAILURE, lake.shadow(new Dispatch("l3", 0, "move_stock", Map.of("qty", 1), "bad", stock, null, "SHADOW")).status());

        ApiRuntime api = world.api;
        ToolSpec wire = world.manifest.tool("send_wire");
        ToolSpec reserve = world.manifest.tool("reserve_credit");
        assertEquals(EffectStatus.OK, api.live(new Dispatch("a", 0, "get_dispatch_status", Map.of("idempotency_key", "missing"), "s", wire, null, "LIVE")).status());
        assertEquals(EffectStatus.FAILURE, api.live(new Dispatch("a", 0, "void_credit", Map.of(), "v", reserve, null, "LIVE")).status());
        assertEquals(EffectStatus.OK, api.live(new Dispatch("a", 0, "void_credit", Map.of("reservation_id", "r"), "v2", reserve, null, "LIVE")).status());
        assertTrue(api.seen("void_credit", "v2"));
        Dispatch dryOff = new Dispatch("a", 1, "send_wire", Map.of("amount", 5), "dry", dryDisabled(), null, "SHADOW");
        assertEquals(Boolean.TRUE, api.shadow(dryOff).output().get("unverified"));
        Dispatch live = new Dispatch("a", 2, "send_wire", Map.of("amount", 5, "source", "A"), "wire", wire, null, "LIVE");
        assertEquals(EffectStatus.OK, api.live(live).status());
        assertEquals(EffectStatus.OK, api.live(live).status());
        assertEquals(1, api.applications("send_wire"));
        assertEquals(EffectStatus.FAILURE, api.live(new Dispatch("a", 3, "missing", Map.of(), "m", wire, null, "LIVE")).status());
        assertEquals(EffectStatus.OK, api.compensate(new Dispatch("a", 2, "send_wire", Map.of(), "wire", wire, "DISCARD", "COMPENSATE")).status());
        assertEquals(EffectStatus.FAILURE, api.compensate(new Dispatch("a", 2, "reserve_credit", Map.of(), "c", reserve, "INVERSE", "COMPENSATE")).status());
        EffectResult compensated = api.compensate(new Dispatch("a", 4, "reserve_credit", Map.of("reservation_id", "r"), "comp", reserve, "INVERSE", "COMPENSATE"));
        assertEquals(EffectStatus.OK, compensated.status());
        assertEquals(EffectStatus.OK, api.compensate(new Dispatch("a", 4, "reserve_credit", Map.of("reservation_id", "r"), "comp", reserve, "INVERSE", "COMPENSATE")).status());
        ToolSpec carrier = world.manifest.tool("dispatch_carrier");
        assertEquals(EffectStatus.UNKNOWN, api.reconcile(new Dispatch("a", 5, "dispatch_carrier", Map.of(), "gone", missingReconcile(), null, "LIVE")).status());
        assertFalse(api.reconcile(new Dispatch("a", 6, "send_wire", Map.of(), "absent", wire, null, "LIVE")).found());
        assertTrue(api.reconcile(live).found());
        ToolSpec unregisteredInverse = new ToolSpec("reserve_credit", "", Map.of("type", "object"), Reversibility.COMPENSATABLE, "api",
                "credit", "LOW", true, List.of(), null, "get_dispatch_status",
                new CompensationSpec("INVOKE_TOOL", "missing_void", Map.of("a", "input.a"), 1, "ESCALATE_HITL"), Map.of("ok", true), 1);
        assertEquals(EffectStatus.FAILURE, api.compensate(new Dispatch("a", 9, "reserve_credit", Map.of(), "mc", unregisteredInverse, "INVERSE", "COMPENSATE")).status());
        assertEquals(EffectStatus.OK, api.reconcile(new Dispatch("a", 8, "dispatch_carrier", Map.of(), "rec", carrier, null, "LIVE")).status());
        api.shadow(new Dispatch("money", 0, "reserve_credit", Map.of("amount", 4, "transfer_id", "t"), "md", reserve, null, "SHADOW"));
        assertEquals(1, api.diff("money").tables().size());
        api.shadow(new Dispatch("nomoney", 0, "dispatch_carrier", Map.of("shipment_id", "s"), "nd", carrier, null, "SHADOW"));
        assertEquals(0, api.diff("nomoney").tables().size());
    }

    @Test
    void jdbcGuardsAndCatalogClients() throws Exception {
        String url = "jdbc:h2:mem:adapter;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcOltp jdbc = new JdbcOltp(url, "sa", "");
        jdbc.seed(Map.of("A", new BigDecimal("20.00"), "B", new BigDecimal("20.00"), "C", new BigDecimal("20.00"), "D", new BigDecimal("20.00")));
        DemoWorld world = DemoWorld.create();
        ToolSpec ledger = world.manifest.tool("adjust_ledger");
        Dispatch shadow = new Dispatch("j1", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 5), "js", ledger, null, "SHADOW");
        assertEquals(EffectStatus.OK, jdbc.shadow(shadow).status());
        assertEquals(EffectStatus.OK, jdbc.shadow(shadow).status());
        assertEquals(EffectStatus.FAILURE, jdbc.shadow(new Dispatch("j2", 0, "adjust_ledger", Map.of("source", "A", "target", "NO", "amount", 1), "ju", ledger, null, "SHADOW")).status());
        assertEquals(EffectStatus.FAILURE, jdbc.shadow(new Dispatch("j3", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 100), "ji", ledger, null, "SHADOW")).status());
        assertEquals(EffectStatus.FAILURE, jdbc.shadow(new Dispatch("j4", 0, "adjust_ledger", Map.of("source", 1), "jb", ledger, null, "SHADOW")).status());
        assertFalse(jdbc.reconcile(new Dispatch("j9", 0, "adjust_ledger", Map.of(), "missing", ledger, null, "LIVE")).found());
        assertTrue(jdbc.reconcile(shadow).found());
        assertEquals(0, jdbc.diff("absent").tables().size());
        assertTrue(jdbc.diff("j1").financialDelta().signum() > 0);
        Dispatch live = new Dispatch("j1", 0, "adjust_ledger", Map.of(), "jl", ledger, null, "LIVE");
        assertEquals(EffectStatus.OK, jdbc.live(live).status());
        assertEquals(EffectStatus.OK, jdbc.live(live).status());
        assertEquals(EffectStatus.OK, jdbc.compensate(new Dispatch("j1", 0, "adjust_ledger", Map.of(), "jl", ledger, "INVERSE", "COMPENSATE")).status());
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("INSERT INTO atp_effect (effect_key, body) VALUES ('SHADOW:crafted', '{\"pre\":\"p\"}')");
            connection.createStatement().execute("DROP TABLE customer_ledger");
        }
        assertEquals(EffectStatus.OK, jdbc.shadow(new Dispatch("craft", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 1), "crafted", ledger, null, "SHADOW")).status());
        assertThrows(AtpException.class, () -> jdbc.shadow(new Dispatch("broken", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 1), "bx", ledger, null, "SHADOW")));
        try (java.sql.Connection connection = java.sql.DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("SHUTDOWN");
        }
        assertEquals(0, jdbc.diff("j1").tables().size());
        assertThrows(AtpException.class, () -> new JdbcOltp("jdbc:not-a-driver:x", "sa", ""));
        JdbcOltp injected = new JdbcOltp("jdbc:h2:mem:injected;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        java.sql.Connection backing = java.sql.DriverManager.getConnection(
                "jdbc:h2:mem:injected;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        injected.injected = (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(java.sql.Connection.class.getClassLoader(),
                new Class<?>[] {java.sql.Connection.class}, (proxy, method, args) -> {
                    if ("setAutoCommit".equals(method.getName())) {
                        throw new java.sql.SQLException("down");
                    }
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    return args == null ? method.invoke(backing) : method.invoke(backing, args);
                });
        assertEquals(0, injected.diff("closed").tables().size());

        SimpleHttp http = (method, target, headers, body) -> {
            if (target.contains("409")) {
                return new SimpleResponse(409, "");
            }
            if ("DELETE".equals(method) && target.contains("missing")) {
                return new SimpleResponse(404, "");
            }
            if ("DELETE".equals(method) && target.contains("denied")) {
                return new SimpleResponse(500, "");
            }
            if (target.contains("empty-refs")) {
                return new SimpleResponse(200, "{\"references\":{}}");
            }
            if ("GET".equals(method)) {
                return new SimpleResponse(200, "{\"references\":[{\"name\":\"main\",\"hash\":\"h1\"},{\"name\":\"atp_tx\",\"hash\":\"h2\"}]}");
            }
            if ("POST".equals(method) && target.contains("/branches")) {
                return new SimpleResponse(201, "{\"branch\":{\"id\":\"br-1\"}}");
            }
            return new SimpleResponse(204, "");
        };
        NessieClient nessie = new NessieClient(http, "http://nessie.local/api/v2/");
        assertEquals("h1", nessie.createBranch("atp_tx", "main"));
        nessie.mergeIntoMain("atp_tx");
        nessie.deleteBranch("missing");
        assertThrows(AtpException.class, () -> nessie.deleteBranch("denied"));
        assertThrows(AtpException.class, () -> new NessieClient(http, "http://nessie.local/empty-refs").createBranch("a", "main"));
        assertEquals("h1", new NessieClient((m, u, h, b) -> u.contains("name=conflict") ? new SimpleResponse(409, "") : http.send(m, u, h, b),
                "http://nessie.local/api/v2").createBranch("conflict", "main"));
        NeonClient neon = new NeonClient(http, "http://neon.local/api/v2", "project", "token");
        assertEquals("br-1", neon.createBranch("atp-tx"));
        assertEquals("br-1", neon.createBranch("atp-tx"));
        neon.deleteBranch("unknown");
        neon.deleteBranch("atp-tx");
        NeonClient missing = new NeonClient((m, u, h, b) -> "DELETE".equals(m) ? new SimpleResponse(404, "") : new SimpleResponse(201, "{\"branch\":{\"id\":\"br-2\"}}"),
                "http://neon.local", "project", "token");
        missing.createBranch("missing");
        missing.deleteBranch("missing");
        NeonClient denied = new NeonClient((method, target, headers, body) -> "DELETE".equals(method)
                ? new SimpleResponse(500, "") : new SimpleResponse(201, "{\"branch\":{\"id\":\"br-x\"}}"),
                "http://neon.local", "project", "token");
        denied.createBranch("denied");
        assertThrows(AtpException.class, () -> denied.deleteBranch("denied"));

        MemoryOltp inner = new MemoryOltp();
        inner.seed(Map.of("A", BigDecimal.TEN, "B", BigDecimal.TEN));
        CatalogBacking catalog = new CatalogBacking(inner, null);
        Dispatch transfer = new Dispatch("cat", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 1), "ck", ledger, null, "SHADOW");
        assertEquals(EffectStatus.OK, catalog.shadow(transfer).status());
        assertEquals(EffectStatus.FAILURE, catalog.live(new Dispatch("absent", 0, "adjust_ledger", Map.of(), "no", ledger, null, "LIVE")).status());
        catalog.reconcile(transfer);
        catalog.diff("cat");
        catalog.afterTerminal("cat", false);
        CatalogBacking named = new CatalogBacking(world.lake, nessie);
        Dispatch stockMove = new Dispatch("tx", 0, "move_stock", Map.of("sku", "SKU-1", "qty", 1), "sk", world.manifest.tool("move_stock"), null, "SHADOW");
        named.shadow(stockMove);
        named.live(new Dispatch("tx", 0, "move_stock", Map.of(), "skl", world.manifest.tool("move_stock"), null, "LIVE"));
        named.compensate(new Dispatch("tx", 0, "move_stock", Map.of(), "skl", world.manifest.tool("move_stock"), "INVERSE", "COMPENSATE"));
        named.afterTerminal("tx", true);
        NeonBacking neonBacking = new NeonBacking(inner, null);
        neonBacking.shadow(transfer);
        neonBacking.live(transfer);
        neonBacking.reconcile(transfer);
        neonBacking.diff("cat");
        neonBacking.afterTerminal("cat", true);
        NeonBacking real = new NeonBacking(inner, neon);
        real.shadow(new Dispatch("neo", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 1), "nk", ledger, null, "SHADOW"));
        real.compensate(new Dispatch("neo", 0, "adjust_ledger", Map.of(), "nk", ledger, "DISCARD", "COMPENSATE"));
        real.afterTerminal("neo", true);

        Runtime empty = new Runtime() {
            public PhasePort oltp() {
                return null;
            }

            public PhasePort lakehouse() {
                return null;
            }

            public PhasePort api() {
                return null;
            }
        };
        ToolSpec weird = new ToolSpec("t", "", Map.of("type", "object"), Reversibility.COMPENSATABLE, "ftp", "t", "LOW", true,
                List.of(), null, null, new CompensationSpec("NONE", null, Map.of(), 1, "ESCALATE_HITL"), Map.of(), 1);
        assertEquals(EffectStatus.FAILURE, empty.shadow(new Dispatch("e", 0, "t", Map.of(), "e", weird, null, "SHADOW")).status());
        assertEquals(0, empty.diff("e").tables().size());
        empty.afterTerminal("e", true);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] body = "{\"ok\":true}".getBytes();
            exchange.sendResponseHeaders(201, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/block", exchange -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            JdkSimpleHttp client = new JdkSimpleHttp();
            int port = server.getAddress().getPort();
            SimpleResponse posted = client.send("POST", "http://127.0.0.1:" + port + "/ok", null, null);
            assertEquals(201, posted.status());
            assertEquals(201, client.send("GET", "http://127.0.0.1:" + port + "/ok", Map.of("Accept", "application/json"), "").status());
            assertThrows(AtpException.class, () -> client.send("GET", "http://127.0.0.1:1/missing", Map.of(), ""));
            AtomicReference<Throwable> failed = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    client.send("DELETE", "http://127.0.0.1:" + port + "/block", null, "");
                } catch (Throwable ex) {
                    failed.set(ex);
                }
            });
            thread.start();
            Thread.sleep(100);
            thread.interrupt();
            thread.join(2_000);
            assertTrue(failed.get() instanceof AtpException);
        } finally {
            server.stop(0);
        }
    }

    private static ToolSpec stockOf(String table) {
        return new ToolSpec("move_stock", "", Map.of("type", "object"), Reversibility.ATOMIC_BRANCHABLE, "lakehouse", table, "LOW",
                true, List.of(), null, null, new CompensationSpec("RESTORE_SNAPSHOT", null, Map.of(), 1, "ESCALATE_HITL"), Map.of(), 1);
    }

    private static ToolSpec dryDisabled() {
        return new ToolSpec("send_wire", "", Map.of("type", "object"), Reversibility.IRREVERSIBLE, "api", "wires", "HIGH",
                false, List.of("amount"), null, null, new CompensationSpec("NONE", null, Map.of(), 1, "ESCALATE_HITL"), Map.of(), 1);
    }

    private static ToolSpec missingReconcile() {
        return new ToolSpec("dispatch_carrier", "", Map.of("type", "object"), Reversibility.COMPENSATABLE, "api", "shipments", "LOW",
                true, List.of(), null, "missing_tool", new CompensationSpec("INVOKE_TOOL", "void_dispatch", Map.of("a", "input.a"), 1, "ESCALATE_HITL"),
                Map.of("accepted", true), 1);
    }
}
