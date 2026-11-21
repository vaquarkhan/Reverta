// SPDX-License-Identifier: Apache-2.0
package org.atp.log;

import org.atp.json.JsonCodec;
import org.atp.ledger.LedgerEntry;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FileDurableLog implements DurableLog, AutoCloseable {
    private final FrameLog frames;
    private final Path anchorPath;
    private boolean writeAnchor = true;

    public FileDurableLog(Path directory) {
        this.frames = FrameLog.open(directory.resolve("wal.bin"));
        this.anchorPath = directory.resolve("wal.anchor");
    }

    /** Test hook: simulate a crash between the frame fsync and the anchor update. */
    public void skipAnchorWrites(boolean skip) {
        this.writeAnchor = !skip;
    }

    @Override
    public void persist(Map<String, Object> snapshot, LedgerEntry entry) {
        byte[] snap = ("S" + JsonCodec.canonical(snapshot)).getBytes(StandardCharsets.UTF_8);
        byte[] led = ("L" + JsonCodec.canonical(entry.toMap())).getBytes(StandardCharsets.UTF_8);
        frames.appendAll(List.of(snap, led));
        if (writeAnchor) {
            writeAnchor(entry.entryHash);
        }
    }

    @Override
    public Loaded load() {
        List<Map<String, Object>> snapshots = new ArrayList<>();
        List<LedgerEntry> ledger = new ArrayList<>();
        for (byte[] frame : frames.readAll()) {
            String text = new String(frame, StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                continue;
            }
            char kind = text.charAt(0);
            String json = text.substring(1);
            if (kind == 'S') {
                snapshots.add(JsonCodec.asMap(JsonCodec.parse(json)));
            } else if (kind == 'L') {
                ledger.add(LedgerEntry.fromMap(JsonCodec.asMap(JsonCodec.parse(json))));
            } else {
                throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "unknown WAL frame");
            }
        }
        String anchor = null;
        if (Files.exists(anchorPath)) {
            try {
                anchor = Files.readString(anchorPath).trim();
            } catch (IOException ex) {
                throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "anchor unreadable");
            }
        }
        return new Loaded(snapshots, ledger, anchor);
    }

    @Override
    public void anchor(String tip) {
        writeAnchor(tip);
    }

    public void writeAnchor(String tip) {
        try {
            Files.writeString(anchorPath, tip);
        } catch (IOException ex) {
            throw new AtpException(AtpErrorCode.INVALID, "anchor write failed: " + ex.getMessage());
        }
    }

    @Override
    public void close() {
        frames.close();
    }
}
