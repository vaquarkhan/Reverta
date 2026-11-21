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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process Write-Audit-Publish catalog. Snapshots are immutable; a branch is a pointer.
 * Unchanged rows keep the same map instance (structural sharing). Publish swings the main pointer.
 */
public final class MemoryLakehouse implements PhasePort {
    public static final class Snapshot {
        public final long id;
        public final List<String> columns;
        public final List<Map<String, Object>> rows;

        Snapshot(long id, List<String> columns, List<Map<String, Object>> rows) {
            this.id = id;
            this.columns = List.copyOf(columns);
            this.rows = List.copyOf(rows);
        }
    }

    private long sequence = 1;
    private final Map<Long, Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Long> mains = new ConcurrentHashMap<>();
    private final Map<String, Long> branches = new ConcurrentHashMap<>();
    private final Map<String, Long> prePublish = new ConcurrentHashMap<>();
    private final Map<String, EffectResult> applied = new ConcurrentHashMap<>();

    public void seed(String table, List<String> columns, List<Map<String, Object>> rows) {
        Snapshot snapshot = new Snapshot(sequence++, columns, copyRows(rows));
        snapshots.put(snapshot.id, snapshot);
        mains.put(table, snapshot.id);
    }

    public Snapshot main(String table) {
        Long id = mains.get(table);
        return id == null ? null : snapshots.get(id);
    }

    @Override
    public EffectResult shadow(Dispatch dispatch) {
        EffectResult prior = applied.get("SHADOW:" + dispatch.idempotencyKey());
        if (prior != null) {
            return prior;
        }
        String table = dispatch.spec().table();
        Long mainId = mains.get(table);
        if (mainId == null) {
            return EffectResult.fail("unknown table " + table, false);
        }
        String branchKey = dispatch.txId() + "/" + table;
        long currentId = branches.getOrDefault(branchKey, mainId);
        Snapshot current = snapshots.get(currentId);
        Mutation mutation = mutation(dispatch, current);
        if (mutation.error != null) {
            return EffectResult.fail(mutation.error, false);
        }
        Snapshot next = new Snapshot(sequence++, mutation.columns, mutation.rows);
        snapshots.put(next.id, next);
        branches.put(branchKey, next.id);
        EffectResult result = EffectResult.ok(Map.of("snapshot", next.id, "found", true),
                "lake:" + table + ":" + mainId, "lake:" + table + ":" + next.id);
        applied.put("SHADOW:" + dispatch.idempotencyKey(), result);
        return result;
    }

    @Override
    public EffectResult live(Dispatch dispatch) {
        EffectResult prior = applied.get("LIVE:" + dispatch.idempotencyKey());
        if (prior != null) {
            return prior;
        }
        String table = dispatch.spec().table();
        String branchKey = dispatch.txId() + "/" + table;
        Long branchId = branches.get(branchKey);
        if (branchId == null) {
            return EffectResult.fail("no lakehouse branch", false);
        }
        long previous = mains.get(table);
        prePublish.put(dispatch.txId() + "/" + table, previous);
        mains.put(table, branchId);
        EffectResult result = EffectResult.ok(Map.of("published", branchId, "found", true),
                "lake:" + table + ":" + previous, "lake:" + table + ":" + branchId);
        applied.put("LIVE:" + dispatch.idempotencyKey(), result);
        return result;
    }

    @Override
    public EffectResult compensate(Dispatch dispatch) {
        String table = dispatch.spec().table();
        String key = dispatch.txId() + "/" + table;
        if ("INVERSE".equals(dispatch.action()) && prePublish.containsKey(key)) {
            mains.put(table, prePublish.remove(key));
            branches.remove(key);
            return EffectResult.ok(Map.of("restored", true), "lake:" + table + ":main", "lake:" + table + ":pre");
        }
        branches.remove(key);
        return EffectResult.ok(Map.of("discarded", true), "lake:" + table + ":branch", "lake:" + table + ":dropped");
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
        List<TableDiff> tables = new ArrayList<>();
        for (Map.Entry<String, Long> entry : branches.entrySet()) {
            if (!entry.getKey().startsWith(txId + "/")) {
                continue;
            }
            String table = entry.getKey().substring(txId.length() + 1);
            Snapshot before = snapshots.get(mains.get(table));
            Snapshot after = snapshots.get(entry.getValue());
            if (before == null || after == null || before.id == after.id) {
                continue;
            }
            long changed = 0;
            int max = Math.max(before.rows.size(), after.rows.size());
            for (int i = 0; i < max; i++) {
                Map<String, Object> left = i < before.rows.size() ? before.rows.get(i) : null;
                Map<String, Object> right = i < after.rows.size() ? after.rows.get(i) : null;
                if (left != right) {
                    changed++;
                }
            }
            tables.add(new TableDiff(table, before.rows.size(), after.rows.size(), changed,
                    !before.columns.equals(after.columns), BigDecimal.ZERO));
        }
        return new DiffReport(tables);
    }

    private static Mutation mutation(Dispatch dispatch, Snapshot current) {
        Object sku = dispatch.arguments().get("sku");
        Object qty = dispatch.arguments().get("qty");
        if (!(sku instanceof String) || !(qty instanceof Number)) {
            return new Mutation(null, null, "stock arguments must include sku and qty");
        }
        List<Map<String, Object>> rows = new ArrayList<>(current.rows);
        int found = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (sku.equals(rows.get(i).get("sku"))) {
                found = i;
                break;
            }
        }
        if (found < 0) {
            return new Mutation(null, null, "unknown sku " + sku);
        }
        Map<String, Object> updated = new LinkedHashMap<>(rows.get(found));
        BigDecimal next = Types.asDecimal(updated.get("qty")).add(Types.asDecimal(qty));
        if (next.compareTo(BigDecimal.ZERO) < 0) {
            return new Mutation(null, null, "stock would go negative");
        }
        updated.put("qty", next);
        rows.set(found, updated);
        List<String> columns = current.columns;
        if (dispatch.arguments().get("addColumn") instanceof String column && !columns.contains(column)) {
            columns = new ArrayList<>(columns);
            columns.add(column);
        }
        return new Mutation(columns, rows, null);
    }

    private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows) {
        List<Map<String, Object>> copy = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            copy.add(new LinkedHashMap<>(row));
        }
        return copy;
    }

    private record Mutation(List<String> columns, List<Map<String, Object>> rows, String error) {
    }
}
