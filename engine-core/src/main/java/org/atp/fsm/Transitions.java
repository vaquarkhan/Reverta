// SPDX-License-Identifier: Apache-2.0
package org.atp.fsm;

import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;
import org.atp.model.Types.TxState;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** Explicit saga edges. Anything not listed here is a defect, not a policy choice. */
public final class Transitions {
    private static final Map<TxState, Set<TxState>> EDGES = new EnumMap<>(TxState.class);

    static {
        allow(TxState.INIT, TxState.SHADOW_EXECUTING, TxState.ROLLED_BACK);
        allow(TxState.SHADOW_EXECUTING, TxState.POLICY_EVAL, TxState.UNKNOWN_RECONCILING, TxState.COMPENSATING, TxState.ESCALATED_HITL);
        allow(TxState.POLICY_EVAL, TxState.COMMITTING, TxState.COMPENSATING, TxState.ESCALATED_HITL);
        allow(TxState.COMMITTING, TxState.COMMITTED, TxState.UNKNOWN_RECONCILING, TxState.COMPENSATING, TxState.ESCALATED_HITL);
        allow(TxState.UNKNOWN_RECONCILING, TxState.SHADOW_EXECUTING, TxState.COMMITTING, TxState.COMPENSATING,
                TxState.ESCALATED_HITL, TxState.COMMITTED, TxState.POLICY_EVAL);
        allow(TxState.COMPENSATING, TxState.ROLLED_BACK, TxState.ESCALATED_HITL, TxState.UNKNOWN_RECONCILING);
        allow(TxState.ESCALATED_HITL, TxState.POLICY_EVAL, TxState.COMMITTING, TxState.COMPENSATING,
                TxState.ROLLED_BACK, TxState.UNKNOWN_RECONCILING);
        allow(TxState.COMMITTED);
        allow(TxState.ROLLED_BACK);
    }

    private Transitions() {
    }

    private static void allow(TxState from, TxState... to) {
        EDGES.put(from, to.length == 0 ? Set.of() : EnumSet.copyOf(java.util.Arrays.asList(to)));
    }

    public static Set<TxState> edges(TxState from) {
        return Set.copyOf(EDGES.get(from));
    }

    public static void require(TxState from, TxState to) {
        if (from == to) {
            return;
        }
        if (!EDGES.get(from).contains(to)) {
            throw new AtpException(AtpErrorCode.INVALID, "illegal transition " + from + " -> " + to);
        }
    }

    public static boolean terminal(TxState state) {
        return state == TxState.COMMITTED || state == TxState.ROLLED_BACK;
    }
}
