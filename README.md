# Agent Transaction Protocol

A transactional proxy and coordinator for tool-calling agents. MCP 2026-07-28 defines how a tool is called. This project defines how a multi-step mutation is shadowed, gated, committed, and rolled back.

The specification is in [`spec/RFC-0001-core-protocol.md`](spec/RFC-0001-core-protocol.md). The requirements and the stack decisions are in [`spec/TRD.md`](spec/TRD.md). The license is Apache-2.0.

Guides and the published site live in [`docs/`](docs/index.html):

| Page | What it teaches |
| --- | --- |
| [Overview](docs/index.html) | What the engine guarantees |
| [Tutorials](docs/tutorials/index.html) | Six lessons, from the protocol gap through the SDKs |
| [States](docs/reference/states.html) | The nine saga states and their edges |
| [Errors](docs/reference/errors.html) | JSON-RPC codes −31801 through −31812 |
| [Commands](docs/reference/commands.html) | `tx-engine`, environment variables, and demo routes |

`docs/` is the GitHub Pages site, and `.github/workflows/pages.yml` publishes it. GitHub declined to enable Pages on this private repository with the current plan. Making the repository public lets that workflow serve [vaquarkhan.github.io/Reverta](https://vaquarkhan.github.io/Reverta/). Until then, open `docs/index.html` directly.

## Layout

| Path | Role |
| --- | --- |
| `spec/` | RFC-0001, technical requirements, JSON Schemas |
| `engine-core/` | Saga coordinator, write-ahead log, policy, ledger, adapters, chaos harness |
| `mcp-tx-proxy/` | Streamable HTTP and STDIO proxy, timeline UI, `tx-engine` command |
| `sdks/java/` | `TxClient` for the proxy |
| `sdks/python/` | Stdlib client and `@compensatable_tool` |
| `examples/` | Docker Compose demo |
| `docs/` | GitHub Pages site: tutorials and reference |
| `chaos-bench/` | How to emit the recovery CSV |

## Build

The coordinator targets Java 21. Point `JAVA_HOME` at a JDK 21 before Maven. On this machine that JDK is Temurin 21.0.11.

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
mvn -B verify
```

`mvn verify` fails the build unless JaCoCo reports a line-coverage ratio of 1.00 for `engine-core`, `mcp-tx-proxy` (excluding the one-line process entrypoint), and `atp-java-sdk`.

The Python SDK has no third-party dependencies.

```powershell
py -3.12 -m pip install pytest coverage
py -3.12 -m coverage run --source=agent_tx -m pytest
py -3.12 -m coverage report --fail-under=100
```

Run those from `sdks/python`. `pyproject.toml` puts `src` on the pytest path. `coverage` needs `--source=agent_tx` when it is invoked as a module from that directory.

## Run the demo

```powershell
mvn -B -pl mcp-tx-proxy -am package -DskipTests
java -jar mcp-tx-proxy\target\tx-engine.jar demo --port 8080
```

Open `http://127.0.0.1:8080/`. **Run rollback demo** executes a three-step plan: a ledger transfer, a stock mutation, and a carrier call that returns HTTP 500. The page shows `ROLLED_BACK` and the hash chain. **Park an irreversible wire**, then **Approve** or **Reject**, exercises the human hold. Each park uses a new transaction id.

`examples/demo.ps1` sets `JAVA_HOME` and starts the same jar. `examples/docker-compose.yml` builds the jar and stores the write-ahead log in PostgreSQL. The default `java -jar ... demo` command uses an in-memory log and does not need Docker.

## Point a Spring AI client at the proxy

Spring AI 2.1 speaks MCP `protocol=MODERN` over Streamable HTTP. Set the client base URL to `http://127.0.0.1:8080/mcp`. This process does not embed Spring. The proxy reads `io.modelcontextprotocol/protocolVersion` and `io.modelcontextprotocol/clientCapabilities` from `_meta` on every request.

## Commands

```text
tx-engine demo --port 8080
tx-engine stdio
tx-engine keys
tx-engine sign --kid admin --private <base64> --payload manifest.json
tx-engine replay --tx <id> --wal-dir <dir> --sandbox --up-to-step 3
tx-engine chaos --out chaos-bench/out/results.csv
```

Environment variables for the demo process:

| Variable | Effect |
| --- | --- |
| `ATP_JDBC_URL` | JDBC write-ahead log. User and password come from `ATP_JDBC_USER` and `ATP_JDBC_PASSWORD` |
| `ATP_WAL_DIR` | File write-ahead log, used when no JDBC URL is set |

## What the engine guarantees

Mutating tools declare `ATOMIC_BRANCHABLE`, `COMPENSATABLE`, or `IRREVERSIBLE` in a manifest signed with Ed25519. The model cannot change that classification. Every outbound call gets an idempotency key of `txId:stepIndex:sha256(arguments)`. Unknown outcomes are reconciled before they are retried. Rollback runs in reverse topological order. Irreversible steps wait for a person, and a later failure leaves them marked orphaned instead of pretending they were undone.

The ledger is a SHA-256 hash chain with an anchor. It shows tampering that leaves the anchor behind. It does not detect a wipe of both the log and the anchor. Replay refuses to call live endpoints.
