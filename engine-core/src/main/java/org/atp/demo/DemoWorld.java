// SPDX-License-Identifier: Apache-2.0
package org.atp.demo;

import org.atp.adapter.ApiRuntime;
import org.atp.adapter.MemoryLakehouse;
import org.atp.adapter.MemoryOltp;
import org.atp.adapter.RoutingRuntime;
import org.atp.crypto.Signatures;
import org.atp.effect.Effects.EffectResult;
import org.atp.engine.TransactionEngine;
import org.atp.log.DurableLog;
import org.atp.model.Types.StepRequest;
import org.atp.otel.Telemetry.ApprovalSink;
import org.atp.otel.Telemetry.SpanRecorder;
import org.atp.policy.Policy;
import org.atp.policy.Policy.SignedManifest;

import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/** Reference three-system world: SQL ledger, lakehouse stock, and an external API. */
public final class DemoWorld {
    public final KeyPair keys;
    public final SignedManifest manifest;
    public final MemoryOltp oltp = new MemoryOltp();
    public final MemoryLakehouse lake = new MemoryLakehouse();
    public final ApiRuntime api = new ApiRuntime();
    public final RoutingRuntime runtime;

    private DemoWorld(KeyPair keys, SignedManifest manifest) {
        this.keys = keys;
        this.manifest = manifest;
        this.runtime = new RoutingRuntime(oltp, lake, api);
        oltp.seed(Map.of(
                "A", new BigDecimal("1000"),
                "B", new BigDecimal("1000"),
                "C", new BigDecimal("500"),
                "D", new BigDecimal("50")));
        List<Map<String, Object>> stock = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            stock.add(Map.of("sku", "SKU-" + i, "qty", new BigDecimal("10")));
        }
        lake.seed("warehouse_stock", List.of("sku", "qty"), stock);
        api.register("dispatch_carrier", args -> EffectResult.fail("HTTP 500 from carrier", false));
        api.register("get_dispatch_status", args -> EffectResult.ok(Map.of(
                "found", api.seen("dispatch_carrier", String.valueOf(args.get("idempotency_key")))), null, null));
        api.register("send_wire", args -> EffectResult.ok(Map.of("wireId", "wire-" + args.get("idempotency_key"), "found", true),
                "api:none", "api:wire"));
        api.register("reserve_credit", args -> EffectResult.ok(Map.of(
                "reservation_id", "r-" + args.get("transfer_id"), "found", true), "api:none", "api:reserved"));
        api.register("void_credit", args -> {
            if (args.get("reservation_id") == null) {
                return EffectResult.fail("missing reservation", true);
            }
            return EffectResult.ok(Map.of("voided", args.get("reservation_id"), "found", true), "api:reserved", "api:void");
        });
        api.register("capture_funds", args -> EffectResult.fail("HTTP 500 capture", false));
    }

    public static DemoWorld create() {
        KeyPair keys = Signatures.generate();
        return new DemoWorld(keys, SignedManifest.open(document(keys.getPrivate(), "demo-admin"), Map.of("demo-admin", keys.getPublic())));
    }

    public TransactionEngine engine(DurableLog log) {
        return TransactionEngine.create(log, manifest, runtime);
    }

    public TransactionEngine engine(DurableLog log, Clock clock, SpanRecorder spans, ApprovalSink approvals,
                                    Executor analytics, int maxSteps, long waitMillis) {
        return new TransactionEngine(log, clock, manifest, runtime, spans, approvals, analytics, maxSteps, waitMillis);
    }

    public Map<String, PublicKey> trust() {
        return Map.of("demo-admin", keys.getPublic());
    }

    public static List<StepRequest> rollbackPlan() {
        return List.of(
                new StepRequest("adjust_ledger", Map.of("source", "A", "target", "B", "amount", 100, "transfer_id", "t-1")),
                new StepRequest("move_stock", Map.of("sku", "SKU-1", "qty", 2)),
                new StepRequest("dispatch_carrier", Map.of("shipment_id", "s-1")));
    }

    public static String document(PrivateKey privateKey, String kid) {
        return Policy.sign(payload(), kid, privateKey);
    }

    public static Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("manifestId", "demo-v1");
        payload.put("tools", List.of(
                tool("adjust_ledger", "Move funds between ledger accounts", "ATOMIC_BRANCHABLE", "oltp", "customer_ledger",
                        Map.of("source", str(), "target", str(), "amount", num(), "transfer_id", str()),
                        List.of("source", "target", "amount", "transfer_id"),
                        Map.of("mode", "RESTORE_SNAPSHOT"), List.of("amount"), "$.input.transfer_id", null, null),
                tool("move_stock", "Adjust a warehouse stock row on a catalog branch", "ATOMIC_BRANCHABLE", "lakehouse",
                        "warehouse_stock", Map.of("sku", str(), "qty", num()), List.of("sku", "qty"),
                        Map.of("mode", "RESTORE_SNAPSHOT"), List.of(), null, null, null),
                tool("dispatch_carrier", "Hand a shipment to the carrier", "COMPENSATABLE", "api", "shipments",
                        Map.of("shipment_id", str()), List.of("shipment_id"),
                        Map.of("tool", "void_dispatch", "argumentMapping", Map.of("shipment_id", "input.shipment_id"), "maxRetries", 3),
                        List.of(), null, "get_dispatch_status", Map.of("accepted", true)),
                tool("send_wire", "Irreversible wire transfer", "IRREVERSIBLE", "api", "wires",
                        Map.of("source", str(), "amount", num()), List.of("source", "amount"),
                        Map.of("mode", "NONE"), List.of("amount"), null, null, Map.of("wireId", "dry")),
                tool("reserve_credit", "Reserve a credit line", "COMPENSATABLE", "api", "credit",
                        Map.of("transfer_id", str(), "amount", num()), List.of("transfer_id", "amount"),
                        Map.of("tool", "void_credit", "argumentMapping", Map.of(
                                "reservation_id", "output.reservation_id",
                                "transfer_id", "'comp_' + input.transfer_id"), "maxRetries", 4),
                        List.of("amount"), "$.input.transfer_id", null, Map.of("reservation_id", "dry")),
                tool("capture_funds", "Capture a reservation", "COMPENSATABLE", "api", "credit",
                        Map.of("transfer_id", str()), List.of("transfer_id"),
                        Map.of("tool", "void_capture", "argumentMapping", Map.of("transfer_id", "input.transfer_id"), "maxRetries", 2),
                        List.of(), null, null, Map.of("captured", false))));
        payload.put("rules", List.of(
                Map.of("id", "ledger-fraction", "expression", "tableFraction('customer_ledger') <= 0.75", "onFail", "DENY"),
                Map.of("id", "financial-delta", "expression", "financialDelta <= 1000", "onFail", "DENY")));
        return payload;
    }

    private static Map<String, Object> tool(String name, String description, String reversibility, String adapter, String table,
                                            Map<String, Object> properties, List<String> required, Map<String, Object> compensation,
                                            List<String> money, String keyPath, String reconcile, Map<String, Object> example) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("reversibility", reversibility);
        tx.put("adapter", adapter);
        tx.put("table", table);
        tx.put("blastRadiusTier", "HIGH");
        tx.put("dryRunSupported", true);
        tx.put("compensation", compensation);
        tx.put("moneyColumns", money);
        tx.put("forwardRetries", 2);
        if (keyPath != null) {
            tx.put("idempotencyKeyPath", keyPath);
        }
        if (reconcile != null) {
            tx.put("reconciliationTool", reconcile);
        }
        if (example != null) {
            tx.put("dryRunExample", example);
        }
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        tool.put("transaction", tx);
        return tool;
    }

    private static Map<String, Object> str() {
        return Map.of("type", "string", "minLength", 1);
    }

    private static Map<String, Object> num() {
        return Map.of("type", "number", "minimum", 0);
    }
}
