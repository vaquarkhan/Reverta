// SPDX-License-Identifier: Apache-2.0
package org.atp.replay;

import org.atp.fsm.Transitions;
import org.atp.ledger.LedgerChain;
import org.atp.ledger.LedgerEntry;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.TxState;

import java.util.ArrayList;
import java.util.List;

/** Offline replay. Recorded ledger entries are the only inputs; live endpoints are never called. */
public final class ReplayEngine {
    private ReplayEngine() {
    }

    public record Replay(String txId, int upToStep, boolean sandbox, List<LedgerEntry> timeline, TxState finalState) {
    }

    public static Replay replay(List<LedgerEntry> ledger, String txId, int upToStep, boolean sandbox) {
        if (!sandbox) {
            throw new AtpException(AtpErrorCode.INVALID, "live replay is not permitted");
        }
        LedgerChain.check(ledger, null);
        List<LedgerEntry> selected = new ArrayList<>();
        TxState previous = TxState.INIT;
        int maxIndex = upToStep <= 0 ? Integer.MAX_VALUE : upToStep - 1;
        for (LedgerEntry entry : ledger) {
            if (!txId.equals(entry.txId)) {
                continue;
            }
            if (upToStep > 0 && entry.stepId.startsWith("compensate")) {
                break;
            }
            Integer index = stepIndex(entry.stepId);
            if (index != null && index > maxIndex) {
                break;
            }
            TxState state = TxState.valueOf(entry.state);
            try {
                Transitions.require(previous, state);
            } catch (AtpException ex) {
                throw new AtpException(AtpErrorCode.REPLAY_DIVERGENCE, previous + " -> " + state);
            }
            selected.add(entry);
            previous = state;
        }
        if (selected.isEmpty()) {
            throw new AtpException(AtpErrorCode.INVALID, "no ledger entries for " + txId);
        }
        return new Replay(txId, upToStep, true, List.copyOf(selected), previous);
    }

    public static Integer stepIndex(String stepId) {
        if (stepId == null || !stepId.startsWith("step-")) {
            return null;
        }
        int dash = stepId.indexOf('-', 5);
        String number = dash < 0 ? stepId.substring(5) : stepId.substring(5, dash);
        try {
            return Integer.valueOf(number);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
