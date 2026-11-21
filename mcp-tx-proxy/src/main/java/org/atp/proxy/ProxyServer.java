package org.atp.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.atp.demo.DemoWorld;
import org.atp.engine.TransactionEngine;
import org.atp.engine.TxSnapshot;
import org.atp.json.JsonCodec;
import org.atp.log.DurableLog;
import org.atp.log.FileDurableLog;
import org.atp.model.Types.Decision;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

/** Streamable HTTP endpoint plus the rollback timeline. */
public final class ProxyServer implements AutoCloseable {
    private final HttpServer server;
    private final RpcHandler rpc;
    private final TransactionEngine engine;
    private final DurableLog log;
    private final CountDownLatch stopped = new CountDownLatch(1);

    private ProxyServer(HttpServer server, RpcHandler rpc, TransactionEngine engine, DurableLog log) {
        this.server = server;
        this.rpc = rpc;
        this.engine = engine;
        this.log = log;
    }

    public static ProxyServer demo(int port) throws IOException {
        return open(port, Logs.openFromEnvironment());
    }

    public static ProxyServer open(int port, DurableLog log) throws IOException {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(log);
        RpcHandler rpc = new RpcHandler(world, engine);
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        ProxyServer proxy = new ProxyServer(http, rpc, engine, log);
        http.createContext("/", proxy::route);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        return proxy;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public TransactionEngine engine() {
        return engine;
    }

    public void await() throws InterruptedException {
        stopped.await();
    }

    @Override
    public void close() throws IOException {
        server.stop(0);
        stopped.countDown();
        if (log instanceof FileDurableLog file) {
            file.close();
        }
    }

    private void route(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/".equals(path)) {
                bytes(exchange, 200, "text/html; charset=utf-8", Timeline.html().getBytes(StandardCharsets.UTF_8));
            } else if ("GET".equals(method) && "/api/ledger".equals(path)) {
                json(exchange, 200, rpc.handle(ledgerRequest()));
            } else if ("POST".equals(method) && "/mcp".equals(path)) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response = rpc.handle(body);
                String accept = exchange.getRequestHeaders().getFirst("Accept");
                if (accept != null && accept.contains("text/event-stream")) {
                    bytes(exchange, 200, "text/event-stream", ("data: " + response + "\n\n").getBytes(StandardCharsets.UTF_8));
                } else {
                    json(exchange, 200, response);
                }
            } else if ("POST".equals(method) && "/api/demo/rollback".equals(path)) {
                TxSnapshot snap = engine.execute("ui-rollback", DemoWorld.rollbackPlan(), "timeline demo");
                json(exchange, 200, JsonCodec.canonical(Map.of("state", snap.state().name(), "txId", snap.txId())));
            } else if ("POST".equals(method) && "/api/demo/approve-setup".equals(path)) {
                TxSnapshot snap = engine.execute(wireId(exchange), java.util.List.of(
                        new org.atp.model.Types.StepRequest("send_wire", Map.of("source", "A", "amount", 1))), "timeline demo");
                json(exchange, 200, JsonCodec.canonical(Map.of("state", snap.state().name(), "txId", snap.txId(),
                        "holdReason", String.valueOf(snap.holdReason()))));
            } else if ("POST".equals(method) && "/api/demo/decision".equals(path)) {
                Map<String, Object> body = JsonCodec.asMap(JsonCodec.parse(new String(read(exchange), StandardCharsets.UTF_8)));
                TxSnapshot snap = engine.resume(String.valueOf(body.get("txId")), Decision.valueOf(String.valueOf(body.get("decision"))));
                json(exchange, 200, JsonCodec.canonical(Map.of("state", snap.state().name(), "txId", snap.txId())));
            } else {
                bytes(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException ex) {
            json(exchange, 400, JsonCodec.canonical(Map.of("error", ex.getMessage() == null ? "bad request" : ex.getMessage())));
        }
    }

    private static String wireId(HttpExchange exchange) throws IOException {
        String raw = new String(read(exchange), StandardCharsets.UTF_8);
        if (raw.isBlank()) {
            return "ui-wire";
        }
        Map<String, Object> body = JsonCodec.asMap(JsonCodec.parse(raw));
        Object txId = body.get("txId");
        if (txId == null || String.valueOf(txId).isBlank()) {
            return "ui-wire";
        }
        return String.valueOf(txId);
    }

    private static String ledgerRequest() {
        return JsonCodec.canonical(Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "tx/ledger",
                "params", Map.of(),
                "_meta", Map.of(RpcHandler.VERSION_KEY, RpcHandler.PROTOCOL, RpcHandler.CAPABILITIES_KEY, Map.of())));
    }

    private static byte[] read(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    private static void json(HttpExchange exchange, int status, String body) throws IOException {
        bytes(exchange, status, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void bytes(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
