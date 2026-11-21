// SPDX-License-Identifier: Apache-2.0
package org.atp.ledger;

import org.atp.crypto.Hashing;
import org.atp.json.JsonCodec;
import org.atp.model.Types.AtpErrorCode;
import org.atp.model.Types.AtpException;

import java.util.LinkedHashMap;
import java.util.Map;

public final class LedgerEntry {
    public final long seq;
    public final String txId;
    public final String stepId;
    public final String prevEntrySha256;
    public final String agentReasoningHash;
    public final String toolName;
    public final Object sanitizedInput;
    public final String preStatePointer;
    public final String postStatePointer;
    public final String compensationStatus;
    public final String state;
    public final String entryHash;

    private LedgerEntry(long seq, String txId, String stepId, String prev, String reasoning, String tool,
                         Object input, String pre, String post, String compensation, String state, String hash) {
        this.seq = seq;
        this.txId = txId;
        this.stepId = stepId;
        this.prevEntrySha256 = prev;
        this.agentReasoningHash = reasoning;
        this.toolName = tool;
        this.sanitizedInput = input;
        this.preStatePointer = pre;
        this.postStatePointer = post;
        this.compensationStatus = compensation;
        this.state = state;
        this.entryHash = hash;
    }

    public static LedgerEntry seal(long seq, String txId, String stepId, String prev, String reasoningHash,
                                   String tool, Object sanitizedInput, String pre, String post,
                                   String compensationStatus, String state) {
        LedgerEntry unsigned = new LedgerEntry(seq, txId, stepId, prev, reasoningHash, tool, sanitizedInput,
                pre, post, compensationStatus, state, "");
        String hash = Hashing.sha256(JsonCodec.canonical(unsigned.body()));
        return new LedgerEntry(seq, txId, stepId, prev, reasoningHash, tool, sanitizedInput, pre, post,
                compensationStatus, state, hash);
    }

    public Map<String, Object> body() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("seq", seq);
        map.put("txId", txId);
        map.put("stepId", stepId);
        map.put("prevEntrySha256", prevEntrySha256);
        map.put("agentReasoningHash", agentReasoningHash);
        map.put("toolName", toolName);
        map.put("sanitizedInput", sanitizedInput);
        map.put("preStatePointer", preStatePointer);
        map.put("postStatePointer", postStatePointer);
        map.put("compensationStatus", compensationStatus);
        map.put("state", state);
        return map;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = body();
        map.put("entryHash", entryHash);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static LedgerEntry fromMap(Map<String, Object> map) {
        return new LedgerEntry(
                ((Number) map.get("seq")).longValue(),
                String.valueOf(map.get("txId")),
                String.valueOf(map.get("stepId")),
                String.valueOf(map.get("prevEntrySha256")),
                String.valueOf(map.get("agentReasoningHash")),
                String.valueOf(map.get("toolName")),
                map.get("sanitizedInput"),
                map.get("preStatePointer") == null ? null : String.valueOf(map.get("preStatePointer")),
                map.get("postStatePointer") == null ? null : String.valueOf(map.get("postStatePointer")),
                String.valueOf(map.get("compensationStatus")),
                String.valueOf(map.get("state")),
                String.valueOf(map.get("entryHash")));
    }

    public void verifyHash() {
        String recomputed = Hashing.sha256(JsonCodec.canonical(body()));
        if (!recomputed.equals(entryHash)) {
            throw new AtpException(AtpErrorCode.LEDGER_TAMPERED, "ledger hash mismatch at seq " + seq);
        }
    }
}
