// SPDX-License-Identifier: Apache-2.0
package org.atp.engine;

import org.atp.adapter.MemoryLakehouse;
import org.atp.demo.DemoWorld;
import org.atp.log.MemoryLog;
import org.atp.model.Types.StepRequest;
import org.atp.model.Types.TxState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SagaTest {
    @Test
    void threeStepFailureRollsBackLedgerAndLakehouse() {
        DemoWorld world = DemoWorld.create();
        MemoryLakehouse.Snapshot before = world.lake.main("warehouse_stock");
        TransactionEngine engine = world.engine(new MemoryLog());
        long start = System.nanoTime();
        TxSnapshot snap = engine.execute("tx-demo-1", DemoWorld.rollbackPlan(), "ship the transfer");
        long elapsed = System.nanoTime() - start;
        assertEquals(TxState.ROLLED_BACK, snap.state());
        assertTrue(elapsed < 1_000_000_000L, "rollback took " + elapsed);
        assertEquals(new BigDecimal("1000"), world.oltp.main().get("A"));
        assertEquals(new BigDecimal("1000"), world.oltp.main().get("B"));
        assertSame(before, world.lake.main("warehouse_stock"));
        assertFalse(engine.ledger().isEmpty());
        assertTrue(snap.elapsedNanos() >= 0);
    }

    @Test
    void irreversibleToolWaitsForApproval() {
        DemoWorld world = DemoWorld.create();
        TransactionEngine engine = world.engine(new MemoryLog());
        TxSnapshot held = engine.execute("tx-wire-1", java.util.List.of(
                new StepRequest("send_wire", Map.of("source", "A", "amount", 10))), "wire");
        assertEquals(TxState.ESCALATED_HITL, held.state());
        TxSnapshot committed = engine.resume("tx-wire-1", org.atp.model.Types.Decision.APPROVE);
        assertEquals(TxState.COMMITTED, committed.state());
        assertEquals(1, world.api.applications("send_wire"));
    }
}
