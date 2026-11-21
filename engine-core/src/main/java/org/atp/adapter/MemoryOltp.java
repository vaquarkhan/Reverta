// SPDX-License-Identifier: Apache-2.0
package org.atp.adapter;

import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.model.Types;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.TableDiff;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Copy-on-write ledger branch. Publish and restore swap the main map pointer. */
public final class MemoryOltp implements PhasePort {
    private Map<String, BigDecimal> main = new LinkedHashMap<>();
    private final Map<String, Map<String, BigDecimal>> branches = new ConcurrentHashMap<>();
    private final Map<String, Map<String, BigDecimal>> publishedFrom = new ConcurrentHashMap<>();
    private final Map<String, EffectResult> applied = new ConcurrentHashMap<>();

    public void seed(Map<String, BigDecimal> balances) {
        main = new LinkedHashMap<>(balances);
    }

    public Map<String, BigDecimal> main() {
        return Map.copyOf(main);
    }

    public Map<String, BigDecimal> mainIdentity() {
        return main;
    }

    @Override
    public EffectResult shadow(Dispatch dispatch) {
        EffectResult prior = applied.get(key("SHADOW", dispatch));
        if (prior != null) {
            return prior;
        }
        Transfer transfer = transfer(dispatch);
        if (transfer.error != null) {
            return EffectResult.fail(transfer.error, false);
        }
        Map<String, BigDecimal> existing = branches.get(dispatch.txId());
        Map<String, BigDecimal> view = existing == null ? main : existing;
        if (view.getOrDefault(transfer.source, BigDecimal.ZERO).compareTo(transfer.amount) < 0) {
            return EffectResult.fail("insufficient funds", false);
        }
        branches.computeIfAbsent(dispatch.txId(), id -> new LinkedHashMap<>(main));
        Map<String, BigDecimal> branch = branches.get(dispatch.txId());
        branch.put(transfer.source, branch.getOrDefault(transfer.source, BigDecimal.ZERO).subtract(transfer.amount));
        branch.put(transfer.target, branch.getOrDefault(transfer.target, BigDecimal.ZERO).add(transfer.amount));
        EffectResult result = EffectResult.ok(Map.of("applied", true, "found", true),
                "oltp:" + dispatch.txId() + ":open", "oltp:" + dispatch.txId() + ":branch");
        applied.put(key("SHADOW", dispatch), result);
        return result;
    }

    @Override
    public EffectResult live(Dispatch dispatch) {
        EffectResult prior = applied.get(key("LIVE", dispatch));
        if (prior != null) {
            return prior;
        }
        Map<String, BigDecimal> branch = branches.get(dispatch.txId());
        if (branch == null) {
            return EffectResult.fail("no oltp branch", false);
        }
        publishedFrom.put(dispatch.txId(), main);
        main = new LinkedHashMap<>(branch);
        EffectResult result = EffectResult.ok(Map.of("published", true, "found", true),
                "oltp:" + dispatch.txId() + ":pre", "oltp:" + dispatch.txId() + ":main");
        applied.put(key("LIVE", dispatch), result);
        return result;
    }

    @Override
    public EffectResult compensate(Dispatch dispatch) {
        if ("INVERSE".equals(dispatch.action()) && publishedFrom.containsKey(dispatch.txId())) {
            main = publishedFrom.remove(dispatch.txId());
            branches.remove(dispatch.txId());
            return EffectResult.ok(Map.of("restored", true), "oltp:" + dispatch.txId() + ":main", "oltp:" + dispatch.txId() + ":pre");
        }
        branches.remove(dispatch.txId());
        return EffectResult.ok(Map.of("discarded", true), "oltp:" + dispatch.txId() + ":branch", "oltp:" + dispatch.txId() + ":dropped");
    }

    @Override
    public EffectResult reconcile(Dispatch dispatch) {
        EffectResult prior = applied.get(dispatch.phase() + ":" + dispatch.idempotencyKey());
        if (prior == null) {
            return EffectResult.ok(Map.of("found", false), null, null);
        }
        Map<String, Object> output = new LinkedHashMap<>(prior.output());
        output.put("found", true);
        return EffectResult.ok(output, prior.preState(), prior.postState());
    }

    @Override
    public DiffReport diff(String txId) {
        Map<String, BigDecimal> branch = branches.get(txId);
        if (branch == null) {
            return DiffReport.empty();
        }
        long changed = 0;
        BigDecimal money = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> entry : branch.entrySet()) {
            BigDecimal before = main.get(entry.getKey());
            if (before == null || before.compareTo(entry.getValue()) != 0) {
                changed++;
                if (before != null && entry.getValue().compareTo(before) < 0) {
                    money = money.add(before.subtract(entry.getValue()));
                }
            }
        }
        long rowsBefore = main.size();
        return new DiffReport(java.util.List.of(new TableDiff(
                "customer_ledger", rowsBefore, branch.size(), changed, false, money)));
    }

    private static String key(String phase, Dispatch dispatch) {
        return phase + ":" + dispatch.idempotencyKey();
    }

    private static Transfer transfer(Dispatch dispatch) {
        Object source = dispatch.arguments().get("source");
        Object target = dispatch.arguments().get("target");
        Object amount = dispatch.arguments().get("amount");
        if (!(source instanceof String) || !(target instanceof String) || !(amount instanceof Number)) {
            return new Transfer(null, null, null, "ledger arguments must include source, target, and amount");
        }
        return new Transfer((String) source, (String) target, Types.asDecimal(amount), null);
    }

    private record Transfer(String source, String target, BigDecimal amount, String error) {
    }
}
