package org.atp.sdk;

import java.util.LinkedHashMap;
import java.util.Map;

/** Builds the MCP tool advertisement. Authorization still comes from the signed manifest. */
public final class ToolMetadata {
    private ToolMetadata() {
    }

    public static Map<String, Object> compensatable(String name, String description, Map<String, Object> inputSchema,
                                                     String compensationTool, Map<String, String> mapping) {
        Map<String, Object> transaction = new LinkedHashMap<>();
        transaction.put("reversibility", "COMPENSATABLE");
        transaction.put("compensation", Map.of(
                "tool", compensationTool,
                "argumentMapping", mapping,
                "maxRetries", 5,
                "onCompensationFailure", "ESCALATE_HITL"));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        Map<String, Object> meta = Map.of("org.agenttxprotocol/transaction", transaction);
        tool.put("_meta", meta);
        tool.put("x-atp-transaction", transaction);
        return tool;
    }
}
