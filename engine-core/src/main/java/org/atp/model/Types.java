// SPDX-License-Identifier: Apache-2.0
package org.atp.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Protocol enumerations and value objects for RFC-0001. */
public final class Types {
    private Types() {
    }

    public enum TxState {
        INIT,
        SHADOW_EXECUTING,
        POLICY_EVAL,
        COMMITTING,
        COMMITTED,
        UNKNOWN_RECONCILING,
        COMPENSATING,
        ROLLED_BACK,
        ESCALATED_HITL
    }

    public enum Reversibility {
        ATOMIC_BRANCHABLE,
        COMPENSATABLE,
        IRREVERSIBLE
    }

    public enum EffectStatus {
        OK, FAILURE, UNKNOWN
    }

    public enum Decision {
        APPROVE, REJECT, RETRY
    }

    public enum AtpErrorCode {
        INVALID(-31801, "AtpInvalidTransaction"),
        POLICY_DENIED(-31802, "AtpPolicyDenied"),
        UNKNOWN_OUTCOME(-31803, "AtpUnknownOutcome"),
        COMPENSATION_FAILED(-31804, "AtpCompensationFailed"),
        IRREVERSIBLE_BLOCKED(-31805, "AtpIrreversibleBlocked"),
        REPLAY_DIVERGENCE(-31806, "AtpReplayDivergence"),
        IDEMPOTENCY_CONFLICT(-31807, "AtpIdempotencyConflict"),
        APPROVAL_REQUIRED(-31808, "AtpApprovalRequired"),
        MANIFEST_REJECTED(-31809, "AtpManifestRejected"),
        ARGUMENT_INVALID(-31810, "AtpArgumentInvalid"),
        TOOL_UNREGISTERED(-31811, "AtpToolUnregistered"),
        LEDGER_TAMPERED(-31812, "AtpLedgerTampered");

        private final int code;
        private final String name;

        AtpErrorCode(int code, String name) {
            this.code = code;
            this.name = name;
        }

        public int code() {
            return code;
        }

        public String wireName() {
            return name;
        }
    }

    public static final class AtpException extends RuntimeException {
        private final AtpErrorCode code;

        public AtpException(AtpErrorCode code, String message) {
            super(message);
            this.code = code;
        }

        public AtpErrorCode code() {
            return code;
        }
    }

    public record StepRequest(String tool, Map<String, Object> arguments, List<Integer> dependsOn) {
        public StepRequest {
            if (tool == null || tool.isBlank()) {
                throw new AtpException(AtpErrorCode.INVALID, "tool name is required");
            }
            arguments = arguments == null ? new LinkedHashMap<>() : new LinkedHashMap<>(arguments);
            dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        }

        public StepRequest(String tool, Map<String, Object> arguments) {
            this(tool, arguments, List.of());
        }
    }

    public record CompensationSpec(
            String mode,
            String tool,
            Map<String, String> argumentMapping,
            int maxRetries,
            String onFailure) {
        public CompensationSpec {
            argumentMapping = argumentMapping == null ? Map.of() : Map.copyOf(argumentMapping);
            if (maxRetries < 1) {
                maxRetries = 1;
            }
            if (onFailure == null || onFailure.isBlank()) {
                onFailure = "ESCALATE_HITL";
            }
        }
    }

    public record PolicyRule(String id, String expression, String onFail) {
        public PolicyRule {
            if (id == null || expression == null || onFail == null) {
                throw new AtpException(AtpErrorCode.MANIFEST_REJECTED, "policy rule is incomplete");
            }
        }
    }

    public record ToolSpec(
            String name,
            String description,
            Map<String, Object> inputSchema,
            Reversibility reversibility,
            String adapter,
            String table,
            String blastRadiusTier,
            boolean dryRunSupported,
            List<String> moneyColumns,
            String idempotencyKeyPath,
            String reconciliationTool,
            CompensationSpec compensation,
            Map<String, Object> dryRunExample,
            int forwardRetries) {
        public ToolSpec {
            inputSchema = inputSchema == null ? Map.of() : Map.copyOf(inputSchema);
            moneyColumns = moneyColumns == null ? List.of() : List.copyOf(moneyColumns);
            dryRunExample = dryRunExample == null ? Map.of() : Map.copyOf(dryRunExample);
            if (forwardRetries < 1) {
                forwardRetries = 1;
            }
        }
    }

    public record TableDiff(
            String table,
            long rowsBefore,
            long rowsAfter,
            long rowsChanged,
            boolean schemaChanged,
            BigDecimal financialDelta) {
        public TableDiff {
            if (financialDelta == null) {
                financialDelta = BigDecimal.ZERO;
            }
        }

        public BigDecimal modifiedFraction() {
            long denom = Math.max(rowsBefore, 1);
            return BigDecimal.valueOf(rowsChanged).divide(BigDecimal.valueOf(denom), 8, java.math.RoundingMode.HALF_UP);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("table", table);
            m.put("rowsBefore", rowsBefore);
            m.put("rowsAfter", rowsAfter);
            m.put("rowsChanged", rowsChanged);
            m.put("schemaChanged", schemaChanged);
            m.put("financialDelta", financialDelta);
            m.put("modifiedFraction", modifiedFraction());
            return m;
        }

        @SuppressWarnings("unchecked")
        public static TableDiff fromMap(Map<String, Object> m) {
            return new TableDiff(
                    String.valueOf(m.get("table")),
                    asLong(m.get("rowsBefore")),
                    asLong(m.get("rowsAfter")),
                    asLong(m.get("rowsChanged")),
                    Boolean.TRUE.equals(m.get("schemaChanged")),
                    asDecimal(m.get("financialDelta")));
        }
    }

    public record DiffReport(List<TableDiff> tables) {
        public DiffReport {
            tables = tables == null ? List.of() : List.copyOf(tables);
        }

        public static DiffReport empty() {
            return new DiffReport(List.of());
        }

        public static DiffReport merge(DiffReport... parts) {
            List<TableDiff> all = new ArrayList<>();
            for (DiffReport part : parts) {
                if (part != null) {
                    all.addAll(part.tables());
                }
            }
            return new DiffReport(all);
        }

        public BigDecimal financialDelta() {
            BigDecimal sum = BigDecimal.ZERO;
            for (TableDiff table : tables) {
                sum = sum.add(table.financialDelta().abs());
            }
            return sum;
        }

        public TableDiff table(String name) {
            for (TableDiff table : tables) {
                if (table.table().equals(name)) {
                    return table;
                }
            }
            return null;
        }

        public Map<String, Object> toMap() {
            List<Object> rows = new ArrayList<>();
            for (TableDiff table : tables) {
                rows.add(table.toMap());
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tables", rows);
            m.put("financialDelta", financialDelta());
            return m;
        }

        @SuppressWarnings("unchecked")
        public static DiffReport fromMap(Object raw) {
            if (!(raw instanceof Map<?, ?> root)) {
                return empty();
            }
            Object tables = root.get("tables");
            if (!(tables instanceof List<?> list)) {
                return empty();
            }
            List<TableDiff> parsed = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    parsed.add(TableDiff.fromMap((Map<String, Object>) map));
                }
            }
            return new DiffReport(parsed);
        }
    }

    public static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new AtpException(AtpErrorCode.INVALID, "expected number");
    }

    public static int asInt(Object value) {
        return (int) asLong(value);
    }

    public static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        return new BigDecimal(String.valueOf(value));
    }
}
