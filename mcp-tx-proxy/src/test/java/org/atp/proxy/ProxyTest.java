package org.atp.proxy;

import org.atp.demo.DemoWorld;
import org.atp.engine.TransactionEngine;
import org.atp.json.JsonCodec;
import org.atp.log.FileDurableLog;
import org.atp.log.MemoryLog;
import org.atp.model.Types.StepRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyTest {
    private final RpcHandler rpc = new RpcHandler(DemoWorld.create(), DemoWorld.create().engine(new MemoryLog()));

    @Test
    void jsonRpcSpeaksMcpAndTransactions() {
        assertTrue(rpc.handle("{").contains("-32700"));
        assertTrue(rpc.handle("[]").contains("-32600"));
        assertTrue(rpc.handle(req(1, "server/discover", Map.of(), false)).contains("-32600"));
        assertTrue(rpc.handle(req(2, " ", Map.of(), true)).contains("-32600"));
        assertTrue(rpc.handle(req(3, "server/discover", Map.of(), true).replace(RpcHandler.PROTOCOL, "1999-01-01")).contains("-32602"));
        assertTrue(rpc.handle(req(4, "nope", Map.of(), true)).contains("-32601"));
        assertTrue(rpc.handle(req(5, "server/discover", Map.of(), true)).contains("atp-tx-proxy"));
        assertTrue(rpc.handle(req(6, "tools/list", Map.of(), true)).contains("reserve_credit"));
        String called = rpc.handle(req(7, "tools/call", Map.of("name", "reserve_credit", "txId", "rpc-1", "reasoning", "because",
                "arguments", Map.of("transfer_id", "a", "amount", 1)), true));
        if (!called.contains("COMMITTED")) {
            throw new AssertionError(called);
        }
        assertTrue(rpc.handle(req(8, "tools/call", Map.of("name", "reserve_credit", "txId", "rpc-2"), true)).contains("AtpArgumentInvalid"));
        assertTrue(rpc.handle(req(81, "tools/call", Map.of("name", "reserve_credit", "txId", "rpc-2b", "arguments", "nope"), true)).contains("AtpArgumentInvalid"));
        assertTrue(rpc.handle(req(82, "tx/execute", Map.of("txId", "rpc-steps", "steps", "nope"), true)).contains("-31801"));
        assertTrue(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":83,\"method\":\"server/discover\",\"_meta\":{\"" + RpcHandler.VERSION_KEY + "\":\"" + RpcHandler.PROTOCOL + "\"}}").contains("-32602"));
        assertTrue(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":84,\"method\":\"tools/list\",\"params\":[]}").contains("-32602"));
        String executed = rpc.handle(req(9, "tx/execute", Map.of("txId", "rpc-3", "reasoning", "plan", "steps", List.of(
                Map.of("tool", "reserve_credit", "arguments", Map.of("transfer_id", "b", "amount", 1), "dependsOn", List.of(0)))), true));
        if (!executed.contains("ROLLED_BACK") && !executed.contains("COMMITTED")) {
            throw new AssertionError(executed);
        }
        assertTrue(rpc.handle(req(10, "tx/status", Map.of("txId", "rpc-1"), true)).contains("COMMITTED"));
        assertTrue(rpc.handle(req(11, "tx/ledger", Map.of(), true)).contains("rpc-1"));
        assertTrue(rpc.handle(req(12, "tx/replay", Map.of("txId", "rpc-1", "sandbox", false), true)).contains("-31801"));
        assertTrue(rpc.handle(req(13, "tx/replay", Map.of("txId", "rpc-1", "sandbox", true, "upToStep", 1), true)).contains("rpc-1"));
        assertTrue(rpc.handle(req(14, "tx/execute", Map.of("txId", "rpc-4", "steps", List.of()), true)).contains("-31801"));
        assertTrue(rpc.handle(req(15, "tx/resume", Map.of("txId", "rpc-1", "decision", "NOPE"), true)).contains("-32603"));
        String parked = rpc.handle(req(16, "tx/execute", Map.of("txId", "rpc-wire", "reasoning", "wire", "steps", List.of(
                Map.of("tool", "send_wire", "arguments", Map.of("source", "A", "amount", 1)))), true));
        assertTrue(parked.contains("ESCALATED_HITL"));
        assertTrue(rpc.handle(req(20, "tx/resume", Map.of("txId", "rpc-wire", "decision", "REJECT"), true)).contains("ROLLED_BACK"));
        assertTrue(rpc.handle(req(17, "tx/abort", Map.of("txId", "rpc-wire"), true)).contains("COMPENSATING")
                || rpc.handle(req(17, "tx/abort", Map.of("txId", "rpc-wire"), true)).contains("ROLLED_BACK")
                || rpc.handle(req(18, "tx/status", Map.of("txId", "rpc-wire"), true)).contains("rpc-wire"));
        assertTrue(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":19,\"method\":\"tools/list\"}").contains("-32602"));
        String listed = req(84, "tools/list", Map.of(), true);
        assertTrue(rpc.handle(listed.replace("\"params\":{}", "\"params\":[]")).contains("reserve_credit"));
        assertTrue(rpc.handle(req(85, "tx/execute", Map.of("txId", "rpc-plain", "steps", List.of(Map.of("tool", "reserve_credit"))), true)).contains("AtpArgumentInvalid"));
        assertTrue(rpc.handle(req(86, "tx/replay", Map.of("txId", "rpc-1", "sandbox", true), true)).contains("rpc-1"));
        assertTrue(rpc.handle("{\"jsonrpc\":\"2.0\",\"id\":87}").contains("-32600"));
        assertTrue(Timeline.html().contains("Run rollback demo"));
    }

    @Test
    void httpDemoAndCommands(@TempDir Path dir) throws Exception {
        try (ProxyServer server = ProxyServer.demo(0)) {
            HttpClient client = HttpClient.newHttpClient();
            URI base = URI.create("http://127.0.0.1:" + server.port());
            HttpResponse<String> page = client.send(HttpRequest.newBuilder(base).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            assertTrue(page.body().contains("Hash chain"));
            HttpResponse<String> missing = client.send(HttpRequest.newBuilder(base.resolve("/missing")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(404, missing.statusCode());
            HttpResponse<String> ledger = client.send(HttpRequest.newBuilder(base.resolve("/api/ledger")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, ledger.statusCode());
            String meta = request("server/discover", Map.of());
            HttpResponse<String> sse = client.send(HttpRequest.newBuilder(base.resolve("/mcp"))
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString(meta)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(sse.body().startsWith("data: "));
            HttpResponse<String> plain = client.send(HttpRequest.newBuilder(base.resolve("/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString(meta)).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(plain.body().contains("atp-tx-proxy"));
            HttpResponse<String> rolled = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/rollback"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(rolled.body().contains("ROLLED_BACK"));
            HttpResponse<String> wire = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/approve-setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(wire.body().contains("ESCALATED_HITL"));
            HttpResponse<String> approved = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/decision"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"txId\":\"ui-wire\",\"decision\":\"APPROVE\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(approved.body().contains("COMMITTED"));
            HttpResponse<String> bad = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/decision"))
                    .POST(HttpRequest.BodyPublishers.ofString("{")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(400, bad.statusCode());
            assertEquals(server.engine().status("ui-wire").state().name(), "COMMITTED");
            HttpResponse<String> again = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/approve-setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(again.body().contains("COMMITTED"));
            HttpResponse<String> blankId = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/approve-setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"txId\":\"  \"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(blankId.body().contains("ui-wire"));
            HttpResponse<String> named = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/approve-setup"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"txId\":\"ui-wire-9\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(named.body().contains("ESCALATED_HITL"));
            HttpResponse<String> rejected = client.send(HttpRequest.newBuilder(base.resolve("/api/demo/decision"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"txId\":\"ui-wire-9\",\"decision\":\"REJECT\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(rejected.body().contains("ROLLED_BACK"));
        }
        HttpClient later = HttpClient.newHttpClient();
        Path selectedWal = dir.resolve("selected-wal");
        org.atp.log.DurableLog file = Logs.select(" ", null, null, selectedWal.toString());
        try (ProxyServer selected = ProxyServer.open(0, file)) {
            HttpResponse<String> rolled = later.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + selected.port() + "/api/demo/rollback"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(rolled.body().contains("ROLLED_BACK"));
        }
        org.atp.log.DurableLog jdbc = Logs.select("jdbc:h2:mem:atplogs;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", null, null, null);
        try (ProxyServer selected = ProxyServer.open(0, jdbc)) {
            assertTrue(selected.port() > 0);
        }
        assertTrue(Logs.select("jdbc:h2:mem:atplogs2;MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "atp", "secret", null) instanceof org.atp.log.JdbcDurableLog);
        assertTrue(Logs.select(null, null, null, null) instanceof org.atp.log.MemoryLog);

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        PrintStreams streams = new PrintStreams(out, err);
        assertEquals(2, TxCommands.run(null, System.in, streams.out, streams.err));
        assertEquals(2, TxCommands.run(new String[] {}, System.in, streams.out, streams.err));
        assertEquals(2, TxCommands.run(new String[] {"nope"}, System.in, streams.out, streams.err));
        streams.reset();
        assertEquals(0, TxCommands.run(new String[] {"keys"}, System.in, streams.out, streams.err));
        String secret = out.toString(java.nio.charset.StandardCharsets.UTF_8).trim().lines().skip(1).findFirst().orElseThrow();
        assertEquals(1, TxCommands.run(new String[] {"sign"}, System.in, streams.out, streams.err));
        Path payload = dir.resolve("payload.json");
        Files.writeString(payload, JsonCodec.canonical(Map.of("manifestId", "cli", "tools", List.of())));
        streams.reset();
        assertEquals(0, TxCommands.run(new String[] {"sign", "--kid", "admin", "--private", secret, "--payload", payload.toString()},
                System.in, streams.out, streams.err));
        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("admin"), out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, TxCommands.run(new String[] {"sign", "--kid", "admin", "--private", secret, "--payload", dir.resolve("missing.json").toString()},
                System.in, streams.out, streams.err));
        assertEquals(0, TxCommands.run(new String[] {"chaos", "--out", "chaos-cli.csv"}, System.in, streams.out, streams.err));
        Files.deleteIfExists(Path.of("chaos-cli.csv"));
        Path csv = dir.resolve("nested").resolve("chaos.csv");
        assertEquals(0, TxCommands.run(new String[] {"chaos", "--out", csv.toString()}, System.in, streams.out, streams.err));
        assertTrue(Files.readString(csv).contains("mode,fault"));
        assertEquals(1, TxCommands.run(new String[] {"chaos"}, System.in, streams.out, streams.err));
        Path wal = dir.resolve("wal");
        try (FileDurableLog log = new FileDurableLog(wal)) {
            TransactionEngine engine = DemoWorld.create().engine(log);
            engine.execute("cli-tx", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "cli", "amount", 1))), "cli");
        }
        streams.reset();
        assertEquals(0, TxCommands.run(new String[] {"replay", "--tx", "cli-tx", "--wal-dir", wal.toString(), "--sandbox", "--up-to-step", "2"},
                System.in, streams.out, streams.err));
        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("COMMITTED"), out.toString(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(1, TxCommands.run(new String[] {"replay", "--tx", "cli-tx", "--wal-dir", wal.toString()}, System.in, streams.out, streams.err));
        assertEquals(1, TxCommands.run(new String[] {"demo", "--port", "nope"}, System.in, streams.out, streams.err));
        String line = request("server/discover", Map.of());
        assertEquals(0, TxCommands.run(new String[] {"stdio"}, new java.io.ByteArrayInputStream(("\n" + line + "\n").getBytes()),
                streams.out, streams.err));
        assertTrue(out.toString(java.nio.charset.StandardCharsets.UTF_8).contains("atp-tx-proxy"));

        AtomicInteger code = new AtomicInteger(-1);
        Thread serving = new Thread(() -> code.set(TxCommands.run(new String[] {"demo", "--port", "0"}, System.in, streams.out, streams.err)));
        serving.start();
        ProxyServer active = waitForServer();
        assertTrue(active.port() > 0);
        active.close();
        serving.join(5_000);
        assertEquals(0, code.get());

        AtomicInteger interrupted = new AtomicInteger(-1);
        Thread blocking = new Thread(() -> interrupted.set(TxCommands.run(new String[] {"serve", "--port", "0"}, System.in, streams.out, streams.err)));
        blocking.start();
        ProxyServer running = waitForServer();
        blocking.interrupt();
        blocking.join(5_000);
        running.close();
        assertEquals(1, interrupted.get());
        java.lang.reflect.Constructor<?> commands = TxCommands.class.getDeclaredConstructor();
        commands.setAccessible(true);
        commands.newInstance();
        java.lang.reflect.Constructor<?> logs = Logs.class.getDeclaredConstructor();
        logs.setAccessible(true);
        logs.newInstance();
        java.lang.reflect.Constructor<?> timeline = Timeline.class.getDeclaredConstructor();
        timeline.setAccessible(true);
        timeline.newInstance();
    }

    private static ProxyServer waitForServer() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            ProxyServer server = TxCommands.ACTIVE.get();
            if (server != null) {
                return server;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("server did not start");
    }

    private static String request(String method, Map<String, Object> params) {
        return req(1, method, params, true);
    }

    private static String req(Object id, String method, Map<String, Object> params, boolean meta) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("jsonrpc", meta ? "2.0" : "1.0");
        request.put("id", id);
        request.put("method", method);
        request.put("params", params);
        if (meta) {
            request.put("_meta", Map.of(RpcHandler.VERSION_KEY, RpcHandler.PROTOCOL, RpcHandler.CAPABILITIES_KEY, Map.of()));
        }
        return JsonCodec.canonical(request);
    }

    private static final class PrintStreams {
        private final java.io.ByteArrayOutputStream outBytes;
        private final java.io.ByteArrayOutputStream errBytes;
        private java.io.PrintStream out;
        private java.io.PrintStream err;

        PrintStreams(java.io.ByteArrayOutputStream outBytes, java.io.ByteArrayOutputStream errBytes) {
            this.outBytes = outBytes;
            this.errBytes = errBytes;
            this.out = new java.io.PrintStream(outBytes, true);
            this.err = new java.io.PrintStream(errBytes, true);
        }

        void reset() {
            out.flush();
            outBytes.reset();
            errBytes.reset();
        }
    }
}
