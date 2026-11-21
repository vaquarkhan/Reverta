// SPDX-License-Identifier: Apache-2.0
package org.atp.engine;

import org.atp.crypto.Hashing;
import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.Runtime;
import org.atp.engine.TxSnapshot.Pending;
import org.atp.engine.TxSnapshot.StepState;
import org.atp.expr.Expressions;
import org.atp.expr.Expressions.EvalContext;
import org.atp.expr.Expressions.ExprException;
import org.atp.fsm.Transitions;
import org.atp.json.JsonCodec;
import org.atp.ledger.LedgerChain;
import org.atp.ledger.LedgerEntry;
import org.atp.ledger.Sanitizer;
import org.atp.log.DurableLog;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.Decision;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.Reversibility;
import org.atp.model.Types.StepRequest;
import org.atp.model.Types.ToolSpec;
import org.atp.model.Types.TxState;
import org.atp.otel.Telemetry.ApprovalSink;
import org.atp.otel.Telemetry.AsyncFanout;
import org.atp.otel.Telemetry.SpanEvent;
import org.atp.otel.Telemetry.SpanRecorder;
import org.atp.policy.Policy;
import org.atp.policy.Policy.SignedManifest;
import org.atp.schema.SchemaValidator;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Saga coordinator. The write-ahead snapshot is fsynced before any mutating call.
 * A crash leaves {@code pending} set, and recovery treats that call as unknown until reconcile.
 */
public final class TransactionEngine {
    private final DurableLog log;
    private final Clock clock;
    private final SignedManifest manifest;
    private final Runtime effects;
    private final AsyncFanout fanout;
    private final int maxDriveSteps;
    private final long lockWaitMillis;
    private final Map<String, TxSnapshot> transactions = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new HashMap<>();
    private final Map<String, Boolean> inflight = new HashMap<>();
    private final List<LedgerEntry> ledger = new CopyOnWriteArrayList<>();
    private final AtomicLong dropped = new AtomicLong();

    public TransactionEngine(DurableLog log, Clock clock, SignedManifest manifest, Runtime effects,
                             SpanRecorder spans, ApprovalSink approvals, Executor analytics,
                             int maxDriveSteps, long lockWaitMillis) {
        this.log = log;
        this.clock = clock;
        this.manifest = manifest;
        this.effects = effects;
        this.fanout = new AsyncFanout(spans, approvals, analytics);
        this.maxDriveSteps = maxDriveSteps;
        this.lockWaitMillis = lockWaitMillis;
        recover();
    }

    public static TransactionEngine create(DurableLog log, SignedManifest manifest, Runtime effects) {
        return new TransactionEngine(log, Clock.systemUTC(), manifest, effects, SpanRecorder.NONE, ApprovalSink.NONE,
                Runnable::run, 10_000, 30_000);
    }

    public TxSnapshot execute(String txId, List<StepRequest> steps, String reasoning) {
        validateId(txId);
        String planHash = planHash(steps);
        Object lock = lock(txId);
        synchronized (lock) {
            awaitIdle(lock, txId);
            TxSnapshot existing = transactions.get(txId);
            if (existing != null) {
                if (!existing.planHash.equals(planHash)) {
                    throw new AtpException(AtpErrorCode.IDEMPOTENCY_CONFLICT, "different plan for " + txId);
                }
                if (Transitions.terminal(existing.state) || existing.state == TxState.ESCALATED_HITL) {
                    return existing.copy();
                }
            } else {
                transactions.put(txId, create(txId, steps, planHash, reasoning));
            }
            inflight.put(txId, true);
        }
        try {
            drive(txId);
        } finally {
            synchronized (lock) {
                inflight.put(txId, false);
                lock.notifyAll();
            }
        }
        return transactions.get(txId).copy();
    }

    public TxSnapshot resume(String txId, Decision decision) {
        validateId(txId);
        Object lock = lock(txId);
        synchronized (lock) {
            awaitIdle(lock, txId);
            TxSnapshot snap = must(txId);
            if (snap.state != TxState.ESCALATED_HITL) {
                throw new AtpException(AtpErrorCode.INVALID, "transaction is not waiting");
            }
            steer(snap, decision);
            persist(snap, "resume", "", Map.of("decision", decision.name()), null, null, "NOT_APPLICABLE");
            inflight.put(txId, true);
        }
        try {
            drive(txId);
        } finally {
            synchronized (lock) {
                inflight.put(txId, false);
                lock.notifyAll();
            }
        }
        return transactions.get(txId).copy();
    }

    public TxSnapshot abort(String txId) {
        validateId(txId);
        Object lock = lock(txId);
        synchronized (lock) {
            awaitIdle(lock, txId);
            TxSnapshot snap = must(txId);
            if (Transitions.terminal(snap.state)) {
                return snap.copy();
            }
            if (snap.state == TxState.UNKNOWN_RECONCILING || "UNKNOWN".equals(snap.holdReason) || "ORPHAN".equals(snap.holdReason)) {
                throw new AtpException(AtpErrorCode.UNKNOWN_OUTCOME, "refusing to abort an unknown or orphaned transaction");
            }
            if (snap.state == TxState.ESCALATED_HITL && snap.holdReason != null && snap.holdReason.startsWith("APPROVAL")) {
                steer(snap, Decision.REJECT);
            } else if (snap.state != TxState.COMPENSATING) {
                snap.rollbackReady = false;
                move(snap, TxState.COMPENSATING);
            }
            inflight.put(txId, true);
        }
        try {
            drive(txId);
        } finally {
            synchronized (lock) {
                inflight.put(txId, false);
                lock.notifyAll();
            }
        }
        return transactions.get(txId).copy();
    }

    public TxSnapshot status(String txId) {
        validateId(txId);
        synchronized (lock(txId)) {
            return must(txId).copy();
        }
    }

    public List<LedgerEntry> ledger() {
        return List.copyOf(ledger);
    }

    public long droppedSignals() {
        return dropped.get() + fanout.dropped();
    }

    public synchronized void recover() {
        DurableLog.Loaded loaded = log.load();
        List<Map<String, Object>> snapshots = new ArrayList<>(loaded.snapshots());
        List<LedgerEntry> entries = new ArrayList<>(loaded.ledger());
        if (snapshots.size() == entries.size() + 1) {
            snapshots.remove(snapshots.size() - 1);
        } else if (snapshots.size() != entries.size()) {
            throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "snapshot and ledger lengths differ");
        }
        LedgerChain.AnchorStatus anchor = LedgerChain.check(entries, loaded.anchor());
        if (anchor == LedgerChain.AnchorStatus.HEALED && !entries.isEmpty()) {
            log.anchor(entries.get(entries.size() - 1).entryHash);
        }
        ledger.clear();
        ledger.addAll(entries);
        transactions.clear();
        Map<String, TxSnapshot> latest = new LinkedHashMap<>();
        for (Map<String, Object> raw : snapshots) {
            TxSnapshot snap = TxSnapshot.fromMap(raw);
            if (snap.pending != null && !Transitions.terminal(snap.state) && snap.state != TxState.ESCALATED_HITL
                    && snap.state != TxState.UNKNOWN_RECONCILING) {
                move(snap, TxState.UNKNOWN_RECONCILING);
            }
            latest.put(snap.txId, snap);
        }
        transactions.putAll(latest);
        for (TxSnapshot snap : List.copyOf(latest.values())) {
            if (Transitions.terminal(snap.state)) {
                continue;
            }
            if (snap.state == TxState.ESCALATED_HITL) {
                fanout.approval(frozen(snap));
                continue;
            }
            drive(snap.txId);
        }
    }

    private void drive(String txId) {
        int steps = 0;
        while (true) {
            if (++steps > maxDriveSteps) {
                throw new AtpException(AtpErrorCode.INVALID, "drive limit exceeded for " + txId);
            }
            Action action;
            synchronized (lock(txId)) {
                TxSnapshot snap = transactions.get(txId);
                action = plan(snap);
                if (action.kind == Kind.STOP) {
                    return;
                }
                if (action.kind == Kind.LOCAL) {
                    action.local.run();
                    persist(snap, action.stepId, action.tool, action.input, action.pre, action.post, action.compensation);
                    if (Transitions.terminal(snap.state)) {
                        cleanup(snap);
                    }
                    if (snap.state == TxState.ESCALATED_HITL) {
                        fanout.approval(frozen(snap));
                    }
                    continue;
                }
                if (action.recordIntent) {
                    snap.pending = action.pending;
                    persist(snap, action.stepId, action.tool, action.input, null, null, "PENDING");
                }
            }
            Outcome outcome;
            try {
                outcome = action.call.get();
            } catch (RuntimeException ex) {
                if ("DIFF".equals(action.phase)) {
                    outcome = new Outcome(EffectResult.fail(ex.getMessage(), false), DiffReport.empty());
                } else {
                    outcome = new Outcome(EffectResult.unknown(ex.getMessage()), null);
                }
            }
            synchronized (lock(txId)) {
                TxSnapshot snap = transactions.get(txId);
                apply(snap, action, outcome);
                persist(snap, action.stepId, action.tool, action.input, action.pre, action.post, action.compensation);
                if (snap.state == TxState.ESCALATED_HITL) {
                    fanout.approval(frozen(snap));
                }
            }
        }
    }

    private Action plan(TxSnapshot snap) {
        return switch (snap.state) {
            case INIT -> local(snap, "open", () -> open(snap));
            case SHADOW_EXECUTING -> planShadow(snap);
            case POLICY_EVAL -> planPolicy(snap);
            case COMMITTING -> planCommit(snap);
            case UNKNOWN_RECONCILING -> planReconcile(snap);
            case COMPENSATING -> planCompensate(snap);
            case ESCALATED_HITL, COMMITTED, ROLLED_BACK -> Action.stop();
        };
    }

    private void open(TxSnapshot snap) {
        try {
            snap.topo.addAll(order(snap, false));
            move(snap, TxState.SHADOW_EXECUTING);
        } catch (AtpException ex) {
            snap.errorCode = ex.code().wireName();
            snap.errorMessage = ex.getMessage();
            move(snap, TxState.ROLLED_BACK);
        }
    }

    private Action planShadow(TxSnapshot snap) {
        if (snap.shadowCursor >= snap.topo.size()) {
            return local(snap, "policy", () -> move(snap, TxState.POLICY_EVAL));
        }
        StepState step = snap.steps.get(snap.topo.get(snap.shadowCursor));
        ToolSpec spec = manifest.tool(step.tool);
        if (spec == null) {
            return local(snap, "step-" + step.index, () -> fail(snap, step, AtpErrorCode.TOOL_UNREGISTERED, "tool is not in the signed manifest"));
        }
        List<String> errors = SchemaValidator.validate(spec.inputSchema(), step.arguments);
        if (!errors.isEmpty()) {
            return local(snap, "step-" + step.index, () -> fail(snap, step, AtpErrorCode.ARGUMENT_INVALID, String.join("; ", errors)));
        }
        ensureKey(snap, step, spec);
        if (step.attempts == 0) {
            step.attempts = 1;
        }
        Dispatch dispatch = dispatch(snap, step, step.outbound, step.idempotencyKey, "SHADOW", null);
        return external(snap, step, "SHADOW", "step-" + step.index + "-shadow", dispatch, true, () ->
                new Outcome(effects.shadow(dispatch), null));
    }

    private Action planPolicy(TxSnapshot snap) {
        return new Action(Kind.EXTERNAL, "policy", "policy", Map.of(), null, null, "NOT_APPLICABLE", "DIFF",
                null, false, null, () -> {
            DiffReport diff = effects.diff(snap.txId);
            return new Outcome(EffectResult.ok(Map.of("ok", true), null, null), diff);
        });
    }

    private Action planCommit(TxSnapshot snap) {
        if (!snap.commitReady) {
            return local(snap, "commit-plan", () -> {
                snap.commitPlan.addAll(order(snap, true));
                snap.commitReady = true;
            });
        }
        if (snap.commitCursor >= snap.commitPlan.size()) {
            return local(snap, "seal", () -> move(snap, TxState.COMMITTED));
        }
        StepState step = snap.steps.get(snap.commitPlan.get(snap.commitCursor));
        Dispatch dispatch = dispatch(snap, step, step.outbound, step.idempotencyKey, "LIVE", null);
        return external(snap, step, "LIVE", "step-" + step.index + "-live", dispatch, true, () ->
                new Outcome(effects.live(dispatch), null));
    }

    private Action planReconcile(TxSnapshot snap) {
        if (snap.pending == null) {
            return local(snap, "reconcile", () -> hold(snap, "UNKNOWN", AtpErrorCode.UNKNOWN_OUTCOME, "missing intent"));
        }
        StepState step = snap.steps.get(snap.pending.stepIndex);
        ToolSpec spec = manifest.tool(step.tool);
        Dispatch dispatch = new Dispatch(snap.txId, step.index, snap.pending.tool, snap.pending.arguments,
                snap.pending.idempotencyKey, spec, snap.pending.action, snap.pending.phase);
        return external(snap, step, "RECONCILE", "step-" + step.index + "-reconcile", dispatch, false, () ->
                new Outcome(effects.reconcile(dispatch), null));
    }

    private Action planCompensate(TxSnapshot snap) {
        if (!snap.rollbackReady) {
            return local(snap, "rollback-plan", () -> prepareRollback(snap));
        }
        if (snap.rollbackCursor >= snap.rollbackQueue.size()) {
            return local(snap, "seal", () -> {
                if (snap.orphan) {
                    hold(snap, "ORPHAN", AtpErrorCode.IRREVERSIBLE_BLOCKED, "irreversible effect remains");
                } else {
                    move(snap, TxState.ROLLED_BACK);
                }
            });
        }
        StepState step = snap.steps.get(snap.rollbackQueue.get(snap.rollbackCursor));
        ToolSpec spec = manifest.tool(step.tool);
        if ("LIVE".equals(step.status) && spec.reversibility() == Reversibility.IRREVERSIBLE) {
            return local(snap, "compensate-" + step.index, () -> {
                step.status = "ORPHAN";
                snap.orphan = true;
                snap.rollbackCursor++;
            });
        }
        if (!"LIVE".equals(step.status)) {
            Dispatch dispatch = dispatch(snap, step, step.outbound, step.idempotencyKey, "COMPENSATE", "DISCARD");
            return external(snap, step, "COMPENSATE", "compensate-" + step.index, dispatch, true, () ->
                    new Outcome(effects.compensate(dispatch), null));
        }
        Map<String, Object> mapped;
        try {
            mapped = mapCompensation(snap, step, spec);
        } catch (ExprException ex) {
            return local(snap, "compensate-" + step.index, () ->
                    hold(snap, "COMPENSATION_FAILED", AtpErrorCode.COMPENSATION_FAILED, ex.getMessage()));
        }
        String key = "comp:" + step.idempotencyKey + ":" + Math.max(step.compAttempts, 1);
        mapped.put("idempotency_key", key);
        if (step.compAttempts == 0) {
            step.compAttempts = 1;
        }
        Dispatch dispatch = new Dispatch(snap.txId, step.index, spec.compensation().tool(), mapped, key, spec, "INVERSE", "COMPENSATE");
        return external(snap, step, "COMPENSATE", "compensate-" + step.index, dispatch, true, () ->
                new Outcome(effects.compensate(dispatch), null));
    }

    private void apply(TxSnapshot snap, Action action, Outcome outcome) {
        EffectResult result = outcome.result;
        if ("DIFF".equals(action.phase)) {
            applyPolicy(snap, outcome);
            return;
        }
        if ("RECONCILE".equals(action.phase)) {
            applyReconcile(snap, result);
            return;
        }
        StepState step = snap.steps.get(action.pending.stepIndex);
        if (result.status() == org.atp.model.Types.EffectStatus.UNKNOWN) {
            move(snap, TxState.UNKNOWN_RECONCILING);
            action.compensation = "PENDING";
            return;
        }
        if (result.status() == org.atp.model.Types.EffectStatus.FAILURE) {
            applyFailure(snap, step, action, result);
            return;
        }
        succeed(snap, step, action, result);
    }

    private void applyPolicy(TxSnapshot snap, Outcome outcome) {
        if (outcome.result.status() != org.atp.model.Types.EffectStatus.OK) {
            deny(snap, outcome.result.error());
            return;
        }
        snap.diff = outcome.diff == null ? DiffReport.empty() : outcome.diff;
        List<ToolSpec> specs = new ArrayList<>();
        for (StepState step : snap.steps) {
            specs.add(manifest.tool(step.tool));
        }
        Policy.Decision decision = Policy.evaluate(manifest, snap.diff, specs);
        if (decision.deny()) {
            deny(snap, decision.reason());
            return;
        }
        if (!decision.allow()) {
            String reason = "IRREVERSIBLE".equals(decision.reason()) ? "APPROVAL_IRREVERSIBLE" : "APPROVAL_POLICY";
            hold(snap, reason, AtpErrorCode.APPROVAL_REQUIRED, decision.reason());
            return;
        }
        snap.policyPassed = true;
        move(snap, TxState.COMMITTING);
    }

    private void applyFailure(TxSnapshot snap, StepState step, Action action, EffectResult result) {
        ToolSpec spec = manifest.tool(step.tool);
        if ("COMPENSATE".equals(action.phase)) {
            int limit = spec.compensation().maxRetries();
            if (result.retryable() && step.compAttempts < limit) {
                step.compAttempts++;
                snap.pending = null;
                action.compensation = "PENDING";
                return;
            }
            hold(snap, "COMPENSATION_FAILED", AtpErrorCode.COMPENSATION_FAILED, result.error());
            return;
        }
        if (result.retryable() && step.attempts < spec.forwardRetries()) {
            step.attempts++;
            snap.pending = null;
            action.compensation = "PENDING";
            return;
        }
        fail(snap, step, AtpErrorCode.INVALID, result.error() == null ? "step failed" : result.error());
    }

    private void succeed(TxSnapshot snap, StepState step, Action action, EffectResult result) {
        snap.pending = null;
        if (snap.state == TxState.UNKNOWN_RECONCILING) {
            move(snap, switch (action.phase) {
                case "LIVE" -> TxState.COMMITTING;
                case "COMPENSATE" -> TxState.COMPENSATING;
                default -> TxState.SHADOW_EXECUTING;
            });
        }
        if ("SHADOW".equals(action.phase)) {
            step.status = "SHADOWED";
            step.touched = true;
            step.output = new LinkedHashMap<>(result.output());
            step.pre = result.preState();
            step.post = result.postState();
            snap.shadowCursor++;
            action.pre = step.pre;
            action.post = step.post;
            action.compensation = "NOT_APPLICABLE";
        } else if ("LIVE".equals(action.phase)) {
            step.status = "LIVE";
            step.touched = true;
            step.output = new LinkedHashMap<>(result.output());
            if (result.preState() != null) {
                step.pre = result.preState();
            }
            step.post = result.postState();
            snap.commitCursor++;
            action.pre = step.pre;
            action.post = step.post;
            action.compensation = "NOT_APPLICABLE";
        } else {
            step.status = "INVERSE".equals(action.pending.action) ? "COMPENSATED" : "DISCARDED";
            snap.rollbackCursor++;
            action.compensation = "COMPENSATED".equals(step.status) ? "SUCCEEDED" : "DISCARDED";
        }
    }

    private void applyReconcile(TxSnapshot snap, EffectResult result) {
        Pending pending = snap.pending;
        StepState step = snap.steps.get(pending.stepIndex);
        if (result.status() != org.atp.model.Types.EffectStatus.OK) {
            hold(snap, "UNKNOWN", AtpErrorCode.UNKNOWN_OUTCOME, result.error() == null ? "reconcile failed" : result.error());
            return;
        }
        if (result.found()) {
            ToolSpec spec = manifest.tool(step.tool);
            Dispatch dispatch = new Dispatch(snap.txId, step.index, pending.tool, pending.arguments,
                    pending.idempotencyKey, spec, pending.action, pending.phase);
            Action synthetic = external(snap, step, pending.phase, "step-" + step.index, dispatch, false, null);
            succeed(snap, step, synthetic, result);
            return;
        }
        ToolSpec spec = manifest.tool(step.tool);
        int limit = "COMPENSATE".equals(pending.phase) ? spec.compensation().maxRetries() : spec.forwardRetries();
        int used = "COMPENSATE".equals(pending.phase) ? step.compAttempts : step.attempts;
        if (used < limit) {
            if ("COMPENSATE".equals(pending.phase)) {
                step.compAttempts++;
            } else {
                step.attempts++;
            }
            snap.pending = null;
            move(snap, switch (pending.phase) {
                case "LIVE" -> TxState.COMMITTING;
                case "COMPENSATE" -> TxState.COMPENSATING;
                default -> TxState.SHADOW_EXECUTING;
            });
            return;
        }
        hold(snap, "UNKNOWN", AtpErrorCode.UNKNOWN_OUTCOME, "effect was not found after reconcile");
    }

    private void steer(TxSnapshot snap, Decision decision) {
        String hold = snap.holdReason == null ? "" : snap.holdReason;
        switch (decision) {
            case APPROVE -> {
                if (!hold.startsWith("APPROVAL")) {
                    throw new AtpException(AtpErrorCode.INVALID, "nothing to approve");
                }
                snap.holdReason = null;
                snap.policyPassed = true;
                snap.errorCode = null;
                snap.errorMessage = null;
                move(snap, TxState.COMMITTING);
            }
            case REJECT -> {
                if (!hold.startsWith("APPROVAL")) {
                    throw new AtpException(AtpErrorCode.INVALID, "reject is only valid for approval holds");
                }
                snap.holdReason = null;
                snap.rollbackReady = false;
                move(snap, TxState.COMPENSATING);
            }
            case RETRY -> {
                if ("UNKNOWN".equals(hold)) {
                    snap.holdReason = null;
                    snap.pending = snap.pending == null ? snap.pending : snap.pending;
                    move(snap, TxState.UNKNOWN_RECONCILING);
                } else if ("COMPENSATION_FAILED".equals(hold)) {
                    snap.holdReason = null;
                    move(snap, TxState.COMPENSATING);
                } else {
                    throw new AtpException(AtpErrorCode.INVALID, "retry is not valid for " + hold);
                }
            }
        }
    }

    private void prepareRollback(TxSnapshot snap) {
        snap.rollbackQueue.clear();
        List<Integer> reversed = new ArrayList<>(snap.topo);
        java.util.Collections.reverse(reversed);
        for (int index : reversed) {
            StepState step = snap.steps.get(index);
            if (step.touched && !"COMPENSATED".equals(step.status) && !"DISCARDED".equals(step.status) && !"ORPHAN".equals(step.status)) {
                snap.rollbackQueue.add(index);
            }
        }
        snap.rollbackCursor = 0;
        snap.rollbackReady = true;
    }

    private Map<String, Object> mapCompensation(TxSnapshot snap, StepState step, ToolSpec spec) {
        Map<String, Object> variables = new HashMap<>();
        variables.put("input", step.arguments);
        variables.put("output", step.output);
        variables.put("tx", Map.of("id", snap.txId));
        EvalContext context = new EvalContext(variables, Expressions.baseFunctions());
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : spec.compensation().argumentMapping().entrySet()) {
            mapped.put(entry.getKey(), Expressions.eval(entry.getValue(), context));
        }
        return mapped;
    }

    private void fail(TxSnapshot snap, StepState step, AtpErrorCode code, String message) {
        step.status = "FAILED";
        step.error = message;
        snap.errorCode = code.wireName();
        snap.errorMessage = message;
        snap.pending = null;
        snap.rollbackReady = false;
        move(snap, TxState.COMPENSATING);
    }

    private void deny(TxSnapshot snap, String reason) {
        snap.errorCode = AtpErrorCode.POLICY_DENIED.wireName();
        snap.errorMessage = reason;
        snap.pending = null;
        snap.rollbackReady = false;
        move(snap, TxState.COMPENSATING);
    }

    private void hold(TxSnapshot snap, String reason, AtpErrorCode code, String message) {
        snap.holdReason = reason;
        snap.errorCode = code.wireName();
        snap.errorMessage = message;
        snap.pending = "UNKNOWN".equals(reason) ? snap.pending : null;
        move(snap, TxState.ESCALATED_HITL);
    }

    private void move(TxSnapshot snap, TxState to) {
        Transitions.require(snap.state, to);
        snap.state = to;
        if (Transitions.terminal(to)) {
            snap.elapsedNanos = Math.max(0, System.nanoTime() - snap.startNanos);
        }
    }

    private void cleanup(TxSnapshot snap) {
        try {
            effects.afterTerminal(snap.txId, snap.state == TxState.COMMITTED);
        } catch (RuntimeException ex) {
            dropped.incrementAndGet();
        }
    }

    private void persist(TxSnapshot snap, String stepId, String tool, Object input, String pre, String post, String compensation) {
        snap.updatedMillis = clock.millis();
        String prev = ledger.isEmpty() ? Hashing.genesis() : ledger.get(ledger.size() - 1).entryHash;
        LedgerEntry entry = LedgerEntry.seal(ledger.size() + 1L, snap.txId, stepId, prev, snap.reasoningHash,
                tool == null ? "" : tool, Sanitizer.sanitize(input), pre, post,
                compensation == null ? "NOT_APPLICABLE" : compensation, snap.state.name());
        log.persist(snap.toMap(), entry);
        ledger.add(entry);
        boolean compensationTriggered = snap.state == TxState.COMPENSATING || "SUCCEEDED".equals(compensation) || "DISCARDED".equals(compensation);
        fanout.span(new SpanEvent("atp.step", Map.of(
                "atp.tx_id", snap.txId,
                "atp.compensation_triggered", Boolean.toString(compensationTriggered),
                "gen_ai.tool.name", tool == null ? "" : tool,
                "gen_ai.operation.name", "execute_tool",
                "atp.state", snap.state.name())));
    }

    private TxSnapshot create(String txId, List<StepRequest> steps, String planHash, String reasoning) {
        TxSnapshot snap = new TxSnapshot();
        snap.txId = txId;
        snap.planHash = planHash;
        snap.reasoningHash = Policy.reasoningHash(reasoning);
        snap.explicitDeps = steps.stream().anyMatch(step -> !step.dependsOn().isEmpty());
        snap.startedMillis = clock.millis();
        snap.updatedMillis = snap.startedMillis;
        snap.startNanos = System.nanoTime();
        for (int i = 0; i < steps.size(); i++) {
            StepRequest request = steps.get(i);
            StepState step = new StepState();
            step.index = i;
            step.tool = request.tool();
            step.arguments = new LinkedHashMap<>(request.arguments());
            step.dependsOn = new ArrayList<>(request.dependsOn());
            snap.steps.add(step);
        }
        return snap;
    }

    private void ensureKey(TxSnapshot snap, StepState step, ToolSpec spec) {
        if (step.idempotencyKey != null) {
            return;
        }
        String hash = Hashing.sha256(JsonCodec.canonical(step.arguments)).substring(0, 32);
        step.idempotencyKey = snap.txId + ":" + step.index + ":" + hash;
        step.outbound = new LinkedHashMap<>(step.arguments);
        step.outbound.put("idempotency_key", step.idempotencyKey);
        step.businessKey = businessKey(spec.idempotencyKeyPath(), step.arguments);
    }

    private static String businessKey(String path, Map<String, Object> arguments) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String field = path;
        if (field.startsWith("$.")) {
            field = field.substring(2);
        }
        if (field.startsWith("input.")) {
            field = field.substring("input.".length());
        }
        if (field.contains(".")) {
            return null;
        }
        Object value = arguments.get(field);
        return value == null ? null : String.valueOf(value);
    }

    private List<Integer> order(TxSnapshot snap, boolean deferIrreversible) {
        Map<Integer, List<Integer>> deps = new HashMap<>();
        for (StepState step : snap.steps) {
            deps.put(step.index, new ArrayList<>(step.dependsOn));
        }
        if (!snap.explicitDeps) {
            for (StepState step : snap.steps) {
                if (step.index > 0) {
                    deps.get(step.index).add(step.index - 1);
                }
            }
        }
        List<Integer> remaining = new ArrayList<>();
        for (StepState step : snap.steps) {
            remaining.add(step.index);
        }
        List<Integer> done = new ArrayList<>();
        List<Integer> result = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<Integer> ready = new ArrayList<>();
            for (int index : remaining) {
                if (done.containsAll(deps.get(index))) {
                    ready.add(index);
                }
            }
            if (ready.isEmpty()) {
                throw new AtpException(AtpErrorCode.INVALID, "dependency cycle");
            }
            List<Integer> pool = ready;
            if (deferIrreversible) {
                List<Integer> normal = new ArrayList<>();
                for (int index : ready) {
                    ToolSpec spec = manifest.tool(snap.steps.get(index).tool);
                    if (spec.reversibility() != Reversibility.IRREVERSIBLE) {
                        normal.add(index);
                    }
                }
                if (!normal.isEmpty()) {
                    pool = normal;
                }
            }
            int pick = pool.stream().min(Comparator.naturalOrder()).orElseThrow();
            result.add(pick);
            done.add(pick);
            remaining.remove(Integer.valueOf(pick));
        }
        return result;
    }

    private static String planHash(List<StepRequest> steps) {
        List<Map<String, Object>> view = new ArrayList<>();
        for (StepRequest step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", step.tool());
            item.put("arguments", step.arguments());
            item.put("dependsOn", step.dependsOn());
            view.add(item);
        }
        return Hashing.sha256(JsonCodec.canonical(view));
    }

    private Dispatch dispatch(TxSnapshot snap, StepState step, Map<String, Object> args, String key, String phase, String action) {
        return new Dispatch(snap.txId, step.index, step.tool, args, key, manifest.tool(step.tool), action, phase);
    }

    private void awaitIdle(Object lock, String txId) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(lockWaitMillis);
        while (Boolean.TRUE.equals(inflight.get(txId))) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new AtpException(AtpErrorCode.INVALID, "timed out waiting for " + txId);
            }
            try {
                lock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AtpException(AtpErrorCode.INVALID, "interrupted");
            }
        }
    }

    private Object lock(String txId) {
        synchronized (locks) {
            return locks.computeIfAbsent(txId, id -> new Object());
        }
    }

    private TxSnapshot must(String txId) {
        TxSnapshot snap = transactions.get(txId);
        if (snap == null) {
            throw new AtpException(AtpErrorCode.INVALID, "unknown transaction " + txId);
        }
        return snap;
    }

    private static void validateId(String txId) {
        if (txId == null || !txId.matches("[A-Za-z0-9_.:\\-]{1,80}")) {
            throw new AtpException(AtpErrorCode.INVALID, "txId must be 1-80 characters of letters, digits, or . _ : -");
        }
    }

    private static Map<String, Object> frozen(TxSnapshot snap) {
        return JsonCodec.asMap(JsonCodec.parse(JsonCodec.canonical(snap.toMap())));
    }

    private Action local(TxSnapshot snap, String stepId, Runnable runnable) {
        return new Action(Kind.LOCAL, stepId, "", Map.of(), null, null, "NOT_APPLICABLE", null, null, false, runnable, null);
    }

    private Action external(TxSnapshot snap, StepState step, String phase, String stepId, Dispatch dispatch,
                            boolean recordIntent, java.util.function.Supplier<Outcome> call) {
        Pending pending = new Pending();
        pending.phase = phase;
        pending.stepIndex = step.index;
        pending.arguments = dispatch == null ? new LinkedHashMap<>() : new LinkedHashMap<>(dispatch.arguments());
        pending.idempotencyKey = dispatch == null ? null : dispatch.idempotencyKey();
        pending.tool = dispatch == null ? step.tool : dispatch.tool();
        pending.action = dispatch == null ? null : dispatch.action();
        return new Action(Kind.EXTERNAL, stepId, step.tool, pending.arguments, null, null, "NOT_APPLICABLE",
                phase, pending, recordIntent, null, call);
    }

    private enum Kind {LOCAL, EXTERNAL, STOP}

    private record Outcome(EffectResult result, DiffReport diff) {
    }

    private static final class Action {
        private final Kind kind;
        private final String stepId;
        private final String tool;
        private final Object input;
        private String pre;
        private String post;
        private String compensation;
        private final String phase;
        private final Pending pending;
        private final boolean recordIntent;
        private final Runnable local;
        private final java.util.function.Supplier<Outcome> call;

        private Action(Kind kind, String stepId, String tool, Object input, String pre, String post, String compensation,
                       String phase, Pending pending, boolean recordIntent, Runnable local,
                       java.util.function.Supplier<Outcome> call) {
            this.kind = kind;
            this.stepId = stepId;
            this.tool = tool;
            this.input = input;
            this.pre = pre;
            this.post = post;
            this.compensation = compensation;
            this.phase = phase;
            this.pending = pending;
            this.recordIntent = recordIntent;
            this.local = local;
            this.call = call;
        }

        private static Action stop() {
            return new Action(Kind.STOP, "stop", "", Map.of(), null, null, "NOT_APPLICABLE", null, null, false, null, null);
        }
    }
}
