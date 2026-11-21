package org.atp.sdk;

import org.atp.json.JsonCodec;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-RPC client for an ATP proxy. A Spring AI 2.1 MODERN client can point at the same /mcp URL. */
public final class TxClient {
    private final HttpClient http;
    private final URI endpoint;

    public TxClient(String baseUrl) {
        this(HttpClient.newHttpClient(), endpoint(baseUrl));
    }

    TxClient(HttpClient http, URI endpoint) {
        this.http = http;
        this.endpoint = endpoint;
    }

    public Map<String, Object> execute(String txId, List<Map<String, Object>> steps, String reasoning) {
        return call("tx/execute", Map.of("txId", txId, "steps", steps, "reasoning", reasoning));
    }

    public Map<String, Object> resume(String txId, String decision) {
        return call("tx/resume", Map.of("txId", txId, "decision", decision));
    }

    public Map<String, Object> abort(String txId) {
        return call("tx/abort", Map.of("txId", txId));
    }

    public Map<String, Object> status(String txId) {
        return call("tx/status", Map.of("txId", txId));
    }

    public Map<String, Object> replay(String txId, int upToStep) {
        return call("tx/replay", Map.of("txId", txId, "sandbox", true, "upToStep", upToStep));
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> tools() {
        return (List<Map<String, Object>>) call("tools/list", Map.of()).get("tools");
    }

    private Map<String, Object> call(String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.put("params", params);
        request.put("_meta", Map.of(
                "io.modelcontextprotocol/protocolVersion", "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities", Map.of()));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonCodec.canonical(request)))
                .build();
        try {
            HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            return result(response.body());
        } catch (AtpException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AtpException(AtpErrorCode.INVALID, "interrupted");
        } catch (Exception ex) {
            throw new AtpException(AtpErrorCode.INVALID, ex.getMessage() == null ? "request failed" : ex.getMessage());
        }
    }

    private static Map<String, Object> result(String body) {
        Object parsed = JsonCodec.parse(body);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new AtpException(AtpErrorCode.INVALID, "response is not an object");
        }
        Map<String, Object> response = JsonCodec.asMap(parsed);
        if (response.get("error") instanceof Map<?, ?> error) {
            Map<String, Object> details = JsonCodec.asMap(error);
            int code = details.get("code") instanceof Number number ? number.intValue() : AtpErrorCode.INVALID.code();
            throw new AtpException(lookup(code), String.valueOf(details.get("message")));
        }
        return JsonCodec.asMap(response.get("result"));
    }

    private static AtpErrorCode lookup(int code) {
        for (AtpErrorCode candidate : AtpErrorCode.values()) {
            if (candidate.code() == code) {
                return candidate;
            }
        }
        return AtpErrorCode.INVALID;
    }

    private static URI endpoint(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(trimmed + "/mcp");
    }
}
