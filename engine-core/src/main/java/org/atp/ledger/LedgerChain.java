// SPDX-License-Identifier: Apache-2.0
package org.atp.ledger;

import org.atp.crypto.Hashing;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.util.List;

public final class LedgerChain {
    public enum AnchorStatus {ABSENT, MATCH, HEALED}

    private LedgerChain() {
    }

    public static AnchorStatus check(List<LedgerEntry> entries, String anchor) {
        String prev = Hashing.genesis();
        for (LedgerEntry entry : entries) {
            entry.verifyHash();
            if (!prev.equals(entry.prevEntrySha256)) {
                throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "prev hash break at seq " + entry.seq);
            }
            prev = entry.entryHash;
        }
        if (anchor == null || anchor.isBlank()) {
            return entries.isEmpty() ? AnchorStatus.ABSENT : AnchorStatus.HEALED;
        }
        if (entries.isEmpty()) {
            throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "anchor without ledger");
        }
        boolean found = false;
        for (LedgerEntry entry : entries) {
            if (anchor.equals(entry.entryHash)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "anchor is not in the chain");
        }
        return anchor.equals(entries.get(entries.size() - 1).entryHash) ? AnchorStatus.MATCH : AnchorStatus.HEALED;
    }
}
