# RFC-0001: Agent Transaction Protocol

Status: v1.0, implemented by this repository.
Extension identifier: `org.agenttxprotocol/tx`
Base protocol: Model Context Protocol 2026-07-28 (stateless JSON-RPC 2.0, JSON Schema 2020-12).

MCP standardizes tool input, output, and execution. It does not define undo, saga state, or idempotent mutation. This document is an additive extension. A client that ignores it continues to work against a non-transactional server. A transactional proxy enforces the extension with a signed manifest.

## 1. Advertised tool metadata

`tools/list` returns each tool with:

```json
"_meta": {
  "org.agenttxprotocol/transaction": { }
}
```

`x-atp-transaction` is a compatibility alias for humans and for catalogs. The proxy copies the signed definition into `_meta`. It does not authorize a call from either annotation. The signed manifest is the only source of reversibility, compensation, and policy.

## 2. Signed manifest

A manifest document is `{ "payload": { ... }, "signature": { "alg": "Ed25519", "kid": "...", "sig": "<base64>" } }`. The signature is Ed25519 over the canonical JSON of `payload`. Canonical JSON sorts object keys by UTF-16 code unit, emits no insignificant whitespace, and renders numbers with `BigDecimal` so `10` is not `1E+1`.

`payload` contains:

- `manifestId` (string)
- `tools` (non-empty array)
- `rules` (optional array)

Each tool contains `name`, `description`, `inputSchema` (a JSON Schema object whose `type` is `object`), and `transaction`:

| Field | Required | Meaning |
| --- | --- | --- |
| `reversibility` | yes | `ATOMIC_BRANCHABLE`, `COMPENSATABLE`, or `IRREVERSIBLE` |
| `adapter` | yes | `oltp`, `lakehouse`, or `api` |
| `compensation` | required for `COMPENSATABLE` | mode, tool, argument mapping, retries, failure policy |
| `table` | no | blast-radius table; defaults to the tool name |
| `blastRadiusTier` | no | defaults to `MEDIUM` |
| `dryRunSupported` | no | defaults to true unless the boolean `false` |
| `dryRunExample` | required for an `api` tool with dry-run enabled | object returned during shadow, without calling the handler |
| `moneyColumns` | no | argument names summed into `financialDelta` |
| `idempotencyKeyPath` | no | business key recorded beside the engine key. One path segment after an optional `$.` or `input.` prefix |
| `reconciliationTool` | no | read-only tool used when an outcome is unknown |
| `forwardRetries` | no | defaults to 2, minimum 1 |

`compensation.argumentMapping` values are expressions in the subset in section 6. They may read `input`, `output`, and `tx.id`. `compensation.mode` defaults to `RESTORE_SNAPSHOT` for a branchable tool and `INVOKE_TOOL` otherwise. `maxRetries` defaults to 5 and is at least 1. `onCompensationFailure` defaults to `ESCALATE_HITL`.

A `COMPENSATABLE` tool whose mapping is missing a tool name or is empty is rejected. An unknown `kid`, a bad signature, a duplicate tool name, a bad adapter, or a rule whose `onFail` is neither `DENY` nor `PAUSE` is rejected. The manifest does not open.

## 3. Idempotency

The engine computes the outbound key after schema validation and before the call:

```text
idempotency_key = txId + ":" + stepIndex + ":" + sha256(canonical(arguments)).substring(0, 32)
```

`arguments` is the caller object, without the injected key. The key is then added as `idempotency_key`, so a schema with `additionalProperties: false` still accepts the caller's payload. `idempotencyKeyPath` is stored as a business key. It is not the engine key.

A compensation call uses `comp:` + the forward key + `:` + the attempt number, and that value overwrites any mapped `idempotency_key`.

The client supplies `txId`, 1 to 80 characters from `[A-Za-z0-9_.:-]`. The same transaction id and the same plan hash returns the recorded result. The same id with a different plan hash is an idempotency conflict.

## 4. Saga states

Every transaction is one of these nine states:

`INIT`, `SHADOW_EXECUTING`, `POLICY_EVAL`, `COMMITTING`, `COMMITTED`, `UNKNOWN_RECONCILING`, `COMPENSATING`, `ROLLED_BACK`, `ESCALATED_HITL`.

`COMMITTED` and `ROLLED_BACK` are terminal. The legal edges are those enforced by `org.atp.fsm.Transitions`. A self-edge is allowed. Any other edge is `AtpInvalidTransaction`.

A timeout or a lost response is not folded into success or failure. The coordinator writes the intent to the write-ahead log before the external call. If the process dies, recovery moves that transaction to `UNKNOWN_RECONCILING` and asks the reconciliation tool, or the idempotency store, whether the effect exists.

- Found: treat the original phase as succeeded.
- Not found, and attempts remain: retry.
- Otherwise: `ESCALATED_HITL` with hold `UNKNOWN`.

HTTP 500 is a definite failure. HTTP 429 is a retryable failure. A silent timeout after the handler ran is unknown, so reconciliation can observe the effect and will not apply it twice.

## 5. Shadow, policy, commit, rollback

`ATOMIC_BRANCHABLE` tools mutate an isolated branch during shadow. `api` tools return `dryRunExample` during shadow and call the handler only on the live phase.

The policy gate runs on the diff before commit. `tableFraction(table)`, `schemaChanged(table)`, `rowDelta(table)`, and `financialDelta` are available. A rule that does not return a boolean denies. An evaluation error denies. Any `DENY` wins over any `PAUSE`. After an allow, an `IRREVERSIBLE` step still pauses for a human. A policy deny does not ask a human.

`APPROVE` is valid for an approval hold and moves the transaction to `COMMITTING`. `REJECT` is valid for an approval hold and starts compensation. `RETRY` is valid for an unknown hold or a compensation-failure hold. Abort of a terminal transaction returns the snapshot. Abort of an unknown or orphaned irreversible effect refuses to guess.

Rollback walks completed steps in reverse topological order. A live branch is restored or the mapped inverse tool is invoked. A live irreversible step has no inverse: it is marked orphaned and the transaction ends in `ESCALATED_HITL` with `AtpIrreversibleBlocked`. A failed live call that never applied is discarded, not inverted.

If no step declares `dependsOn`, the plan is a chain. If any step declares dependencies, every dependency is explicit. A cycle rolls the transaction back from `INIT`.

## 6. Expression subset

The policy and mapping language is an in-process subset. It has literals, dotted paths, calls, `+ - * /`, comparisons, `&&`, `||`, `!`, and parentheses. `&&` and `||` short-circuit. String `+` concatenates. Division uses scale 8, half up. Builtins are `abs`, `len`, `str`, and `coalesce`. There is no I/O.

## 7. JSON-RPC

MCP methods and `tx/*` methods require `_meta`:

- `io.modelcontextprotocol/protocolVersion` = `2026-07-28`
- `io.modelcontextprotocol/clientCapabilities` present

Missing either is JSON-RPC `-32602`. Parse failures are `-32700`. A request that is not a JSON-RPC 2.0 object is `-32600`. An unknown method is `-32601`.

| Method | Behavior |
| --- | --- |
| `server/discover` | Advertises extension `org.agenttxprotocol/tx` version `1.0.0` |
| `tools/list` | Tools from the signed manifest |
| `tools/call` | One-step `tx/execute`. `isError` is false. The snapshot is `structuredContent` |
| `tx/execute` | Runs a plan |
| `tx/resume` | `APPROVE`, `REJECT`, or `RETRY` |
| `tx/abort` | Aborts when the state allows it |
| `tx/status` | Returns the snapshot |
| `tx/replay` | Requires `sandbox: true` |
| `tx/ledger` | Returns the hash chain |

A completed rollback is a successful JSON-RPC result whose snapshot state is `ROLLED_BACK`. Application failures use the codes below, outside the MCP range `-32000` through `-32099`.

| Code | Name |
| --- | --- |
| -31801 | AtpInvalidTransaction |
| -31802 | AtpPolicyDenied |
| -31803 | AtpUnknownOutcome |
| -31804 | AtpCompensationFailed |
| -31805 | AtpIrreversibleBlocked |
| -31806 | AtpReplayDivergence |
| -31807 | AtpIdempotencyConflict |
| -31808 | AtpApprovalRequired |
| -31809 | AtpManifestRejected |
| -31810 | AtpArgumentInvalid |
| -31811 | AtpToolUnregistered |
| -31812 | AtpLedgerTampered |

Argument validation uses a JSON Schema 2020-12 profile: type, enum, length, numeric bounds, array bounds, items, required, and additionalProperties. The idempotency key is injected after validation.

## 8. Ledger and replay

Each entry stores `seq`, `txId`, `stepId`, `prevEntrySha256`, `agentReasoningHash`, `toolName`, `sanitizedInput`, `preStatePointer`, `postStatePointer`, `compensationStatus`, `state`, and `entryHash`. The hash is SHA-256 of the canonical body. The genesis previous hash is 64 zero characters. Raw agent reasoning is not stored.

Keys matching `password`, `secret`, `token`, `authorization`, `api_key`, `ssn`, `card`, `cvv`, or `credential` are redacted to `[REDACTED]` before the entry is sealed.

The anchor records the latest hash. A chain that matches the anchor is `MATCH`. A prefix of a longer chain, or a non-empty chain with no anchor, is `HEALED` and is the crash window between the frame fsync and the anchor write. A broken hash, a broken previous link, or an anchor that is not in the chain is `AtpLedgerTampered`. Truncating both the log and the anchor cannot be detected. The chain is tamper-evident.

`tx-engine replay <tx_id> --up-to-step N --sandbox` walks the recorded entries and checks the state edges. `--sandbox` is mandatory. Replay does not call live endpoints. `--up-to-step` is 1-based. A value of 0 replays the whole transaction. The walk stops before compensation entries when a step limit is set.

## 9. Transport

The reference proxy accepts `POST /mcp`. If the `Accept` header contains `text/event-stream`, the response is one Server-Sent Event. Otherwise it is `application/json`. STDIO is one JSON object per line. There is no MCP `initialize` handshake: the 2026-07-28 profile is stateless, and every request carries the protocol version.

## 10. What this reference engine is

The coordinator is a Java 21 library with a file write-ahead log and a JDBC write-ahead log. Policy evaluation is the in-process subset above. The in-memory lakehouse is a copy-on-write snapshot with a pointer swing, which is the reference shape of write-audit-publish. `NessieClient` creates, merges, and deletes Nessie v2 branches. `NeonClient` creates and deletes Neon branches. SQL for a Neon-backed transaction still runs on the configured JDBC URL. The relational shadow that executes SQL is a schema-per-transaction branch on H2 or PostgreSQL.
