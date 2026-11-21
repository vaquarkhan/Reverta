// SPDX-License-Identifier: Apache-2.0
package org.atp.policy;

import org.atp.crypto.Hashing;
import org.atp.crypto.Signatures;
import org.atp.expr.Expressions;
import org.atp.expr.Expressions.EvalContext;
import org.atp.expr.Expressions.ExprException;
import org.atp.json.JsonCodec;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.CompensationSpec;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.PolicyRule;
import org.atp.model.Types.Reversibility;
import org.atp.model.Types.TableDiff;
import org.atp.model.Types.ToolSpec;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** Signed admin manifest and the blast-radius gate. Tool annotations never enter this class. */
public final class Policy {
    private Policy() {
    }

    public record Decision(String outcome, String reason) {
        public boolean allow() {
            return "ALLOW".equals(outcome);
        }

        public boolean deny() {
            return "DENY".equals(outcome);
        }
    }

    public static final class SignedManifest {
        private final String manifestId;
        private final Map<String, ToolSpec> tools;
        private final List<PolicyRule> rules;
        private final String kid;

        private SignedManifest(String manifestId, Map<String, ToolSpec> tools, List<PolicyRule> rules, String kid) {
            this.manifestId = manifestId;
            this.tools = tools;
            this.rules = rules;
            this.kid = kid;
        }

        public String manifestId() {
            return manifestId;
        }

        public ToolSpec tool(String name) {
            return tools.get(name);
        }

        public Map<String, ToolSpec> tools() {
            return tools;
        }

        public List<PolicyRule> rules() {
            return rules;
        }

        public String kid() {
            return kid;
        }

        public static SignedManifest open(String document, Map<String, PublicKey> trusted) {
            Object parsed = JsonCodec.parse(document);
            Map<String, Object> root = JsonCodec.asMap(parsed);
            Object payload = root.get("payload");
            if (!(payload instanceof Map<?, ?>)) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "payload missing");
            }
            Map<String, Object> signature = JsonCodec.asMap(root.get("signature"));
            String alg = String.valueOf(signature.get("alg"));
            String kid = String.valueOf(signature.get("kid"));
            if (!"Ed25519".equals(alg)) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "unsupported alg " + alg);
            }
            PublicKey key = trusted.get(kid);
            if (key == null) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "untrusted kid " + kid);
            }
            byte[] body = JsonCodec.canonical(payload).getBytes(StandardCharsets.UTF_8);
            byte[] sig = Signatures.decode(String.valueOf(signature.get("sig")));
            if (!Signatures.verify(key, body, sig)) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "signature mismatch");
            }
            return interpret(JsonCodec.asMap(payload), kid);
        }

        @SuppressWarnings("unchecked")
        private static SignedManifest interpret(Map<String, Object> payload, String kid) {
            String id = String.valueOf(payload.get("manifestId"));
            if (id.isBlank() || "null".equals(id)) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "manifestId required");
            }
            Object rawTools = payload.get("tools");
            if (!(rawTools instanceof List<?> list) || list.isEmpty()) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "tools required");
            }
            Map<String, ToolSpec> tools = new LinkedHashMap<>();
            for (Object item : list) {
                ToolSpec spec = tool(JsonCodec.asMap(item));
                if (tools.put(spec.name(), spec) != null) {
                    throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "duplicate tool " + spec.name());
                }
            }
            List<PolicyRule> rules = new ArrayList<>();
            Object rawRules = payload.get("rules");
            if (rawRules instanceof List<?> ruleList) {
                for (Object item : ruleList) {
                    Map<String, Object> rule = JsonCodec.asMap(item);
                    String onFail = String.valueOf(rule.get("onFail"));
                    if (!"DENY".equals(onFail) && !"PAUSE".equals(onFail)) {
                        throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "onFail must be DENY or PAUSE");
                    }
                    String expression = String.valueOf(rule.get("expression"));
                    try {
                        Expressions.checkSyntax(expression);
                    } catch (ExprException ex) {
                        throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "bad rule: " + ex.getMessage());
                    }
                    rules.add(new PolicyRule(String.valueOf(rule.get("id")), expression, onFail));
                }
            }
            return new SignedManifest(id, Map.copyOf(tools), List.copyOf(rules), kid);
        }

        @SuppressWarnings("unchecked")
        private static ToolSpec tool(Map<String, Object> raw) {
            String name = String.valueOf(raw.get("name"));
            Map<String, Object> schema = raw.get("inputSchema") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            if (!"object".equals(String.valueOf(schema.get("type")))) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, name + " inputSchema must be an object");
            }
            Map<String, Object> tx = JsonCodec.asMap(raw.get("transaction"));
            Reversibility reversibility;
            try {
                reversibility = Reversibility.valueOf(String.valueOf(tx.get("reversibility")));
            } catch (IllegalArgumentException ex) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "bad reversibility for " + name);
            }
            String adapter = String.valueOf(tx.get("adapter"));
            if (!adapter.equals("oltp") && !adapter.equals("lakehouse") && !adapter.equals("api")) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "bad adapter for " + name);
            }
            CompensationSpec compensation = compensation(tx.get("compensation"), reversibility, name);
            boolean dry = !Boolean.FALSE.equals(tx.get("dryRunSupported"));
            Map<String, Object> example = tx.get("dryRunExample") instanceof Map<?, ?> map
                    ? (Map<String, Object>) map : Map.of();
            if ("api".equals(adapter) && dry && example.isEmpty()) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, name + " requires dryRunExample");
            }
            int retries = tx.get("forwardRetries") instanceof Number n ? n.intValue() : 2;
            List<String> money = new ArrayList<>();
            if (tx.get("moneyColumns") instanceof List<?> cols) {
                for (Object col : cols) {
                    money.add(String.valueOf(col));
                }
            }
            return new ToolSpec(
                    name,
                    raw.get("description") == null ? "" : String.valueOf(raw.get("description")),
                    schema,
                    reversibility,
                    adapter,
                    tx.get("table") == null ? name : String.valueOf(tx.get("table")),
                    tx.get("blastRadiusTier") == null ? "MEDIUM" : String.valueOf(tx.get("blastRadiusTier")),
                    dry,
                    money,
                    tx.get("idempotencyKeyPath") == null ? null : String.valueOf(tx.get("idempotencyKeyPath")),
                    tx.get("reconciliationTool") == null ? null : String.valueOf(tx.get("reconciliationTool")),
                    compensation,
                    example,
                    retries);
        }

        @SuppressWarnings("unchecked")
        private static CompensationSpec compensation(Object raw, Reversibility reversibility, String name) {
            if (reversibility == Reversibility.COMPENSATABLE && !(raw instanceof Map<?, ?>)) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, name + " requires compensation");
            }
            if (!(raw instanceof Map<?, ?> map)) {
                String mode = reversibility == Reversibility.ATOMIC_BRANCHABLE ? "RESTORE_SNAPSHOT" : "NONE";
                return new CompensationSpec(mode, null, Map.of(), 5, "ESCALATE_HITL");
            }
            Map<String, Object> obj = (Map<String, Object>) map;
            Map<String, String> mapping = new LinkedHashMap<>();
            if (obj.get("argumentMapping") instanceof Map<?, ?> args) {
                for (Map.Entry<?, ?> entry : args.entrySet()) {
                    mapping.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
                }
            }
            int retries = obj.get("maxRetries") instanceof Number n ? Math.max(1, n.intValue()) : 5;
            String mode = obj.get("mode") == null
                    ? (reversibility == Reversibility.ATOMIC_BRANCHABLE ? "RESTORE_SNAPSHOT" : "INVOKE_TOOL")
                    : String.valueOf(obj.get("mode"));
            if (reversibility == Reversibility.COMPENSATABLE && (obj.get("tool") == null || mapping.isEmpty())) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, name + " compensation mapping is incomplete");
            }
            try {
                for (String expression : mapping.values()) {
                    Expressions.checkSyntax(expression);
                }
            } catch (ExprException ex) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "bad mapping: " + ex.getMessage());
            }
            return new CompensationSpec(mode, obj.get("tool") == null ? null : String.valueOf(obj.get("tool")),
                    mapping, retries, obj.get("onCompensationFailure") == null ? "ESCALATE_HITL"
                    : String.valueOf(obj.get("onCompensationFailure")));
        }
    }

    public static String sign(Map<String, Object> payload, String kid, java.security.PrivateKey privateKey) {
        byte[] body = JsonCodec.canonical(payload).getBytes(StandardCharsets.UTF_8);
        String sig = Signatures.encode(Signatures.sign(privateKey, body));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("payload", payload);
        document.put("signature", Map.of("alg", "Ed25519", "kid", kid, "sig", sig));
        return JsonCodec.canonical(document);
    }

    public static Decision evaluate(SignedManifest manifest, DiffReport diff, List<ToolSpec> steps) {
        Map<String, Function<List<Object>, Object>> functions = new HashMap<>(Expressions.baseFunctions());
        functions.put("tableFraction", args -> fraction(diff, args));
        functions.put("schemaChanged", args -> changed(diff, args));
        functions.put("rowDelta", args -> {
            requireOne(args);
            TableDiff table = diff.table(String.valueOf(args.get(0)));
            return table == null ? java.math.BigDecimal.ZERO : java.math.BigDecimal.valueOf(table.rowsChanged());
        });
        Map<String, Object> variables = new HashMap<>();
        variables.put("financialDelta", diff.financialDelta());
        variables.put("stepCount", java.math.BigDecimal.valueOf(steps.size()));
        EvalContext context = new EvalContext(variables, functions);
        Decision decision = new Decision("ALLOW", "rules passed");
        for (PolicyRule rule : manifest.rules()) {
            Object value;
            try {
                value = Expressions.eval(rule.expression(), context);
            } catch (ExprException ex) {
                return new Decision("DENY", rule.id() + " failed closed: " + ex.getMessage());
            }
            if (!(value instanceof Boolean ok)) {
                return new Decision("DENY", rule.id() + " did not return a boolean");
            }
            if (!ok) {
                if ("DENY".equals(rule.onFail())) {
                    return new Decision("DENY", rule.id());
                }
                decision = new Decision("PAUSE", rule.id());
            }
        }
        boolean irreversible = false;
        for (ToolSpec step : steps) {
            if (step.reversibility() == Reversibility.IRREVERSIBLE) {
                irreversible = true;
                break;
            }
        }
        if (irreversible && decision.allow()) {
            return new Decision("PAUSE", "IRREVERSIBLE");
        }
        return decision;
    }

    private static Object fraction(DiffReport diff, List<Object> args) {
        requireOne(args);
        TableDiff table = diff.table(String.valueOf(args.get(0)));
        return table == null ? java.math.BigDecimal.ZERO : table.modifiedFraction();
    }

    private static Object changed(DiffReport diff, List<Object> args) {
        requireOne(args);
        TableDiff table = diff.table(String.valueOf(args.get(0)));
        return table != null && table.schemaChanged();
    }

    private static void requireOne(List<Object> args) {
        if (args.size() != 1) {
            throw new ExprException("expected one argument");
        }
    }

    public static String reasoningHash(String reasoning) {
        return Hashing.sha256(reasoning == null ? "" : reasoning);
    }
}
