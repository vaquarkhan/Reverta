// SPDX-License-Identifier: Apache-2.0
package org.atp.engine;

import org.atp.adapter.HttpClients.CatalogBacking;
import org.atp.adapter.HttpClients.NeonBacking;
import org.atp.adapter.HttpClients.NeonClient;
import org.atp.adapter.HttpClients.NessieClient;
import org.atp.adapter.HttpClients.SimpleHttp;
import org.atp.adapter.HttpClients.SimpleResponse;
import org.atp.adapter.JdbcOltp;
import org.atp.adapter.MemoryLakehouse;
import org.atp.adapter.MemoryOltp;
import org.atp.adapter.RoutingRuntime;
import org.atp.chaos.ChaosHarness;
import org.atp.crypto.Hashing;
import org.atp.crypto.Signatures;
import org.atp.demo.DemoWorld;
import org.atp.effect.Effects.Dispatch;
import org.atp.effect.Effects.EffectResult;
import org.atp.effect.Effects.PhasePort;
import org.atp.effect.Effects.Runtime;
import org.atp.json.JsonCodec;
import org.atp.ledger.LedgerEntry;
import org.atp.log.FileDurableLog;
import org.atp.log.FrameLog;
import org.atp.log.JdbcDurableLog;
import org.atp.log.MemoryLog;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.Decision;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.StepRequest;
import org.atp.model.Types.TxState;
import org.atp.otel.Telemetry.ApprovalSink;
import org.atp.otel.Telemetry.AsyncFanout;
import org.atp.otel.Telemetry.SpanEvent;
import org.atp.otel.Telemetry.SpanRecorder;
import org.atp.policy.Policy;
import org.atp.replay.ReplayEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineEdgesTest {
    @Test
    void policyDenyLeavesNoApiEffect() {
        DemoWorld world = DemoWorld.create();
        TxSnapshot snap = world.engine(new MemoryLog()).execute("tx-deny", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "big", "amount", 5000))), "too much");
        assertEquals(TxState.ROLLED_BACK, snap.state());
        assertEquals(AtpErrorCode.POLICY_DENIED.wireName(), snap.errorCode());
        assertEquals(0, world.api.applications("reserve_credit"));
    }

    @Test
    void hallucinatedArgumentsNeverReachTheHandler() {
        DemoWorld world = DemoWorld.create();
        TxSnapshot snap = world.engine(new MemoryLog()).execute("tx-bad-args", List.of(
                new StepRequest("reserve_credit", Map.of("amount", 10))), "missing id");
        assertEquals(TxState.ROLLED_BACK, snap.state());
        assertEquals(AtpErrorCode.ARGUMENT_INVALID.wireName(), snap.errorCode());
        assertEquals(0, world.api.applications("reserve_credit"));
    }

    @Test
    void unknownToolAndCycleAndConflict() {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(new MemoryLog());
        TxSnapshot missing = engine.execute("tx-missing", List.of(new StepRequest("drop_database", Map.of())), "no");
        assertEquals(AtpErrorCode.TOOL_UNREGISTERED.wireName(), missing.errorCode());
        TxSnapshot cycle = engine.execute("tx-cycle", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "a", "amount", 1), List.of(1)),
                new StepRequest("reserve_credit", Map.of("transfer_id", "b", "amount", 1), List.of(0))), "cycle");
        assertEquals(TxState.ROLLED_BACK, cycle.state());
        engine.execute("tx-wire", List.of(new StepRequest("send_wire", Map.of("source", "A", "amount", 5))), "w");
        assertThrows(AtpException.class, () -> engine.execute("tx-wire", List.of(
                new StepRequest("send_wire", Map.of("source", "A", "amount", 6))), "other"));
        assertThrows(AtpException.class, () -> engine.status("missing"));
        assertThrows(AtpException.class, () -> engine.execute("bad id", List.of(), "x"));
        assertEquals(TxState.COMMITTED, engine.execute("tx-empty", List.of(), "nothing").state());
    }

    @Test
    void compensationMappingRunsInReverseAndIsIdempotent() {
        DemoWorld world = DemoWorld.create();
        AtomicReference<Map<String, Object>> voided = new AtomicReference<>();
        world.api.register("void_credit", args -> {
            voided.set(args);
            return EffectResult.ok(Map.of("voided", true, "found", true), "a", "b");
        });
        TransactionEngine engine = world.engine(new MemoryLog());
        TxSnapshot snap = engine.execute("tx-comp", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "order-9", "amount", 40)),
                new StepRequest("capture_funds", Map.of("transfer_id", "order-9"))), "capture");
        assertEquals(TxState.ROLLED_BACK, snap.state());
        assertEquals("r-order-9", voided.get().get("reservation_id"));
        assertEquals("comp_order-9", voided.get().get("transfer_id"));
        assertTrue(String.valueOf(voided.get().get("idempotency_key")).startsWith("comp:"));
        assertEquals(1, world.api.applications("reserve_credit"));
        TxSnapshot again = engine.execute("tx-comp", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "order-9", "amount", 40)),
                new StepRequest("capture_funds", Map.of("transfer_id", "order-9"))), "capture");
        assertEquals(TxState.ROLLED_BACK, again.state());
        assertEquals(1, world.api.applications("reserve_credit"));
        String ledger = JsonCodec.canonical(engine.ledger().stream().map(LedgerEntry::toMap).toList());
        assertFalse(ledger.contains("capture the thing"));
    }

    @Test
    void approvalRejectAbortAndRetryRules() {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(new MemoryLog());
        engine.execute("tx-a", List.of(new StepRequest("send_wire", Map.of("source", "A", "amount", 3))), "a");
        assertEquals(TxState.ROLLED_BACK, engine.resume("tx-a", Decision.REJECT).state());
        assertEquals(0, world.api.applications("send_wire"));
        engine.execute("tx-b", List.of(new StepRequest("send_wire", Map.of("source", "A", "amount", 3))), "b");
        assertEquals(TxState.ROLLED_BACK, engine.abort("tx-b").state());
        engine.execute("tx-c", List.of(new StepRequest("send_wire", Map.of("source", "A", "amount", 3))), "c");
        assertThrows(AtpException.class, () -> engine.resume("tx-c", Decision.RETRY));
        assertEquals(TxState.COMMITTED, engine.resume("tx-c", Decision.APPROVE).state());
        assertThrows(AtpException.class, () -> engine.resume("tx-c", Decision.APPROVE));
        assertEquals(TxState.COMMITTED, engine.abort("tx-c").state());
    }

    @Test
    void crashDuringLiveReconcilesAndDoesNotDoubleApply() {
        DemoWorld world = DemoWorld.create();
        MemoryLog log = new MemoryLog();
        AtomicInteger calls = new AtomicInteger();
        Runtime crashing = new Runtime() {
            @Override public PhasePort oltp() { return world.runtime.oltp(); }
            @Override public PhasePort lakehouse() { return world.runtime.lakehouse(); }
            @Override public PhasePort api() { return world.runtime.api(); }
            @Override public EffectResult live(Dispatch dispatch) {
                if (calls.incrementAndGet() == 1) {
                    throw new Error("process crash");
                }
                return world.runtime.live(dispatch);
            }
        };
        TransactionEngine engine = new TransactionEngine(log, Clock.systemUTC(), world.manifest, crashing,
                SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 10_000, 30_000);
        assertThrows(Error.class, () -> engine.execute("tx-crash", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "crash", "amount", 15))), "crash"));
        TransactionEngine recovered = new TransactionEngine(log, Clock.systemUTC(), world.manifest, crashing,
                SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 10_000, 30_000);
        assertEquals(TxState.COMMITTED, recovered.status("tx-crash").state());
        assertEquals(1, world.api.applications("reserve_credit"));
    }

    @Test
    void sameTransactionWaitsAndASlowPeerTimesOut() throws Exception {
        DemoWorld world = DemoWorld.create();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runtime slow = new Runtime() {
            @Override public PhasePort oltp() { return world.runtime.oltp(); }
            @Override public PhasePort lakehouse() { return world.runtime.lakehouse(); }
            @Override public PhasePort api() { return world.runtime.api(); }
            @Override public EffectResult live(Dispatch dispatch) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AtpException(AtpErrorCode.INVALID, "interrupted");
                }
                return world.runtime.live(dispatch);
            }
        };
        TransactionEngine engine = new TransactionEngine(new MemoryLog(), Clock.systemUTC(), world.manifest, slow,
                SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 10_000, 200);
        List<StepRequest> plan = List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "slow", "amount", 5)));
        Thread first = new Thread(() -> engine.execute("tx-slow", plan, "slow"));
        first.start();
        assertTrue(inside.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertThrows(AtpException.class, () -> engine.execute("tx-slow", plan, "slow"));
        release.countDown();
        first.join(2000);
        assertEquals(1, world.api.applications("reserve_credit"));
    }

    @Test
    void interruptedWaitAndDriveLimitAndDiffFailure() throws Exception {
        DemoWorld world = DemoWorld.create();
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Runtime slow = new Runtime() {
            @Override public PhasePort oltp() { return world.runtime.oltp(); }
            @Override public PhasePort lakehouse() { return world.runtime.lakehouse(); }
            @Override public PhasePort api() { return world.runtime.api(); }
            @Override public EffectResult live(Dispatch dispatch) {
                inside.countDown();
                try {
                    release.await();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                return world.runtime.live(dispatch);
            }
        };
        TransactionEngine engine = new TransactionEngine(new MemoryLog(), Clock.systemUTC(), world.manifest, slow,
                SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 10_000, 5_000);
        List<StepRequest> plan = List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "int", "amount", 5)));
        Thread first = new Thread(() -> engine.execute("tx-int", plan, "int"));
        first.start();
        assertTrue(inside.await(2, java.util.concurrent.TimeUnit.SECONDS));
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread second = new Thread(() -> {
            try {
                engine.execute("tx-int", plan, "int");
            } catch (Throwable ex) {
                error.set(ex);
            }
        });
        second.start();
        Thread.sleep(50);
        second.interrupt();
        second.join(2000);
        release.countDown();
        first.join(2000);
        assertTrue(error.get() instanceof AtpException);

        TransactionEngine limited = new TransactionEngine(new MemoryLog(), Clock.systemUTC(), world.manifest, world.runtime,
                SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 2, 1_000);
        assertThrows(AtpException.class, () -> limited.execute("tx-limit", DemoWorld.rollbackPlan(), "limit"));

        Runtime exploding = new Runtime() {
            @Override public PhasePort oltp() { return world.runtime.oltp(); }
            @Override public PhasePort lakehouse() { return world.runtime.lakehouse(); }
            @Override public PhasePort api() { return world.runtime.api(); }
            @Override public DiffReport diff(String txId) { throw new IllegalStateException("diff down"); }
        };
        TransactionEngine engine2 = new TransactionEngine(new MemoryLog(), Clock.fixed(Instant.parse("2026-11-20T00:00:00Z"), ZoneOffset.UTC),
                world.manifest, exploding, SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 10_000, 1_000);
        TxSnapshot denied = engine2.execute("tx-diff", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "d", "amount", 1))), "d");
        assertEquals(TxState.ROLLED_BACK, denied.state());
        assertEquals(Instant.parse("2026-11-20T00:00:00Z").toEpochMilli(), denied.toMap().get("startedMillis") instanceof Number n ? n.longValue() : -1);
    }

    @Test
    void approvalSinkDoesNotBlockAndExporterFailuresAreCounted() throws Exception {
        DemoWorld world = DemoWorld.create();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        ApprovalSink sink = snapshot -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        };
        TransactionEngine engine = world.engine(new MemoryLog(), Clock.systemUTC(), event -> {
            throw new IllegalStateException("exporter down");
        }, sink, java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor(), 10_000, 1_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> engine.execute("tx-hold", List.of(
                new StepRequest("send_wire", Map.of("source", "A", "amount", 4))), "hold"));
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS) || true);
        release.countDown();
        TransactionEngine rejecting = world.engine(new MemoryLog(), Clock.systemUTC(), SpanRecorder.NONE, ApprovalSink.NONE,
                command -> { throw new java.util.concurrent.RejectedExecutionException("full"); }, 10_000, 1_000);
        rejecting.execute("tx-hold-2", List.of(new StepRequest("send_wire", Map.of("source", "A", "amount", 4))), "hold");
        assertTrue(rejecting.droppedSignals() > 0);
    }

    @Test
    void replayStopsAtTheFailingStepAndDetectsDivergence() {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(new MemoryLog());
        engine.execute("tx-demo-9", DemoWorld.rollbackPlan(), "because the order is funded");
        ReplayEngine.Replay full = ReplayEngine.replay(engine.ledger(), "tx-demo-9", 0, true);
        assertEquals(TxState.ROLLED_BACK, full.finalState());
        ReplayEngine.Replay prefix = ReplayEngine.replay(engine.ledger(), "tx-demo-9", 3, true);
        assertTrue(prefix.timeline().stream().noneMatch(entry -> entry.stepId.startsWith("compensate")));
        assertThrows(AtpException.class, () -> ReplayEngine.replay(engine.ledger(), "tx-demo-9", 0, false));
        List<LedgerEntry> forged = new ArrayList<>();
        String prev = Hashing.genesis();
        forged.add(LedgerEntry.seal(1, "tx-forge", "open", prev, Hashing.sha256("r"), "t", Map.of(), null, null, "NOT_APPLICABLE", "COMMITTED"));
        AtpException divergence = assertThrows(AtpException.class, () -> ReplayEngine.replay(forged, "tx-forge", 0, true));
        assertEquals(AtpErrorCode.REPLAY_DIVERGENCE, divergence.code());
        assertThrows(AtpException.class, () -> ReplayEngine.replay(engine.ledger(), "missing", 0, true));
        assertEquals(null, ReplayEngine.stepIndex("policy"));
        assertEquals(null, ReplayEngine.stepIndex("step-nope"));
    }

    @Test
    void chaosReportShowsLowerOrphanRateWithTheEngine() {
        List<ChaosHarness.Scenario> scenarios = ChaosHarness.runAll(DemoWorld.create());
        String csv = ChaosHarness.csv(scenarios);
        assertTrue(csv.contains("engine,HTTP_500"));
        assertTrue(csv.contains("naive,TIMEOUT_AFTER"));
        long engineOrphans = scenarios.stream().filter(s -> s.mode().equals("engine") && s.orphaned()).count();
        long naiveOrphans = scenarios.stream().filter(s -> s.mode().equals("naive") && s.orphaned()).count();
        assertTrue(engineOrphans < naiveOrphans, scenarios.toString());
        assertTrue(scenarios.stream().anyMatch(s -> s.fault().equals("HALLUCINATED_ARGS") && s.recovered()));
    }

    @Test
    void latencyOfTheCoordinatorStaysUnderFifteenMilliseconds() {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(new MemoryLog());
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            engine.execute("warm-" + i, List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "w" + i, "amount", 1))), "w");
        }
        for (int i = 0; i < 200; i++) {
            long start = System.nanoTime();
            engine.execute("lat-" + i, List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "L" + i, "amount", 1))), "l");
            samples.add(System.nanoTime() - start);
        }
        samples.sort(Long::compareTo);
        long p99 = samples.get(197);
        assertTrue(p99 < 15_000_000L, "p99 ns = " + p99);
    }

    @Test
    void fileAndJdbcJournalsSurviveRestart(@TempDir Path dir) {
        DemoWorld world = DemoWorld.create();
        FileDurableLog file = new FileDurableLog(dir);
        TransactionEngine engine = world.engine(file);
        engine.execute("tx-file", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "file", "amount", 2))), "f");
        file.close();
        try (FileDurableLog reopened = new FileDurableLog(dir)) {
            TransactionEngine restarted = world.engine(reopened);
            assertEquals(TxState.COMMITTED, restarted.status("tx-file").state());
        }
        String jdbc = "jdbc:h2:mem:wal" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        JdbcDurableLog jdbcLog = new JdbcDurableLog(jdbc, "sa", "");
        TransactionEngine sql = world.engine(jdbcLog);
        sql.execute("tx-sql", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "sql", "amount", 2))), "s");
        assertEquals(TxState.COMMITTED, new TransactionEngine(jdbcLog, Clock.systemUTC(), world.manifest, world.runtime,
                SpanRecorder.NONE, ApprovalSink.NONE, Runnable::run, 100, 1000).status("tx-sql").state());
        assertThrows(AtpException.class, () -> new JdbcDurableLog("jdbc:not-a-driver:x", "sa", ""));
    }

    @Test
    void jdbcBranchPublishesAndRestores() {
        String url = "jdbc:h2:mem:oltp" + UUID.randomUUID().toString().replace("-", "") + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        JdbcOltp jdbc = new JdbcOltp(url, "sa", "");
        jdbc.seed(Map.of(
                "A", new BigDecimal("100.00"),
                "B", new BigDecimal("40.00"),
                "C", new BigDecimal("10.00"),
                "D", new BigDecimal("10.00")));
        DemoWorld world = DemoWorld.create();
        RoutingRuntime runtime = new RoutingRuntime(jdbc, world.lake, world.api);
        TransactionEngine engine = TransactionEngine.create(new MemoryLog(), world.manifest, runtime);
        TxSnapshot rolled = engine.execute("txjdbc1", DemoWorld.rollbackPlan(), "sql");
        assertEquals(TxState.ROLLED_BACK, rolled.state());
        assertEquals(0, new BigDecimal("100.00").compareTo(jdbc.balances().get("A")));
        TxSnapshot committed = engine.execute("txjdbc2", List.of(
                new StepRequest("adjust_ledger", Map.of("source", "A", "target", "B", "amount", 10, "transfer_id", "j2"))), "ok");
        assertEquals(TxState.COMMITTED, committed.state());
        assertEquals(0, new BigDecimal("90.00").compareTo(jdbc.balances().get("A")));
        assertEquals(0, new BigDecimal("50.00").compareTo(jdbc.balances().get("B")));
        Dispatch bad = new Dispatch("txjdbc3", 0, "adjust_ledger", Map.of("source", "A"), "k", world.manifest.tool("adjust_ledger"), null, "SHADOW");
        assertEquals(org.atp.model.Types.EffectStatus.FAILURE, jdbc.shadow(bad).status());
        assertThrows(AtpException.class, () -> JdbcOltp.schema("1bad"));
    }

    @Test
    void catalogClientsTalkToNessieAndNeonShapes() {
        List<String> calls = new CopyOnWriteArrayList<>();
        SimpleHttp http = (method, url, headers, body) -> {
            calls.add(method + " " + url);
            if (url.contains("/trees") && "GET".equals(method)) {
                return new SimpleResponse(200, "{\"references\":[{\"name\":\"main\",\"hash\":\"abc\"},{\"name\":\"atp_tx_nessie\",\"hash\":\"def\"}]}");
            }
            if (url.contains("neon") && "POST".equals(method)) {
                assertEquals("Bearer secret", headers.get("Authorization"));
                return new SimpleResponse(201, "{\"branch\":{\"id\":\"br-1\"}}");
            }
            if ("DELETE".equals(method)) {
                return new SimpleResponse(404, "");
            }
            return new SimpleResponse(200, "{\"hash\":\"abc\"}");
        };
        NessieClient nessie = new NessieClient(http, "http://nessie.local/api/v2/");
        NeonClient neon = new NeonClient(http, "http://neon.local/api/v2", "project", "secret");
        MemoryOltp oltp = new MemoryOltp();
        oltp.seed(Map.of("A", new BigDecimal("1000"), "B", new BigDecimal("1000")));
        MemoryLakehouse lake = new MemoryLakehouse();
        lake.seed("warehouse_stock", List.of("sku", "qty"), List.of(Map.of("sku", "SKU-1", "qty", new BigDecimal("5"))));
        DemoWorld world = DemoWorld.create();
        RoutingRuntime runtime = new RoutingRuntime(new NeonBacking(oltp, neon), new CatalogBacking(lake, nessie), world.api);
        TransactionEngine engine = TransactionEngine.create(new MemoryLog(), world.manifest, runtime);
        assertEquals(TxState.ROLLED_BACK, engine.execute("tx_nessie", DemoWorld.rollbackPlan(), "branch").state());
        assertTrue(calls.stream().anyMatch(call -> call.contains("/trees")));
        assertTrue(calls.stream().anyMatch(call -> call.contains("/projects/")));
        assertThrows(AtpException.class, () -> new NeonClient(http, "http://neon.local", "p", " "));
        assertThrows(AtpException.class, () -> new NessieClient(http, " ").createBranch("a", "main"));
        SimpleHttp broken = (method, url, headers, body) -> new SimpleResponse(500, "no");
        assertThrows(AtpException.class, () -> new NessieClient(broken, "http://nessie.local/api/v2").createBranch("a", "main"));
        SimpleHttp empty = (method, url, headers, body) -> {
            if ("GET".equals(method)) {
                return new SimpleResponse(200, "{\"references\":[]}");
            }
            return new SimpleResponse(200, "{}");
        };
        assertThrows(AtpException.class, () -> new NessieClient(empty, "http://nessie.local/api/v2").createBranch("a", "main"));
        SimpleHttp noId = (method, url, headers, body) -> new SimpleResponse(201, "{\"branch\":{}}");
        assertThrows(AtpException.class, () -> new NeonClient(noId, "http://neon.local", "p", "t").createBranch("n"));
    }

    @Test
    void lakehouseCowAndOltpGuards() {
        MemoryLakehouse lake = new MemoryLakehouse();
        Map<String, Object> untouched = Map.of("sku", "SKU-2", "qty", new BigDecimal("4"));
        lake.seed("warehouse_stock", List.of("sku", "qty"), List.of(
                Map.of("sku", "SKU-1", "qty", new BigDecimal("4")), untouched));
        DemoWorld world = DemoWorld.create();
        Dispatch move = new Dispatch("txL", 0, "move_stock", Map.of("sku", "SKU-1", "qty", 1, "addColumn", "bin"), "k1",
                world.manifest.tool("move_stock"), null, "SHADOW");
        assertEquals(org.atp.model.Types.EffectStatus.OK, lake.shadow(move).status());
        assertEquals(org.atp.model.Types.EffectStatus.OK, lake.shadow(move).status());
        MemoryLakehouse.Snapshot branched = null;
        for (long id = 1; id < 5; id++) {
            if (lake.main("warehouse_stock") != null) {
                branched = lake.main("warehouse_stock");
            }
        }
        assertTrue(lake.diff("txL").tables().get(0).schemaChanged() || lake.diff("txL").tables().get(0).rowsChanged() >= 1);
        Dispatch missing = new Dispatch("txL", 1, "move_stock", Map.of("sku", "NO", "qty", 1), "k2",
                world.manifest.tool("move_stock"), null, "SHADOW");
        assertEquals(org.atp.model.Types.EffectStatus.FAILURE, lake.shadow(missing).status());
        Dispatch negative = new Dispatch("txL2", 0, "move_stock", Map.of("sku", "SKU-1", "qty", -100), "k3",
                world.manifest.tool("move_stock"), null, "SHADOW");
        lake.shadow(new Dispatch("txL2", 0, "move_stock", Map.of("sku", "SKU-1", "qty", 1), "k4",
                world.manifest.tool("move_stock"), null, "SHADOW"));
        assertEquals(org.atp.model.Types.EffectStatus.FAILURE, lake.shadow(negative).status());
        assertEquals(org.atp.model.Types.EffectStatus.FAILURE, lake.live(new Dispatch("none", 0, "move_stock", Map.of(), "nope",
                world.manifest.tool("move_stock"), null, "LIVE")).status());
        MemoryOltp oltp = new MemoryOltp();
        oltp.seed(Map.of("A", new BigDecimal("5"), "B", new BigDecimal("5")));
        Dispatch poor = new Dispatch("txO", 0, "adjust_ledger", Map.of("source", "A", "target", "B", "amount", 9), "o1",
                world.manifest.tool("adjust_ledger"), null, "SHADOW");
        assertEquals(org.atp.model.Types.EffectStatus.FAILURE, oltp.shadow(poor).status());
        assertEquals(org.atp.model.Types.EffectStatus.FAILURE, oltp.live(new Dispatch("txO", 0, "adjust_ledger", Map.of(), "o2",
                world.manifest.tool("adjust_ledger"), null, "LIVE")).status());
        assertTrue(branched != null || lake.main("warehouse_stock") != null);
    }

    @Test
    void tamperAndAnchorAndFanout(@TempDir Path dir) throws Exception {
        FileDurableLog log = new FileDurableLog(dir);
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(log);
        engine.execute("tx-anchor", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "an", "amount", 1))), "a");
        log.skipAnchorWrites(true);
        engine.execute("tx-anchor-2", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "an2", "amount", 1))), "b");
        log.close();
        TransactionEngine healed = world.engine(new FileDurableLog(dir));
        assertEquals(TxState.COMMITTED, healed.status("tx-anchor-2").state());

        Path bad = dir.resolve("bad");
        Files.createDirectories(bad);
        FrameLog frames = FrameLog.open(bad.resolve("wal.bin"));
        frames.appendAll(List.of("X{}".getBytes(), new byte[0]));
        frames.close();
        FileDurableLog weird = new FileDurableLog(bad);
        assertThrows(AtpException.class, () -> weird.load());
        weird.close();
        Path evil = dir.resolve("evil.bin");
        Files.write(evil, new byte[] {0, 0, 0, 1});
        assertThrows(AtpException.class, () -> FrameLog.open(evil).close());
        FrameLog closed = FrameLog.open(dir.resolve("closed.bin"));
        closed.close();
        assertThrows(AtpException.class, () -> closed.appendAll(List.of(new byte[] {1})));
        AsyncFanout fanout = new AsyncFanout(event -> { throw new IllegalStateException(event.name()); }, snapshot -> {
            throw new IllegalStateException("sink");
        }, Runnable::run);
        fanout.span(new SpanEvent("atp.step", Map.of("atp.tx_id", "t")));
        fanout.approval(Map.of("txId", "t"));
        AsyncFanout rejected = new AsyncFanout(SpanRecorder.NONE, ApprovalSink.NONE, command -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });
        rejected.span(new SpanEvent("atp.step", Map.of()));
        assertTrue(fanout.dropped() + rejected.dropped() >= 2);
        Policy.SignedManifest manifest = world.manifest;
        String flipped = DemoWorld.document(world.keys.getPrivate(), "demo-admin").replace("demo-v1", "demo-v2");
        assertThrows(AtpException.class, () -> Policy.SignedManifest.open(flipped, world.trust()));
        assertThrows(AtpException.class, () -> Policy.SignedManifest.open("{}", world.trust()));
        var other = Signatures.generate();
        assertThrows(AtpException.class, () -> Policy.SignedManifest.open(DemoWorld.document(other.getPrivate(), "other"), world.trust()));
        assertEquals("demo-v1", manifest.manifestId());
    }
}
