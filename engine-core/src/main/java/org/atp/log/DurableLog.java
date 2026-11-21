// SPDX-License-Identifier: Apache-2.0
package org.atp.log;

import org.atp.ledger.LedgerEntry;

import java.util.List;
import java.util.Map;

/** Synchronous write-ahead log. One persist call fsyncs the snapshot and its ledger entry together. */
public interface DurableLog {
    void persist(Map<String, Object> snapshot, LedgerEntry entry);

    void anchor(String tip);

    Loaded load();

    record Loaded(List<Map<String, Object>> snapshots, List<LedgerEntry> ledger, String anchor) {
    }
}
