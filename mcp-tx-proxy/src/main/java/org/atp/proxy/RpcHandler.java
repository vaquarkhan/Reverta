package org.atp.proxy;

import org.atp.demo.DemoWorld;
import org.atp.engine.TransactionEngine;
import org.atp.engine.TxSnapshot;
import org.atp.json.JsonCodec;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.Decision;
import org.atp.model.Types.StepRequest;
import org.atp.model.Types.ToolSpec;
import org.atp.replay.ReplayEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON-RPC 2.0 surface for MCP 2026-07-28 and the transaction extension. */
public final class RpcHandler {
    static final String PROTOCOL = "2026-07-28";
    static final String VERSION_KEY = "io.modelcontextprotocol/protocolVersion";
    static final String CAPABILITIES_KEY = "io.modelcontextprotocol/clientCapabilities";
    static final String TX_KEY = "org.agenttxprotocol/transaction";

    private final TransactionEngine engine;
    private final DemoWorld world;

    public RpcHandler(DemoWorld world, TransactionEngine engine) {
        this.world = world;
        this.engine = engine;
    }

    public String handle(String body) {
        Object id = null;
        try {
            Object parsed = JsonCodec.parse(body);
            if (!(parsed instanceof Map<?, ?> root)) {
                return error(null, -32600, "Invalid Request");
            }
            Map<String, Object> request = JsonCodec.asMap(parsed);
            id = request.get("id");
            if (!"2.0".equals(String.valueOf(request.get("jsonrpc")))) {
                return error(id, -32600, "Invalid Request");
            }
            String method = String.valueOf(request.get("method"));
            if ("null".equals(method) || method.isBlank()) {
                return error(id, -32600, "Invalid Request");
            }
            Map<String, Object> params = request.get("params") instanceof Map<?, ?> map ? JsonCodec.asMap(map) : Map.of();
            if (requiresMeta(method) && !modern(request.get("_meta"))) {
                return error(id, -32602, "MCP 2026-07-28 _meta.protocolVersion and clientCapabilities are required");
            }
            Object result = dispatch(method, params);
            if (result == null) {
                return error(id, -32601, "Method not found");
            }
            return ok(id, result);
        } catch (AtpException ex) {
            int code = ex.getMessage() != null && ex.getMessage().startsWith("invalid JSON") ? -32700 : ex.code().code();
            return error(id, code, ex.getMessage());
        } catch (RuntimeException ex) {
            return error(id, -32603, String.valueOf(ex.getMessage()));
        }
    }

    private Object dispatch(String method, Map<String, Object> params) {
        return switch (method) {
            case "server/discover" -> discover();
            case "tools/list" -> Map.of("tools", tools());
            case "tools/call" -> toolCall(params);
            case "tx/execute" -> snapshot(engine.execute(text(params, "txId"), steps(params), text(params, "reasoning")));
            case "tx/resume" -> snapshot(engine.resume(text(params, "txId"), Decision.valueOf(text(params, "decision"))));
            case "tx/abort" -> snapshot(engine.abort(text(params, "txId")));
            case "tx/status" -> snapshot(engine.status(text(params, "txId")));
            case "tx/replay" -> replay(params);
            case "tx/ledger" -> Map.of("entries", engine.ledger().stream().map(entry -> entry.toMap()).toList());
            default -> null;
        };
    }

    private Map<String, Object> discover() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false), "extensions", Map.of(
                "org.agenttxprotocol/tx", Map.of("version", "1.0.0"))));
        result.put("serverInfo", Map.of("name", "atp-tx-proxy", "version", "1.0.0"));
        result.put("instructions", "Mutating tools run inside a saga. IRREVERSIBLE tools wait for tx/resume.");
        return result;
    }

    private List<Map<String, Object>> tools() {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (ToolSpec spec : world.manifest.tools().values()) {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", spec.name());
            tool.put("description", spec.description());
            tool.put("inputSchema", spec.inputSchema());
            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("reversibility", spec.reversibility().name());
            tx.put("adapter", spec.adapter());
            tx.put("blastRadiusTier", spec.blastRadiusTier());
            tx.put("dryRunSupported", spec.dryRunSupported());
            tx.put("compensation", Map.of("mode", spec.compensation().mode(), "tool", String.valueOf(spec.compensation().tool())));
            tool.put("_meta", Map.of(TX_KEY, tx));
            listed.add(tool);
        }
        return listed;
    }

    private Map<String, Object> toolCall(Map<String, Object> params) {
        String name = text(params, "name");
        Map<String, Object> arguments = params.get("arguments") instanceof Map<?, ?> map ? JsonCodec.asMap(map) : Map.of();
        TxSnapshot snap = engine.execute(text(params, "txId"), List.of(new StepRequest(name, arguments)), text(params, "reasoning"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("content", List.of(Map.of("type", "text", "text", snap.state().name())));
        result.put("isError", false);
        result.put("structuredContent", snapshot(snap));
        return result;
    }

    private Map<String, Object> replay(Map<String, Object> params) {
        boolean sandbox = Boolean.TRUE.equals(params.get("sandbox"));
        int upTo = params.get("upToStep") instanceof Number number ? number.intValue() : 0;
        var replay = ReplayEngine.replay(engine.ledger(), text(params, "txId"), upTo, sandbox);
        return Map.of("txId", replay.txId(), "finalState", replay.finalState().name(), "entries",
                replay.timeline().stream().map(entry -> entry.toMap()).toList());
    }

    private static List<StepRequest> steps(Map<String, Object> params) {
        Object raw = params.get("steps");
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw new AtpException(org.atp.model.Types.AtpErrorCode.INVALID, "steps are required");
        }
        List<StepRequest> steps = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> step = JsonCodec.asMap(item);
            Map<String, Object> arguments = step.get("arguments") instanceof Map<?, ?> map ? JsonCodec.asMap(map) : Map.of();
            List<Integer> depends = new ArrayList<>();
            if (step.get("dependsOn") instanceof List<?> deps) {
                for (Object dep : deps) {
                    depends.add(((Number) dep).intValue());
                }
            }
            steps.add(new StepRequest(String.valueOf(step.get("tool")), arguments, depends));
        }
        return steps;
    }

    private static Map<String, Object> snapshot(TxSnapshot snap) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("txId", snap.txId());
        map.put("state", snap.state().name());
        map.put("errorCode", snap.errorCode());
        map.put("errorMessage", snap.errorMessage());
        map.put("holdReason", snap.holdReason());
        map.put("orphan", snap.orphan());
        return map;
    }

    private static boolean requiresMeta(String method) {
        return method.startsWith("tools/") || method.startsWith("tx/") || "server/discover".equals(method);
    }

    private static boolean modern(Object meta) {
        if (!(meta instanceof Map<?, ?> map)) {
            return false;
        }
        return PROTOCOL.equals(String.valueOf(map.get(VERSION_KEY))) && map.get(CAPABILITIES_KEY) != null;
    }

    private static String text(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String ok(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return JsonCodec.canonical(response);
    }

    private static String error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return JsonCodec.canonical(response);
    }
}
