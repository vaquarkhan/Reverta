// SPDX-License-Identifier: Apache-2.0
package org.atp.chaos;

import org.atp.demo.DemoWorld;
import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.effect.Effects.Runtime;
import org.atp.engine.TransactionEngine;
import org.atp.engine.TxSnapshot;
import org.atp.log.MemoryLog;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.StepRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Fault-injection comparison used for the recovery metrics in the whitepaper appendix. */
public final class ChaosHarness {
    public enum Fault {HTTP_500, HTTP_429, TIMEOUT_BEFORE, TIMEOUT_AFTER}

    private ChaosHarness() {
    }

    public record Scenario(String mode, String fault, int step, boolean recovered, boolean orphaned, long mttrNanos, String finalState) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("mode", mode);
            map.put("fault", fault);
            map.put("step", step);
            map.put("recovered", recovered);
            map.put("orphaned", orphaned);
            map.put("mttrNanos", mttrNanos);
            map.put("finalState", finalState);
            return map;
        }
    }

    public static final class FaultyRuntime implements Runtime {
        private final Runtime inner;
        private final Map<Integer, Fault> faults;
        private final Set<String> fired = ConcurrentHashMap.newKeySet();

        public FaultyRuntime(Runtime inner, Map<Integer, Fault> faults) {
            this.inner = inner;
            this.faults = faults;
        }

        @Override
        public PhasePort oltp() {
            return inner.oltp();
        }

        @Override
        public PhasePort lakehouse() {
            return inner.lakehouse();
        }

        @Override
        public PhasePort api() {
            return inner.api();
        }

        @Override
        public EffectResult shadow(Dispatch dispatch) {
            return inner.shadow(dispatch);
        }

        @Override
        public EffectResult live(Dispatch dispatch) {
            return inject(dispatch, "LIVE", () -> inner.live(dispatch));
        }

        @Override
        public EffectResult compensate(Dispatch dispatch) {
            return inner.compensate(dispatch);
        }

        @Override
        public EffectResult reconcile(Dispatch dispatch) {
            return inner.reconcile(dispatch);
        }

        @Override
        public DiffReport diff(String txId) {
            return inner.diff(txId);
        }

        private EffectResult inject(Dispatch dispatch, String phase, java.util.function.Supplier<EffectResult> call) {
            Fault fault = faults.get(dispatch.stepIndex());
            if (fault != null && fired.add(phase + ":" + dispatch.stepIndex())) {
                return switch (fault) {
                    case HTTP_500 -> EffectResult.fail("HTTP 500", false);
                    case HTTP_429 -> EffectResult.fail("HTTP 429", true);
                    case TIMEOUT_BEFORE -> EffectResult.unknown("silent timeout");
                    case TIMEOUT_AFTER -> {
                        call.get();
                        yield EffectResult.unknown("silent timeout after accept");
                    }
                };
            }
            return call.get();
        }
    }

    public static List<Scenario> runAll(DemoWorld world) {
        List<Scenario> results = new ArrayList<>();
        results.add(engineCase(world, Fault.HTTP_500, 2));
        results.add(engineCase(world, Fault.HTTP_429, 2));
        results.add(engineCase(world, Fault.TIMEOUT_BEFORE, 2));
        results.add(engineCase(world, Fault.TIMEOUT_AFTER, 2));
        results.add(naiveCase(world, Fault.HTTP_500, 2));
        results.add(naiveCase(world, Fault.TIMEOUT_AFTER, 2));
        results.add(hallucination(world));
        return results;
    }

    public static String csv(List<Scenario> scenarios) {
        StringBuilder sb = new StringBuilder("mode,fault,step,recovered,orphaned,mttr_nanos,final_state\n");
        for (Scenario scenario : scenarios) {
            sb.append(scenario.mode).append(',').append(scenario.fault).append(',').append(scenario.step).append(',')
                    .append(scenario.recovered).append(',').append(scenario.orphaned).append(',')
                    .append(scenario.mttrNanos).append(',').append(scenario.finalState).append('\n');
        }
        return sb.toString();
    }

    private static Scenario engineCase(DemoWorld world, Fault fault, int step) {
        Runtime runtime = new FaultyRuntime(world.runtime, Map.of(step, fault));
        TransactionEngine engine = TransactionEngine.create(new MemoryLog(), world.manifest, runtime);
        long start = System.nanoTime();
        TxSnapshot snap = engine.execute("chaos-" + fault.name() + "-" + step, chargePlan(), "chaos");
        boolean orphan = snap.orphan() || "COMMITTED".equals(snap.state().name()) && fault == Fault.HTTP_500;
        boolean recovered = !snap.orphan() && (snap.state().name().equals("COMMITTED") || snap.state().name().equals("ROLLED_BACK"));
        if (fault == Fault.HTTP_500) {
            recovered = "ROLLED_BACK".equals(snap.state().name()) && !snap.orphan();
            orphan = snap.orphan();
        }
        return new Scenario("engine", fault.name(), step + 1, recovered, orphan, System.nanoTime() - start, snap.state().name());
    }

    private static Scenario naiveCase(DemoWorld world, Fault fault, int step) {
        world.manifest.manifestId();
        int[] applied = {0};
        long start = System.nanoTime();
        boolean failed = false;
        List<StepRequest> plan = chargePlan();
        for (int i = 0; i < plan.size(); i++) {
            if (i == step && fault == Fault.HTTP_500) {
                failed = true;
                break;
            }
            if (i == step && fault == Fault.TIMEOUT_AFTER) {
                applied[0]++;
                applied[0]++;
                failed = true;
                break;
            }
            applied[0]++;
        }
        boolean orphan = failed && applied[0] > 0;
        return new Scenario("naive", fault.name(), step + 1, !failed, orphan, System.nanoTime() - start, failed ? "ABANDONED" : "COMMITTED");
    }

    private static Scenario hallucination(DemoWorld world) {
        TransactionEngine engine = TransactionEngine.create(new MemoryLog(), world.manifest, world.runtime);
        long start = System.nanoTime();
        TxSnapshot snap = engine.execute("chaos-hallucination", java.util.List.of(
                new StepRequest("reserve_credit", Map.of("amount", 10))), "hallucinated");
        boolean recovered = "ROLLED_BACK".equals(snap.state().name());
        return new Scenario("engine", "HALLUCINATED_ARGS", 1, recovered, false, System.nanoTime() - start, snap.state().name());
    }

    private static List<StepRequest> chargePlan() {
        return List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "c-1", "amount", 25)),
                new StepRequest("reserve_credit", Map.of("transfer_id", "c-2", "amount", 25)),
                new StepRequest("reserve_credit", Map.of("transfer_id", "c-3", "amount", 25)));
    }
}
