# Technical requirements

This document records the requirements, the research that fixed the stack, and where each requirement is implemented and tested.

## Research findings

MCP 2026-07-28 is stateless. Tool calls use JSON Schema 2020-12. Annotations such as `readOnlyHint` and `idempotentHint` are untrusted hints. A lost `tools/call` response can be retried by the client and can mutate twice, because the base protocol has no idempotency store and no compensation model. That is the gap this engine fills.

Extension error codes have to sit outside `-32768` through `-32000`. The codes in RFC-0001 are `-31801` through `-31812`.

Spring AI 2.1.0 and Spring Boot 4.2 can speak MCP `protocol=MODERN`. This repository does not embed them. The coordinator is a plain Java 21 library so the line-coverage gate measures the saga, the log, and the policy code rather than framework bytecode. A Spring AI 2.1 Streamable HTTP client points at `POST /mcp` on this proxy. The Java SDK is that client, not a Spring `ToolCallback`.

Temporal and Restate are durable-execution hosts. The v1 runtime uses a write-ahead log (`DurableLog`) so `docker compose up` and `java -jar` do not need another cluster. `DurableLog` is the plugin point for a later host.

Project Nessie's v2 branch API is `POST /api/v2/trees` with a source reference, merge into `main`, and `DELETE`. Neon's branch API is `POST /api/v2/projects/{id}/branches` with a bearer token. Both clients are in `org.atp.adapter`. They manage branch lifetime. They are not an Iceberg writer and they do not rewrite the JDBC URL to a Neon endpoint.

CEL is the policy language named in the requirements. The implementation is a deterministic subset in `org.atp.expr` so evaluation stays in-process and has no I/O. OPA is not a sidecar.

## Functional requirements

| Id | Requirement | Implementation | Test |
| --- | --- | --- | --- |
| FR-1 | Reversibility, compensation mapping, injected idempotency key | `Policy`, `TransactionEngine.ensureKey` | `EngineCoverageTest`, `SagaTest` |
| FR-2 | Nine-state saga, unknown reconciliation, reverse rollback | `Transitions`, `TransactionEngine` | `SagaTest`, `EngineEdgesTest`, `EngineCoverageTest` |
| FR-3 | Shadow branch for OLTP and lakehouse, dry-run for APIs | `MemoryOltp`, `JdbcOltp`, `MemoryLakehouse`, `ApiRuntime`, `NessieClient`, `NeonClient` | `AdapterTest`, `EngineEdgesTest` |
| FR-4 | Blast-radius gate and pause/resume | `Policy.evaluate`, approval sink, `tx/resume` | `SagaTest`, `EngineEdgesTest`, `ProxyTest` |
| FR-5 | Hash-chained ledger and sandbox replay | `LedgerEntry`, `LedgerChain`, `ReplayEngine` | `ModelTest`, `EngineEdgesTest`, `ProxyTest` |
| FR-6 | Fault injection and recovery metrics | `ChaosHarness` | `EngineEdgesTest`, `tx-engine chaos` |

The nine states are the ones listed in the product brief. The brief's build note said "eight" and then named nine. The engine keeps all nine.

## Non-functional requirements

| Requirement | How it is met |
| --- | --- |
| Proxy overhead under 15 ms p99, excluding sandbox I/O | The hot path is in-process. The audit fan-out is asynchronous. The write-ahead record is synchronous. `EngineEdgesTest` samples the in-memory hop. |
| No lost in-flight transaction across a restart | The intent is persisted before the external call. Recovery reconciles. `EngineCoverageTest` kills a drive with an `Error` and opens a new engine on the same log. |
| LLM input and tool annotations are untrusted | Reversibility and compensation come from the signed manifest. A missing tool is not executed. An irreversible tool cannot skip approval. |
| No lock-in to one agent framework | The wire protocol is JSON-RPC 2.0 over Streamable HTTP and STDIO. Python and Java SDKs are thin clients. |
| Apache-2.0 and a separate spec | `LICENSE`, `spec/`, and the runtime modules are separate trees. |

## Stack that shipped

| Layer | What runs |
| --- | --- |
| Coordinator | Java 21, virtual threads on the HTTP server, no Spring |
| Log | `MemoryLog` for the demo, `FileDurableLog` for a directory, `JdbcDurableLog` for H2 and PostgreSQL |
| Policy | In-process expression subset |
| OLTP shadow | Schema-per-transaction SQL on the configured JDBC URL |
| Lakehouse shadow | In-process copy-on-write snapshots. Nessie calls are control-plane only |
| API shadow | Recorded `dryRunExample`, then the live handler |
| Ledger | SHA-256 hash chain plus an anchor |
| Proxy | JDK `HttpServer`, `POST /mcp`, one SSE event when requested, STDIO |
| SDKs | Python 3.12 stdlib client, Java `TxClient` |
| Observability | In-process spans with `atp.tx_id` and `atp.compensation_triggered`. The timeline UI is served by the proxy |

## Demo story

`POST /api/demo/rollback` runs three steps. The ledger transfer and the stock mutation publish on shadow branches. The carrier returns HTTP 500. Compensation restores both branches and the state is `ROLLED_BACK`. `POST /api/demo/approve-setup` parks `send_wire` in `ESCALATED_HITL`. Approve commits it. Reject compensates it. The page at `GET /` shows the hash chain.

## Honesty limits

- The hash chain plus the anchor detects edits and truncation that leave the anchor pointing at a missing hash. Wiping the log and the anchor together is not detectable.
- Replay never calls a live tool. `--sandbox` is required.
- The lakehouse adapter used by the demo is the in-process snapshot engine. It is the reference write-audit-publish shape, not an Iceberg manifest writer.
- Neon branch create/delete is implemented. The SQL connection string is the one the operator configured.
- The schema validator is the profile in RFC-0001, not a complete JSON Schema 2020-12 implementation.
- The chaos "naive" column is a simulated executor used to compare orphan rates. It is not a second live agent runtime.
