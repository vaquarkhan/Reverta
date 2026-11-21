// SPDX-License-Identifier: Apache-2.0
package org.atp.log;

import org.atp.ledger.LedgerEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class MemoryLog implements DurableLog {
    private final List<Map<String, Object>> snapshots = new ArrayList<>();
    private final List<LedgerEntry> ledger = new ArrayList<>();
    private String anchor;

    @Override
    public synchronized void persist(Map<String, Object> snapshot, LedgerEntry entry) {
        snapshots.add(flatten(snapshot));
        ledger.add(entry);
        anchor = entry.entryHash;
    }

    @Override
    public synchronized void anchor(String tip) {
        this.anchor = tip;
    }

    @Override
    public synchronized Loaded load() {
        return new Loaded(List.copyOf(snapshots), List.copyOf(ledger), anchor);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flatten(Map<String, Object> snapshot) {
        return (Map<String, Object>) org.atp.json.JsonCodec.parse(org.atp.json.JsonCodec.canonical(snapshot));
    }
}
