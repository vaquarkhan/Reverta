package org.atp.schema;

import org.atp.fsm.Transitions;
import org.atp.json.JsonCodec;
import org.atp.ledger.LedgerChain;
import org.atp.ledger.LedgerEntry;
import org.atp.ledger.Sanitizer;
import org.atp.model.Types;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.CompensationSpec;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.PolicyRule;
import org.atp.model.Types.Reversibility;
import org.atp.model.Types.StepRequest;
import org.atp.model.Types.TableDiff;
import org.atp.model.Types.ToolSpec;
import org.atp.model.Types.TxState;
import org.atp.replay.ReplayEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelTest {
    @Test
    void schemaProfileRejectsHallucinatedShapes() {
        assertEquals(List.of("schema is empty"), SchemaValidator.validate(Map.of(), Map.of()));
        Map<String, Object> schema = Map.of(
                "type", "object",
                "required", List.of("name"),
                "additionalProperties", false,
                "properties", Map.of(
                        "name", Map.of("type", "string", "minLength", 2, "maxLength", 4, "enum", List.of("ab", "abcd")),
                        "qty", Map.of("type", "integer", "minimum", 1, "maximum", 5),
                        "ok", Map.of("type", "boolean"),
                        "tags", Map.of("type", "array", "minItems", 1, "maxItems", 2, "items", Map.of("type", "string")),
                        "note", Map.of("type", "null"),
                        "score", Map.of("type", "number"),
                        "custom", Map.of("type", "widget")));
        Map<String, Object> valid = new java.util.LinkedHashMap<>();
        valid.put("name", "ab");
        valid.put("qty", 2);
        valid.put("ok", true);
        valid.put("tags", List.of("x"));
        valid.put("note", null);
        valid.put("score", 1.5);
        valid.put("custom", "z");
        assertTrue(SchemaValidator.validate(schema, valid).isEmpty());
        assertFalse(SchemaValidator.validate(schema, Map.of("name", "a", "qty", 0, "ok", "no", "tags", List.of(), "extra", 1)).isEmpty());
        assertFalse(SchemaValidator.validate(schema, "nope").isEmpty());
        assertFalse(SchemaValidator.validate(schema, Map.of("name", "abcde", "qty", 9, "tags", List.of("x", "y", "z"))).isEmpty());
        assertFalse(SchemaValidator.validate(Map.of("type", "string", "enum", java.util.Arrays.asList(1, null)), "x").isEmpty());
        assertTrue(SchemaValidator.validate(Map.of("enum", java.util.Arrays.asList((Object) null)), null).isEmpty());
        assertTrue(SchemaValidator.validate(Map.of("enum", List.of(2)), new BigDecimal("2.0")).isEmpty());
        assertFalse(SchemaValidator.validate(Map.of("type", "integer"), 1.5).isEmpty());
        assertTrue(SchemaValidator.validate(Map.of("type", "integer"), new BigDecimal("2.0")).isEmpty());
    }

    @Test
    void canonicalJsonLedgerAndReplayEdges() throws Exception {
        assertEquals("{\"a\":1,\"b\":[true,false,null]}", JsonCodec.canonical(Map.of("b", java.util.Arrays.asList(true, false, null), "a", 1)));
        assertEquals("1.5", JsonCodec.canonical(1.5d));
        assertEquals("100", JsonCodec.canonical(new BigDecimal("1E+2")));
        assertTrue(JsonCodec.pretty(Map.of("a", 1)).contains("\n"));
        assertThrows(AtpException.class, () -> JsonCodec.parse("{"));
        assertThrows(AtpException.class, () -> JsonCodec.parse("{\"a\":1,\"a\":2}"));
        assertThrows(AtpException.class, () -> JsonCodec.asMap("x"));
        assertThrows(AtpException.class, () -> JsonCodec.canonical(new Object()));
        assertThrows(AtpException.class, () -> JsonCodec.pretty(new Object()));
        var fromNode = JsonCodec.class.getDeclaredMethod("fromNode", com.fasterxml.jackson.databind.JsonNode.class);
        fromNode.setAccessible(true);
        assertNull(fromNode.invoke(null, com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.binaryNode(new byte[] {1})));
        assertEquals("\"\\\"\\\\\\b\\f\\n\\r\\t\\u0001\"", JsonCodec.canonical("\"\\\b\f\n\r\t\u0001"));

        assertEquals("[REDACTED]", ((Map<?, ?>) Sanitizer.sanitize(Map.of("api_key", "s", "ok", List.of(Map.of("card", "1"))))).get("api_key"));
        assertEquals("bearer", Sanitizer.sanitize("bearer"));
        assertEquals("Bearer", Sanitizer.sanitize("Bearer"));
        assertEquals(1, Sanitizer.sanitize(1));

        for (AtpErrorCode code : AtpErrorCode.values()) {
            assertTrue(code.code() < -31800);
            assertFalse(code.wireName().isBlank());
        }
        assertThrows(AtpException.class, () -> new StepRequest(" ", Map.of()));
        assertEquals(List.of(), new StepRequest("t", null).dependsOn());
        CompensationSpec spec = new CompensationSpec("NONE", null, null, 0, " ");
        assertEquals(1, spec.maxRetries());
        assertEquals("ESCALATE_HITL", spec.onFailure());
        assertThrows(AtpException.class, () -> new PolicyRule(null, "1", "DENY"));
        ToolSpec tool = new ToolSpec("t", "d", null, Reversibility.COMPENSATABLE, "api", "t", "LOW", true,
                null, null, null, new CompensationSpec("NONE", null, Map.of(), 1, "ESCALATE_HITL"), null, 0);
        assertEquals(1, tool.forwardRetries());
        TableDiff zero = new TableDiff("t", 0, 1, 1, false, null);
        assertEquals(BigDecimal.ZERO, zero.financialDelta());
        assertEquals(new BigDecimal("1.00000000"), zero.modifiedFraction());
        assertEquals(0, DiffReport.fromMap("nope").tables().size());
        assertEquals(0, DiffReport.fromMap(Map.of("tables", "no")).tables().size());
        DiffReport report = DiffReport.fromMap(Map.of("tables", List.of("skip", zero.toMap())));
        assertEquals(1, report.tables().size());
        assertNull(report.table("missing"));
        assertEquals(0, DiffReport.merge(null, DiffReport.empty()).tables().size());
        assertThrows(AtpException.class, () -> Types.asLong("x"));
        assertEquals(0, Types.asDecimal(null).signum());
        assertEquals(new BigDecimal("2.5"), Types.asDecimal("2.5"));
        assertEquals(2, Types.asInt(new BigDecimal("2")));

        assertFalse(Transitions.edges(TxState.COMMITTED).isEmpty() && Transitions.terminal(TxState.INIT));
        Transitions.require(TxState.INIT, TxState.INIT);
        assertThrows(AtpException.class, () -> Transitions.require(TxState.COMMITTED, TxState.INIT));

        LedgerEntry first = LedgerEntry.seal(1, "tx", "step-0", "0".repeat(64), "h", "tool",
                Sanitizer.sanitize(Map.of("password", "p")), null, null, "NOT_APPLICABLE", "SHADOW_EXECUTING");
        assertEquals("[REDACTED]", ((Map<?, ?>) first.sanitizedInput).get("password"));
        LedgerEntry broken = LedgerEntry.fromMap(mutated(first.toMap()));
        assertThrows(AtpException.class, broken::verifyHash);
        assertThrows(AtpException.class, () -> LedgerChain.check(List.of(broken), null));
        LedgerEntry second = LedgerEntry.seal(2, "tx", "step-4", "f".repeat(64), "h", "tool",
                Map.of(), "pre", "post", "NOT_APPLICABLE", "SHADOW_EXECUTING");
        assertThrows(AtpException.class, () -> LedgerChain.check(List.of(first, second), first.entryHash));
        assertEquals(LedgerChain.AnchorStatus.ABSENT, LedgerChain.check(List.of(), " "));
        assertThrows(AtpException.class, () -> LedgerChain.check(List.of(), first.entryHash));
        assertThrows(AtpException.class, () -> LedgerChain.check(List.of(first), "ab".repeat(32)));
        assertEquals(LedgerChain.AnchorStatus.HEALED, LedgerChain.check(List.of(first, secondWith(first)), first.entryHash));
        assertThrows(AtpException.class, () -> ReplayEngine.replay(List.of(first), "tx", 1, false));
        assertEquals(1, ReplayEngine.replay(List.of(first, secondWith(first)), "tx", 1, true).timeline().size());
        assertThrows(AtpException.class, () -> ReplayEngine.replay(List.of(), "missing", 1, true));
        LedgerEntry jumped = LedgerEntry.seal(1, "tx", "step-0", "0".repeat(64), "h", "tool",
                Map.of(), null, null, "NOT_APPLICABLE", "COMMITTED");
        assertThrows(AtpException.class, () -> ReplayEngine.replay(List.of(jumped), "tx", 0, true));
    }

    private static LedgerEntry secondWith(LedgerEntry first) {
        return LedgerEntry.seal(2, "tx", "step-4", first.entryHash, "h", "tool",
                Map.of(), "pre", "post", "NOT_APPLICABLE", "POLICY_EVAL");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutated(Map<String, Object> map) {
        Map<String, Object> copy = JsonCodec.asMap(JsonCodec.parse(JsonCodec.canonical(map)));
        copy.put("entryHash", "f".repeat(64));
        return copy;
    }
}
