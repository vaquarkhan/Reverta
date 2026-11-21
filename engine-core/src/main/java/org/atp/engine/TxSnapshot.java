// SPDX-License-Identifier: Apache-2.0
package org.atp.engine;

import org.atp.json.JsonCodec;
import org.atp.model.Types;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.TxState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mutable saga record. Persisted as canonical JSON before every external call. */
public final class TxSnapshot {
    public String txId;
    public String planHash;
    public String reasoningHash;
    public TxState state = TxState.INIT;
    public boolean explicitDeps;
    public final List<StepState> steps = new ArrayList<>();
    public final List<Integer> topo = new ArrayList<>();
    public Pending pending;
    public String errorCode;
    public String errorMessage;
    public boolean policyPassed;
    public String holdReason;
    public boolean orphan;
    public int shadowCursor;
    public final List<Integer> commitPlan = new ArrayList<>();
    public int commitCursor;
    public boolean commitReady;
    public final List<Integer> rollbackQueue = new ArrayList<>();
    public int rollbackCursor;
    public boolean rollbackReady;
    public DiffReport diff = DiffReport.empty();
    public long startedMillis;
    public long updatedMillis;
    public long startNanos;
    public long elapsedNanos;

    public TxState state() {
        return state;
    }

    public String txId() {
        return txId;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public boolean orphan() {
        return orphan;
    }

    public String holdReason() {
        return holdReason;
    }

    public long elapsedNanos() {
        return elapsedNanos;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("txId", txId);
        map.put("planHash", planHash);
        map.put("reasoningHash", reasoningHash);
        map.put("state", state.name());
        map.put("explicitDeps", explicitDeps);
        map.put("steps", steps.stream().map(StepState::toMap).toList());
        map.put("topo", new ArrayList<>(topo));
        map.put("pending", pending == null ? null : pending.toMap());
        map.put("errorCode", errorCode);
        map.put("errorMessage", errorMessage);
        map.put("policyPassed", policyPassed);
        map.put("holdReason", holdReason);
        map.put("orphan", orphan);
        map.put("shadowCursor", shadowCursor);
        map.put("commitPlan", new ArrayList<>(commitPlan));
        map.put("commitCursor", commitCursor);
        map.put("commitReady", commitReady);
        map.put("rollbackQueue", new ArrayList<>(rollbackQueue));
        map.put("rollbackCursor", rollbackCursor);
        map.put("rollbackReady", rollbackReady);
        map.put("diff", diff.toMap());
        map.put("startedMillis", startedMillis);
        map.put("updatedMillis", updatedMillis);
        map.put("startNanos", startNanos);
        map.put("elapsedNanos", elapsedNanos);
        return map;
    }

    public TxSnapshot copy() {
        return fromMap(JsonCodec.asMap(JsonCodec.parse(JsonCodec.canonical(toMap()))));
    }

    @SuppressWarnings("unchecked")
    public static TxSnapshot fromMap(Map<String, Object> map) {
        TxSnapshot snap = new TxSnapshot();
        snap.txId = String.valueOf(map.get("txId"));
        snap.planHash = String.valueOf(map.get("planHash"));
        snap.reasoningHash = String.valueOf(map.get("reasoningHash"));
        snap.state = TxState.valueOf(String.valueOf(map.get("state")));
        snap.explicitDeps = Boolean.TRUE.equals(map.get("explicitDeps"));
        for (Object step : (List<?>) map.get("steps")) {
            snap.steps.add(StepState.fromMap((Map<String, Object>) step));
        }
        snap.topo.addAll(ints(map.get("topo")));
        if (map.get("pending") instanceof Map<?, ?> pending) {
            snap.pending = Pending.fromMap((Map<String, Object>) pending);
        }
        snap.errorCode = map.get("errorCode") == null ? null : String.valueOf(map.get("errorCode"));
        snap.errorMessage = map.get("errorMessage") == null ? null : String.valueOf(map.get("errorMessage"));
        snap.policyPassed = Boolean.TRUE.equals(map.get("policyPassed"));
        snap.holdReason = map.get("holdReason") == null ? null : String.valueOf(map.get("holdReason"));
        snap.orphan = Boolean.TRUE.equals(map.get("orphan"));
        snap.shadowCursor = Types.asInt(map.get("shadowCursor"));
        snap.commitPlan.addAll(ints(map.get("commitPlan")));
        snap.commitCursor = Types.asInt(map.get("commitCursor"));
        snap.commitReady = Boolean.TRUE.equals(map.get("commitReady"));
        snap.rollbackQueue.addAll(ints(map.get("rollbackQueue")));
        snap.rollbackCursor = Types.asInt(map.get("rollbackCursor"));
        snap.rollbackReady = Boolean.TRUE.equals(map.get("rollbackReady"));
        snap.diff = DiffReport.fromMap(map.get("diff"));
        snap.startedMillis = Types.asLong(map.get("startedMillis"));
        snap.updatedMillis = Types.asLong(map.get("updatedMillis"));
        snap.startNanos = Types.asLong(map.get("startNanos"));
        snap.elapsedNanos = Types.asLong(map.get("elapsedNanos"));
        return snap;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> ints(Object raw) {
        List<Integer> values = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                values.add(Types.asInt(item));
            }
        }
        return values;
    }

    public static final class StepState {
        public int index;
        public String tool;
        public Map<String, Object> arguments = new LinkedHashMap<>();
        public Map<String, Object> outbound = new LinkedHashMap<>();
        public List<Integer> dependsOn = new ArrayList<>();
        public String idempotencyKey;
        public String businessKey;
        public String status = "PENDING";
        public Map<String, Object> output = new LinkedHashMap<>();
        public String pre;
        public String post;
        public String error;
        public int attempts;
        public int compAttempts;
        public boolean touched;

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("index", index);
            map.put("tool", tool);
            map.put("arguments", arguments);
            map.put("outbound", outbound);
            map.put("dependsOn", new ArrayList<>(dependsOn));
            map.put("idempotencyKey", idempotencyKey);
            map.put("businessKey", businessKey);
            map.put("status", status);
            map.put("output", output);
            map.put("pre", pre);
            map.put("post", post);
            map.put("error", error);
            map.put("attempts", attempts);
            map.put("compAttempts", compAttempts);
            map.put("touched", touched);
            return map;
        }

        @SuppressWarnings("unchecked")
        static StepState fromMap(Map<String, Object> map) {
            StepState step = new StepState();
            step.index = Types.asInt(map.get("index"));
            step.tool = String.valueOf(map.get("tool"));
            if (map.get("arguments") instanceof Map<?, ?> arguments) {
                step.arguments = new LinkedHashMap<>((Map<String, Object>) arguments);
            }
            if (map.get("outbound") instanceof Map<?, ?> outbound) {
                step.outbound = new LinkedHashMap<>((Map<String, Object>) outbound);
            }
            step.dependsOn = ints(map.get("dependsOn"));
            step.idempotencyKey = map.get("idempotencyKey") == null ? null : String.valueOf(map.get("idempotencyKey"));
            step.businessKey = map.get("businessKey") == null ? null : String.valueOf(map.get("businessKey"));
            step.status = String.valueOf(map.get("status"));
            if (map.get("output") instanceof Map<?, ?> output) {
                step.output = new LinkedHashMap<>((Map<String, Object>) output);
            }
            step.pre = map.get("pre") == null ? null : String.valueOf(map.get("pre"));
            step.post = map.get("post") == null ? null : String.valueOf(map.get("post"));
            step.error = map.get("error") == null ? null : String.valueOf(map.get("error"));
            step.attempts = Types.asInt(map.get("attempts"));
            step.compAttempts = Types.asInt(map.get("compAttempts"));
            step.touched = Boolean.TRUE.equals(map.get("touched"));
            return step;
        }
    }

    public static final class Pending {
        public String phase;
        public int stepIndex;
        public Map<String, Object> arguments = new LinkedHashMap<>();
        public String idempotencyKey;
        public String tool;
        public String action;

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("phase", phase);
            map.put("stepIndex", stepIndex);
            map.put("arguments", arguments);
            map.put("idempotencyKey", idempotencyKey);
            map.put("tool", tool);
            map.put("action", action);
            return map;
        }

        @SuppressWarnings("unchecked")
        static Pending fromMap(Map<String, Object> map) {
            Pending pending = new Pending();
            pending.phase = String.valueOf(map.get("phase"));
            pending.stepIndex = Types.asInt(map.get("stepIndex"));
            if (map.get("arguments") instanceof Map<?, ?> arguments) {
                pending.arguments = new LinkedHashMap<>((Map<String, Object>) arguments);
            }
            pending.idempotencyKey = String.valueOf(map.get("idempotencyKey"));
            pending.tool = String.valueOf(map.get("tool"));
            pending.action = map.get("action") == null ? null : String.valueOf(map.get("action"));
            return pending;
        }
    }
}
