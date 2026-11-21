package org.atp.engine;

import org.atp.adapter.ApiRuntime;
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
import org.atp.json.JsonCodec;
import org.atp.ledger.LedgerEntry;
import org.atp.log.FileDurableLog;
import org.atp.log.FrameLog;
import org.atp.log.MemoryLog;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.Decision;
import org.atp.model.Types.DiffReport;
import org.atp.model.Types.StepRequest;
import org.atp.model.Types.TxState;
import org.atp.policy.Policy;
import org.atp.policy.Policy.SignedManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineCoverageTest {
    @Test
    void policyManifestRejectsUntrustedAndIncompleteDefinitions() {
        DemoWorld world = DemoWorld.create();
        assertEquals("demo-admin", world.manifest.kid());
        assertTrue(world.manifest.tools().containsKey("adjust_ledger"));
        assertEquals(64, Policy.reasoningHash(null).length());
        Policy.Decision paused = Policy.evaluate(world.manifest, DiffReport.empty(), List.of(world.manifest.tool("send_wire")));
        assertEquals("PAUSE", paused.outcome());
        assertTrue(paused.deny() == false && !paused.allow());
        SignedManifest numeric = signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of(
                Map.of("id", "num", "expression", "1", "onFail", "DENY"))));
        assertEquals("DENY", Policy.evaluate(numeric, DiffReport.empty(), List.of(numeric.tool("probe"))).outcome());
        SignedManifest broken = signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of(
                Map.of("id", "bad", "expression", "nope()", "onFail", "DENY"))));
        assertTrue(Policy.evaluate(broken, DiffReport.empty(), List.of(broken.tool("probe"))).reason().contains("failed closed"));
        SignedManifest pause = signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of(
                Map.of("id", "pause", "expression", "false", "onFail", "PAUSE"),
                Map.of("id", "rows", "expression", "rowDelta('missing') == 0", "onFail", "DENY"),
                Map.of("id", "schema", "expression", "schemaChanged('missing') == false", "onFail", "DENY"),
                Map.of("id", "fraction", "expression", "tableFraction('missing') == 0", "onFail", "DENY"),
                Map.of("id", "arity", "expression", "tableFraction() == 0", "onFail", "DENY"))));
        assertEquals("DENY", Policy.evaluate(pause, DiffReport.empty(), List.of(pause.tool("probe"))).outcome());

        KeyPair keys = Signatures.generate();
        Map<String, java.security.PublicKey> trust = Map.of("admin", keys.getPublic());
        assertThrows(AtpException.class, () -> SignedManifest.open(JsonCodec.canonical(Map.of(
                "payload", 1, "signature", Map.of("alg", "Ed25519", "kid", "admin", "sig", "YQ=="))), trust));
        String signed = Policy.sign(DemoWorld.payload(), "admin", keys.getPrivate());
        Map<String, Object> root = JsonCodec.asMap(JsonCodec.parse(signed));
        assertThrows(AtpException.class, () -> SignedManifest.open(signed, Map.of()));
        assertThrows(AtpException.class, () -> SignedManifest.open(rewrite(root, "alg", "none"), trust));
        assertThrows(AtpException.class, () -> SignedManifest.open(rewrite(root, "sig", "%%%"), trust));
        assertThrows(AtpException.class, () -> SignedManifest.open(rewrite(root, "sig", Signatures.encode(new byte[8])), trust));
        Map<String, Object> payload = new LinkedHashMap<>(DemoWorld.payload());
        payload.remove("manifestId");
        assertThrows(AtpException.class, () -> signed(payload));
        payload.put("manifestId", "x");
        payload.put("tools", List.of());
        assertThrows(AtpException.class, () -> signed(payload));
        List<Object> tools = new ArrayList<>((List<?>) DemoWorld.payload().get("tools"));
        tools.add(tools.get(0));
        payload.put("tools", tools);
        assertThrows(AtpException.class, () -> signed(payload));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of(
                Map.of("id", "bad", "expression", "1", "onFail", "MAYBE")))));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of(
                Map.of("id", "bad", "expression", "(", "onFail", "DENY")))));
        assertThrows(AtpException.class, () -> signed(payload(List.of(badSchema()), List.of())));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "SOMETIMES", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of())));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "queue",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true)), List.of())));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "api", "nope", true)), List.of())));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), false, true)), List.of())));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("mode", "INVOKE_TOOL"), true)), List.of())));
        assertThrows(AtpException.class, () -> signed(payload(List.of(tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "(")), true)), List.of())));
        SignedManifest defaults = signed(payload(List.of(
                tool("branch", "ATOMIC_BRANCHABLE", "oltp", null, false),
                tool("wire", "IRREVERSIBLE", "api", null, true),
                tool("retry", "COMPENSATABLE", "api", Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a"), "maxRetries", 0), true)),
                List.of()));
        assertEquals("RESTORE_SNAPSHOT", defaults.tool("branch").compensation().mode());
        assertEquals("NONE", defaults.tool("wire").compensation().mode());
        assertEquals(1, defaults.tool("retry").compensation().maxRetries());
        assertEquals(null, defaults.tool("missing"));
    }

    @Test
    void irreversibleOrphanAndCompensationHolds() {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(new MemoryLog());
        TxSnapshot waiting = engine.execute("orphan-1", List.of(
                new StepRequest("send_wire", Map.of("source", "A", "amount", 1)),
                new StepRequest("capture_funds", Map.of("transfer_id", "cap"))), "because");
        assertEquals(TxState.ESCALATED_HITL, waiting.state());
        assertEquals("orphan-1", waiting.txId());
        TxSnapshot orphan = engine.resume("orphan-1", Decision.APPROVE);
        assertEquals(TxState.ESCALATED_HITL, orphan.state());
        assertTrue(orphan.orphan());
        assertNotNull(orphan.holdReason());
        assertNotNull(orphan.errorMessage());
        assertTrue(orphan.elapsedNanos() >= 0 || orphan.elapsedNanos() == 0);
        assertThrows(AtpException.class, () -> engine.abort("orphan-1"));

        world.api.register("void_credit", args -> EffectResult.fail("compensation down", false));
        TxSnapshot failed = engine.execute("comp-1", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "cap", "amount", 1)),
                new StepRequest("capture_funds", Map.of("transfer_id", "cap"))), "because");
        assertEquals(TxState.ESCALATED_HITL, failed.state());
        assertThrows(AtpException.class, () -> engine.resume("comp-1", Decision.APPROVE));
        assertThrows(AtpException.class, () -> engine.resume("comp-1", Decision.REJECT));
        assertThrows(AtpException.class, () -> engine.resume("orphan-1", Decision.RETRY));
        assertEquals(TxState.ESCALATED_HITL, engine.resume("comp-1", Decision.RETRY).state());
        assertEquals(TxState.ESCALATED_HITL, engine.abort("comp-1").state());
        assertThrows(AtpException.class, () -> engine.execute("bad id", List.of(), "x"));
        assertThrows(AtpException.class, () -> engine.status("missing"));
        assertThrows(AtpException.class, () -> engine.resume("comp-1", Decision.APPROVE));
        assertEquals(TxState.ESCALATED_HITL, engine.abort("comp-1").state());
        assertEquals(TxState.ESCALATED_HITL, engine.abort("comp-1").state());
    }

    @Test
    void mappingFailureRetryableCompensationAndUnknownRetry() {
        ApiRuntime api = new ApiRuntime();
        api.register("charge", args -> EffectResult.ok(Map.of("reservation_id", "r", "found", true), "pre", "post"));
        api.register("boom", args -> EffectResult.fail("HTTP 500", false));
        api.register("void_charge", args -> args.get("reservation_id") == null
                ? EffectResult.fail("missing", true)
                : EffectResult.ok(Map.of("found", true), "pre", "post"));
        RoutingRuntime runtime = new RoutingRuntime(new MemoryOltp(), new MemoryLakehouse(), api);
        SignedManifest manifest = signed(payload(List.of(
                tool("charge", "COMPENSATABLE", "api", Map.of("tool", "void_charge", "argumentMapping",
                        Map.of("reservation_id", "nope()"), "maxRetries", 3), true),
                tool("boom", "COMPENSATABLE", "api", Map.of("tool", "void_charge", "argumentMapping",
                        Map.of("reservation_id", "input.transfer_id")), true)), List.of()));
        TransactionEngine engine = TransactionEngine.create(new MemoryLog(), manifest, runtime);
        TxSnapshot mapped = engine.execute("map-1", List.of(
                new StepRequest("charge", Map.of("transfer_id", "c1")),
                new StepRequest("boom", Map.of("transfer_id", "b1"))), "why");
        assertEquals(TxState.ESCALATED_HITL, mapped.state());
        assertEquals(AtpErrorCode.COMPENSATION_FAILED.wireName(), mapped.errorCode());

        SignedManifest retrying = signed(payload(List.of(
                tool("charge", "COMPENSATABLE", "api", Map.of("tool", "void_charge", "argumentMapping",
                        Map.of("reservation_id", "null"), "maxRetries", 2), true),
                tool("boom", "COMPENSATABLE", "api", Map.of("tool", "void_charge", "argumentMapping",
                        Map.of("reservation_id", "input.transfer_id"), "maxRetries", 1), true)), List.of()));
        TransactionEngine retryEngine = TransactionEngine.create(new MemoryLog(), retrying, runtime);
        assertEquals(TxState.ESCALATED_HITL, retryEngine.execute("retry-1", List.of(
                new StepRequest("charge", Map.of("transfer_id", "c2")),
                new StepRequest("boom", Map.of("transfer_id", "b2"))), "why").state());

        PhasePort unknown = new ForwardingPort(null) {
            @Override
            public EffectResult shadow(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", false, "dryRun", true), "pre", "post");
            }

            @Override
            public EffectResult live(Dispatch dispatch) {
                return EffectResult.unknown("timeout");
            }

            @Override
            public EffectResult reconcile(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", false), null, null);
            }

            @Override
            public EffectResult compensate(Dispatch dispatch) {
                return EffectResult.ok(Map.of(), null, null);
            }

            @Override
            public DiffReport diff(String txId) {
                return DiffReport.empty();
            }
        };
        TransactionEngine unknownEngine = TransactionEngine.create(new MemoryLog(), DemoWorld.create().manifest,
                new RoutingRuntime(new MemoryOltp(), new MemoryLakehouse(), unknown));
        TxSnapshot held = unknownEngine.execute("unk-1", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "u", "amount", 1))), "why");
        assertEquals(TxState.ESCALATED_HITL, held.state());
        assertEquals(TxState.ESCALATED_HITL, unknownEngine.resume("unk-1", Decision.RETRY).state());
        assertThrows(AtpException.class, () -> unknownEngine.abort("unk-1"));

        PhasePort reconcileDown = new ForwardingPort(null) {
            @Override
            public EffectResult shadow(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", false), "pre", "post");
            }

            @Override
            public EffectResult live(Dispatch dispatch) {
                return EffectResult.unknown("timeout");
            }

            @Override
            public EffectResult reconcile(Dispatch dispatch) {
                return EffectResult.fail("down", false);
            }

            @Override
            public EffectResult compensate(Dispatch dispatch) {
                return EffectResult.ok(Map.of(), null, null);
            }

            @Override
            public DiffReport diff(String txId) {
                return DiffReport.empty();
            }
        };
        TransactionEngine down = TransactionEngine.create(new MemoryLog(), DemoWorld.create().manifest,
                new RoutingRuntime(new MemoryOltp(), new MemoryLakehouse(), reconcileDown));
        assertEquals(TxState.ESCALATED_HITL, down.execute("down-1", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "d", "amount", 1))), "why").state());
    }

    @Test
    void crashRecoveryDiffFailureAndJournalLengths(@TempDir Path dir) throws Exception {
        DemoWorld world = DemoWorld.create();
        CrashPort shadowCrash = new CrashPort("shadow");
        MemoryLog shadowLog = new MemoryLog();
        TransactionEngine first = TransactionEngine.create(shadowLog, world.manifest,
                new RoutingRuntime(world.oltp, world.lake, shadowCrash));
        assertThrows(Error.class, () -> first.execute("crash-shadow", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "s", "amount", 1))), "why"));
        TransactionEngine second = TransactionEngine.create(shadowLog, world.manifest,
                new RoutingRuntime(world.oltp, world.lake, shadowCrash));
        assertEquals(TxState.COMMITTED, second.status("crash-shadow").state());

        CrashPort compensateCrash = new CrashPort("compensate");
        MemoryLog compensateLog = new MemoryLog();
        TransactionEngine crashing = TransactionEngine.create(compensateLog, world.manifest,
                new RoutingRuntime(world.oltp, world.lake, compensateCrash));
        assertThrows(Error.class, () -> crashing.execute("crash-comp", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "rc", "amount", 1)),
                new StepRequest("capture_funds", Map.of("transfer_id", "cap"))), "why"));
        TransactionEngine recovered = TransactionEngine.create(compensateLog, world.manifest,
                new RoutingRuntime(world.oltp, world.lake, compensateCrash));
        assertEquals(TxState.ROLLED_BACK, recovered.status("crash-comp").state());

        PhasePort diffBoom = new ForwardingPort(world.oltp) {
            @Override
            public DiffReport diff(String txId) {
                throw new IllegalStateException("diff");
            }
        };
        TransactionEngine denied = TransactionEngine.create(new MemoryLog(), world.manifest,
                new RoutingRuntime(diffBoom, world.lake, world.api));
        TxSnapshot diff = denied.execute("diff-1", List.of(
                new StepRequest("adjust_ledger", Map.of("source", "A", "target", "B", "amount", 10, "transfer_id", "d"))), "why");
        assertEquals(TxState.ROLLED_BACK, diff.state());
        assertEquals(AtpErrorCode.POLICY_DENIED.wireName(), diff.errorCode());

        PhasePort late = new ForwardingPort(world.api) {
            @Override
            public void afterTerminal(String txId, boolean committed) {
                throw new IllegalStateException("late");
            }
        };
        TransactionEngine noisy = TransactionEngine.create(new MemoryLog(), world.manifest,
                new RoutingRuntime(world.oltp, world.lake, late));
        assertEquals(TxState.COMMITTED, noisy.execute("late-1", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "late", "amount", 1))), "why").state());
        assertTrue(noisy.droppedSignals() >= 1);

        AtomicInteger notices = new AtomicInteger();
        MemoryLog hitl = new MemoryLog();
        TransactionEngine parked = world.engine(hitl, Clock.systemUTC(), event -> {
        }, snapshot -> notices.incrementAndGet(), Runnable::run, 10_000, 30_000);
        assertEquals(TxState.ESCALATED_HITL, parked.execute("wire-hold", List.of(
                new StepRequest("send_wire", Map.of("source", "A", "amount", 1))), "why").state());
        world.engine(hitl, Clock.systemUTC(), event -> {
        }, snapshot -> notices.incrementAndGet(), Runnable::run, 10_000, 30_000);
        assertTrue(notices.get() >= 2);

        TxSnapshot injected = new TxSnapshot();
        injected.txId = "tx-injected";
        injected.planHash = "plan";
        injected.reasoningHash = Hashing.sha256("r");
        injected.state = TxState.UNKNOWN_RECONCILING;
        MemoryLog injectedLog = new MemoryLog();
        injectedLog.persist(injected.toMap(), LedgerEntry.seal(1, "tx-injected", "seed", Hashing.genesis(),
                injected.reasoningHash, "", Map.of(), null, null, "NOT_APPLICABLE", "UNKNOWN_RECONCILING"));
        TransactionEngine recoveredUnknown = world.engine(injectedLog);
        assertEquals(TxState.ESCALATED_HITL, recoveredUnknown.status("tx-injected").state());

        FileDurableLog file = new FileDurableLog(dir.resolve("one"));
        TransactionEngine filed = world.engine(file);
        filed.execute("tx-file", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "file", "amount", 1))), "why");
        file.close();
        FrameLog frames = FrameLog.open(dir.resolve("one").resolve("wal.bin"));
        frames.appendAll(List.of("S{}".getBytes()));
        frames.close();
        assertEquals(TxState.COMMITTED, world.engine(new FileDurableLog(dir.resolve("one"))).status("tx-file").state());

        FileDurableLog two = new FileDurableLog(dir.resolve("two"));
        world.engine(two).execute("tx-two", List.of(new StepRequest("reserve_credit", Map.of("transfer_id", "two", "amount", 1))), "why");
        two.close();
        FrameLog extra = FrameLog.open(dir.resolve("two").resolve("wal.bin"));
        extra.appendAll(List.of("S{}".getBytes(), "S{}".getBytes()));
        extra.close();
        assertThrows(AtpException.class, () -> world.engine(new FileDurableLog(dir.resolve("two"))));

        List<ChaosHarness.Scenario> scenarios = ChaosHarness.runAll(DemoWorld.create());
        assertTrue(ChaosHarness.csv(scenarios).contains("mode,fault"));
        assertEquals("engine", scenarios.get(0).toMap().get("mode"));
        PhasePort unstable = new ForwardingPort(null) {
            private int shadows;

            @Override
            public EffectResult shadow(Dispatch dispatch) {
                if (shadows++ == 0) {
                    return EffectResult.unknown("shadow timeout");
                }
                return EffectResult.ok(Map.of("found", true, "reservation_id", "r"), "pre", "post");
            }

            @Override
            public EffectResult live(Dispatch dispatch) {
                if ("capture_funds".equals(dispatch.tool())) {
                    return EffectResult.fail("HTTP 500", false);
                }
                throw new IllegalStateException("live blew up");
            }

            @Override
            public EffectResult compensate(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", true), "pre", "post");
            }

            @Override
            public EffectResult reconcile(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", false), null, null);
            }

            @Override
            public DiffReport diff(String txId) {
                return DiffReport.empty();
            }
        };
        TransactionEngine shadowRetry = TransactionEngine.create(new MemoryLog(), world.manifest,
                new RoutingRuntime(world.oltp, world.lake, unstable));
        assertEquals(TxState.ESCALATED_HITL, shadowRetry.execute("shadow-retry", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "sr", "amount", 1))), "why").state());
        PhasePort compensateMiss = new ForwardingPort(null) {
            private boolean failed = true;

            @Override
            public EffectResult shadow(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", false, "dryRun", true), "pre", "post");
            }

            @Override
            public EffectResult live(Dispatch dispatch) {
                if ("capture_funds".equals(dispatch.tool())) {
                    return EffectResult.fail("HTTP 500", false);
                }
                return EffectResult.ok(Map.of("found", true, "reservation_id", "r"), "pre", "post");
            }

            @Override
            public EffectResult compensate(Dispatch dispatch) {
                if (failed) {
                    failed = false;
                    throw new IllegalStateException("compensate blew up");
                }
                return EffectResult.ok(Map.of("found", true), "pre", "post");
            }

            @Override
            public EffectResult reconcile(Dispatch dispatch) {
                return EffectResult.ok(Map.of("found", false), null, null);
            }

            @Override
            public DiffReport diff(String txId) {
                return DiffReport.empty();
            }
        };
        TransactionEngine compensateRetry = TransactionEngine.create(new MemoryLog(), world.manifest,
                new RoutingRuntime(world.oltp, world.lake, compensateMiss));
        assertEquals(TxState.ROLLED_BACK, compensateRetry.execute("comp-retry", List.of(
                new StepRequest("reserve_credit", Map.of("transfer_id", "cr", "amount", 1)),
                new StepRequest("capture_funds", Map.of("transfer_id", "cr"))), "why").state());
        assertEquals(TxState.COMMITTED, filed.status("tx-file").state());
    }

    private static SignedManifest signed(Map<String, Object> payload) {
        KeyPair keys = Signatures.generate();
        return SignedManifest.open(Policy.sign(payload, "admin", keys.getPrivate()), Map.of("admin", keys.getPublic()));
    }

    private static Map<String, Object> payload(List<Object> tools, List<Object> rules) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manifestId", "coverage");
        payload.put("tools", tools);
        payload.put("rules", rules);
        return payload;
    }

    private static Map<String, Object> tool(String name, String reversibility, String adapter, Object compensation, boolean example) {
        return tool(name, reversibility, adapter, compensation, example, true);
    }

    private static Map<String, Object> tool(String name, String reversibility, String adapter, Object compensation, boolean example, boolean dry) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", true);
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("reversibility", reversibility);
        tx.put("adapter", adapter);
        tx.put("idempotencyKeyPath", "$.input.nested.id");
        tx.put("dryRunSupported", dry);
        if (compensation != null) {
            tx.put("compensation", compensation);
        }
        if (example) {
            tx.put("dryRunExample", Map.of("ok", true));
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("inputSchema", schema);
        tool.put("transaction", tx);
        return tool;
    }

    private static Map<String, Object> badSchema() {
        Map<String, Object> tool = tool("probe", "COMPENSATABLE", "api",
                Map.of("tool", "void", "argumentMapping", Map.of("a", "input.a")), true);
        tool.put("inputSchema", Map.of("type", "string"));
        return tool;
    }

    @SuppressWarnings("unchecked")
    private static String rewrite(Map<String, Object> root, String field, String value) {
        Map<String, Object> copy = JsonCodec.asMap(JsonCodec.parse(JsonCodec.canonical(root)));
        Map<String, Object> signature = new LinkedHashMap<>((Map<String, Object>) copy.get("signature"));
        signature.put(field, value);
        copy.put("signature", signature);
        return JsonCodec.canonical(copy);
    }

    private static class ForwardingPort implements PhasePort {
        private final PhasePort inner;

        ForwardingPort(PhasePort inner) {
            this.inner = inner;
        }

        @Override
        public EffectResult shadow(Dispatch dispatch) {
            return inner.shadow(dispatch);
        }

        @Override
        public EffectResult live(Dispatch dispatch) {
            return inner.live(dispatch);
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
    }

    private static final class CrashPort implements PhasePort {
        private final String phase;
        private boolean crashed;
        private EffectResult remembered = EffectResult.ok(Map.of("found", true, "reservation_id", "r"), "pre", "post");
        private final ApiRuntime api = new ApiRuntime();

        CrashPort(String phase) {
            this.phase = phase;
            api.register("reserve_credit", args -> EffectResult.ok(Map.of("reservation_id", "r", "found", true), "pre", "post"));
            api.register("capture_funds", args -> EffectResult.fail("HTTP 500", false));
            api.register("void_credit", args -> EffectResult.ok(Map.of("found", true), "pre", "post"));
        }

        @Override
        public EffectResult shadow(Dispatch dispatch) {
            if ("shadow".equals(phase) && !crashed) {
                crashed = true;
                throw new Error("crash");
            }
            return api.shadow(dispatch);
        }

        @Override
        public EffectResult live(Dispatch dispatch) {
            return api.live(dispatch);
        }

        @Override
        public EffectResult compensate(Dispatch dispatch) {
            if ("compensate".equals(phase) && !crashed) {
                crashed = true;
                throw new Error("crash");
            }
            return api.compensate(dispatch);
        }

        @Override
        public EffectResult reconcile(Dispatch dispatch) {
            if (crashed) {
                return remembered;
            }
            return api.reconcile(dispatch);
        }

        @Override
        public DiffReport diff(String txId) {
            return api.diff(txId);
        }
    }
}
