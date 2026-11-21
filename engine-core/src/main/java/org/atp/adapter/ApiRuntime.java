// SPDX-License-Identifier: Apache-2.0
package org.atp.adapter;

import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.model.Types;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.TableDiff;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Dry-run virtualizer plus idempotent live side effects for tools that cannot branch. */
public final class ApiRuntime implements PhasePort {
    private final Map<String, Function<Map<String, Object>, EffectResult>> handlers = new ConcurrentHashMap<>();
    private final Map<String, EffectResult> applied = new ConcurrentHashMap<>();
    private final Map<String, TableDiff> diffs = new ConcurrentHashMap<>();

    public void register(String tool, Function<Map<String, Object>, EffectResult> handler) {
        handlers.put(tool, handler);
    }

    public int applications(String tool) {
        int count = 0;
        for (String key : applied.keySet()) {
            if (key.startsWith("LIVE:" + tool + ":")) {
                count++;
            }
        }
        return count;
    }

    public boolean seen(String tool, String idempotencyKey) {
        return applied.containsKey("LIVE:" + tool + ":" + idempotencyKey);
    }

    @Override
    public EffectResult shadow(Dispatch dispatch) {
        if (!dispatch.spec().dryRunSupported()) {
            return EffectResult.ok(Map.of("dryRun", false, "unverified", true, "found", false), "api:none", "api:unverified");
        }
        Map<String, Object> example = new java.util.LinkedHashMap<>(dispatch.spec().dryRunExample());
        example.put("found", false);
        example.put("dryRun", true);
        rememberDiff(dispatch);
        return EffectResult.ok(example, "api:none", "api:dry-run");
    }

    @Override
    public EffectResult live(Dispatch dispatch) {
        String id = "LIVE:" + dispatch.tool() + ":" + dispatch.idempotencyKey();
        EffectResult prior = applied.get(id);
        if (prior != null) {
            return prior;
        }
        Function<Map<String, Object>, EffectResult> handler = handlers.get(dispatch.tool());
        if (handler == null) {
            return EffectResult.fail("no handler for " + dispatch.tool(), false);
        }
        EffectResult result = handler.apply(dispatch.arguments());
        if (result.status() == org.atp.model.Types.EffectStatus.OK) {
            applied.put(id, result);
        }
        return result;
    }

    @Override
    public EffectResult compensate(Dispatch dispatch) {
        if (!"INVERSE".equals(dispatch.action())) {
            return EffectResult.ok(Map.of("discarded", true), "api:dry-run", "api:none");
        }
        String tool = dispatch.spec().compensation().tool();
        Function<Map<String, Object>, EffectResult> handler = handlers.get(tool);
        if (handler == null) {
            return EffectResult.fail("no compensation handler for " + tool, false);
        }
        String id = "LIVE:" + tool + ":" + dispatch.idempotencyKey();
        EffectResult prior = applied.get(id);
        if (prior != null) {
            return prior;
        }
        EffectResult result = handler.apply(dispatch.arguments());
        if (result.status() == org.atp.model.Types.EffectStatus.OK) {
            applied.put(id, result);
        }
        return result;
    }

    @Override
    public EffectResult reconcile(Dispatch dispatch) {
        if (dispatch.spec().reconciliationTool() != null) {
            Function<Map<String, Object>, EffectResult> handler = handlers.get(dispatch.spec().reconciliationTool());
            if (handler == null) {
                return EffectResult.unknown("reconciliation tool missing");
            }
            return handler.apply(Map.of("idempotency_key", dispatch.idempotencyKey(), "tool", dispatch.tool()));
        }
        EffectResult prior = applied.get("LIVE:" + dispatch.tool() + ":" + dispatch.idempotencyKey());
        if (prior == null) {
            return EffectResult.ok(Map.of("found", false), null, null);
        }
        Map<String, Object> output = new java.util.LinkedHashMap<>(prior.output());
        output.put("found", true);
        return EffectResult.ok(output, prior.preState(), prior.postState());
    }

    @Override
    public DiffReport diff(String txId) {
        List<TableDiff> tables = new ArrayList<>();
        String prefix = txId + ":";
        for (Map.Entry<String, TableDiff> entry : diffs.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                tables.add(entry.getValue());
            }
        }
        return new DiffReport(tables);
    }

    private void rememberDiff(Dispatch dispatch) {
        BigDecimal money = BigDecimal.ZERO;
        for (String column : dispatch.spec().moneyColumns()) {
            Object value = dispatch.arguments().get(column);
            if (value instanceof Number) {
                money = money.add(Types.asDecimal(value).abs());
            }
        }
        if (money.signum() == 0 && dispatch.spec().moneyColumns().isEmpty()) {
            return;
        }
        diffs.put(dispatch.txId() + ":" + dispatch.stepIndex(), new TableDiff(
                dispatch.spec().table(), 1, 1, 1, false, money));
    }
}
