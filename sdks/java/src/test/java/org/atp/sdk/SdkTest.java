package org.atp.sdk;

import com.sun.net.httpserver.HttpServer;
import org.atp.model.Types.AtpException;
import org.atp.proxy.ProxyServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkTest {
    @Test
    void clientTalksToTheProxyAndBuildsToolMetadata() throws Exception {
        Map<String, Object> tool = ToolMetadata.compensatable("transfer_inventory", "Moves stock",
                Map.of("type", "object"), "transfer_inventory", Map.of("sku", "input.sku"));
        assertEquals("COMPENSATABLE", ((Map<?, ?>) tool.get("x-atp-transaction")).get("reversibility"));
        assertTrue(tool.get("_meta").toString().contains("org.agenttxprotocol/transaction"));
        java.lang.reflect.Constructor<?> hidden = ToolMetadata.class.getDeclaredConstructor();
        hidden.setAccessible(true);
        hidden.newInstance();

        try (ProxyServer server = ProxyServer.demo(0)) {
            TxClient client = new TxClient("http://127.0.0.1:" + server.port());
            assertTrue(client.tools().stream().anyMatch(item -> "reserve_credit".equals(item.get("name"))));
            Map<String, Object> committed = client.execute("sdk-1", List.of(Map.of(
                    "tool", "reserve_credit",
                    "arguments", Map.of("transfer_id", "sdk", "amount", 1))), "sdk");
            assertEquals("COMMITTED", committed.get("state"));
            assertEquals("COMMITTED", client.status("sdk-1").get("state"));
            assertEquals("sdk-1", client.replay("sdk-1", 1).get("txId"));
            Map<String, Object> parked = client.execute("sdk-wire", List.of(Map.of(
                    "tool", "send_wire", "arguments", Map.of("source", "A", "amount", 1))), "sdk");
            assertEquals("ESCALATED_HITL", parked.get("state"));
            assertEquals("ROLLED_BACK", client.resume("sdk-wire", "REJECT").get("state"));
            assertEquals("COMMITTED", client.abort("sdk-1").get("state"));
            AtpException missing = assertThrows(AtpException.class, () -> client.resume("missing", "APPROVE"));
            assertTrue(missing.getMessage() != null);
        }

        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/error", exchange -> respond(exchange, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-31802,\"message\":\"denied\"}}"));
        fake.createContext("/text", exchange -> respond(exchange, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":\"x\"}}"));
        fake.createContext("/unknown", exchange -> respond(exchange, "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-31999,\"message\":\"no\"}}"));
        fake.createContext("/array", exchange -> respond(exchange, "[]"));
        fake.createContext("/empty", exchange -> respond(exchange, "{\"jsonrpc\":\"2.0\",\"result\":null}"));
        fake.start();
        try {
            int port = fake.getAddress().getPort();
            HttpClient http = HttpClient.newHttpClient();
            assertThrows(AtpException.class, () -> new TxClient(http, URI.create("http://127.0.0.1:" + port + "/error")).status("x"));
            assertThrows(AtpException.class, () -> new TxClient(http, URI.create("http://127.0.0.1:" + port + "/text")).status("x"));
            assertThrows(AtpException.class, () -> new TxClient(http, URI.create("http://127.0.0.1:" + port + "/unknown")).status("x"));
            assertThrows(AtpException.class, () -> new TxClient(http, URI.create("http://127.0.0.1:" + port + "/array")).status("x"));
            assertThrows(AtpException.class, () -> new TxClient(http, URI.create("http://127.0.0.1:" + port + "/empty")).status("x"));
        } finally {
            fake.stop(0);
        }

        assertThrows(AtpException.class, () -> new TxClient("http://127.0.0.1:1").status("x"));
        assertThrows(AtpException.class, () -> new TxClient("http://127.0.0.1:1/").status("x"));
        Thread.currentThread().interrupt();
        try {
            assertThrows(AtpException.class, () -> new TxClient("http://127.0.0.1:1").status("x"));
        } finally {
            Thread.interrupted();
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
