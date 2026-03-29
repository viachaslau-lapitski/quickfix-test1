# Copilot Instructions

## Project Overview

A minimal FIX 4.2 throughput benchmarking harness built on QuickFIX/J 2.3.2. Two independent Java 17 console applications:

- **quickfix-server** — FIX acceptor; with `run/server.cfg` listens on `localhost:9876` (plain) and `localhost:9877` (mTLS), counts inbound messages
- **quickfix-client** — FIX initiator that drives configurable message throughput, controlled via `app.properties`

## Build Commands

Build both modules at once from the repo root using the root Gradle wrapper:

```bash
# Build both fat JARs in one step (run from repo root)
./gradlew shadowJar
```

Output artifacts:
- `quickfix-server/build/libs/quickfix-server-all.jar`
- `quickfix-client/build/libs/quickfix-client-all.jar`

Individual modules can still be built independently:

```bash
cd quickfix-server && ./gradlew shadowJar
cd quickfix-client && ./gradlew shadowJar
```

Unit tests exist for `quickfix-client` (covering `ClientMessageSizer`). Run them with:

```bash
./gradlew :quickfix-client:test
```

Integration validation is still manual (run both JARs, observe console throughput output).

## Running

```bash
# Terminal 1 — start server first
java -jar quickfix-server/build/libs/quickfix-server-all.jar

# Terminal 2 — start client (reads app.properties from cwd or classpath)
java -jar quickfix-client/build/libs/quickfix-client-all.jar

# Or use the run scripts for multi-run benchmarks
./run/run-server.sh                            # Terminal 1
./run/run-client.sh --timeout=60 --runs=3      # Terminal 2, plain TCP
./run/run-client.sh --ssl --timeout=60         # Terminal 2, mTLS (port 9877)
```

There are no `--tps` / `--prod` CLI flags. All client tuning is done via `app.properties` (see Configuration section).

## Architecture

```
ClientApp  ──FIX 4.2/TCP:9876──►  ServerApp
(initiator)                        (acceptor)
```

**quickfix-server** — 2 classes:
- `ServerApp.java` — wires QuickFIX/J, manages lifecycle (start, shutdown hook, 1s reporting timer)
- `ServerApplication.java` — implements `quickfix.Application`, increments `AtomicLong` counter in `fromApp()`

**quickfix-client** — 3 classes:
- `ClientApp.java` — wires QuickFIX/J, reads config from `app.properties`, builds message template, manages producers/timers/shutdown
- `ClientApplication.java` — implements `quickfix.Application`, tracks logon state and sessionID
- `ClientMessageSizer.java` — builds `NewOrderSingle` messages with exact FIX body length; pads via the `Text` (tag 58) field

The server uses a hardcoded `MemoryStoreFactory` and no-op `LogFactory`. The client's store and log backends are configurable via `app.properties`.

## Configuration

The client reads `app.properties` from the **working directory first**, then the classpath. Recognized properties:

| Property | Default | Description |
|----------|---------|-------------|
| `tps` | `100` | Total messages per second |
| `prod` | `1` | Producer thread count; **each thread sends the full `tps` rate** (not `tps/prod`) |
| `len` | `75` | Target FIX message body length in bytes (padded via `Text` field) |
| `store` | `memory` | Message store: `memory` \| `file` \| `cachedfile` |
| `log` | `none` | Log backend: `none` \| `file` \| `console` |

`file` and `cachedfile` stores require `FileStorePath` set in `client.cfg`. `file` log requires `FileLogPath` in `client.cfg`.

**Store factory selection** (`buildStoreFactory` in `ClientApp.java`):
- `"file"` → `FileStoreFactory(settings)`
- `"cachedfile"` → `CachedFileStoreFactory(settings)`
- `"memory"` or `""` → `MemoryStoreFactory()`
- anything else → throws `IllegalArgumentException`

**Log factory selection** (`buildLogFactory` in `ClientApp.java`):
- `"file"` → `FileLogFactory(settings)`
- `"console"` → `ScreenLogFactory(settings)`
- `"none"` or `""` → no-op `Log` (inline anonymous class)
- anything else → throws `IllegalArgumentException`

QuickFIX/J session parameters live in `src/main/resources/server.cfg` / `client.cfg` (ini-style). `UseDataDictionary=N` is set deliberately to skip validation overhead.

## run/ Directory

```
run/
├── run-server.sh      — launches quickfix-server JAR (run from repo root or any dir)
├── run-client.sh      — launches quickfix-client JAR; picks up local client.cfg & app.properties
├── client-plain.cfg   — FIX initiator config: plain TCP, port 9876, SenderCompID=CLIENT
├── client-ssl.cfg     — FIX initiator config: mTLS, port 9877, SenderCompID=CLIENT_SSL
├── server.cfg         — FIX acceptor config: two sessions (plain :9876 + mTLS :9877)
├── app.properties     — client tuning: tps, prod, len, store, log
├── gen-certs.sh       — keytool script: regenerates run/certs/ (idempotent)
└── certs/             — committed self-signed JKS keystores (password: changeit)
    ├── server.keystore / server.truststore
    └── client.keystore / client.truststore
```

`run-server.sh` runs from the `run/` directory so relative keystore paths in `server.cfg` resolve correctly. `run-client.sh` copies `client-plain.cfg` or `client-ssl.cfg` over `client.cfg` before launch (the copied file is gitignored).

**run-client.sh flags:**

| Flag | Default | Description |
|------|---------|-------------|
| `--ssl` | off | Use mTLS config (`client-ssl.cfg`, port 9877, `CLIENT_SSL` session) |
| `--timeout=N` | `65` | Seconds per run; `0` = unlimited. Uses `gtimeout`/`timeout` |
| `--runs=N` / `--run-count=N` | `1` | Number of sequential runs |
| `--pause=N` | `5` | Seconds between runs |

All unrecognized arguments are forwarded to the Java process. The script handles `SIGINT`/`SIGTERM`/`SIGTSTP` gracefully and validates all numeric inputs.

## Key Conventions

**No-op logging (server)**: `ServerApp` implements a custom `LogFactory` returning empty `Log` instances — hardcoded, not configurable. Do not replace with SLF4J or other frameworks; this is intentional for performance.

**Configurable store/log (client)**: `ClientApp` selects store and log backends at startup based on `app.properties`. When adding new backend types, extend `buildStoreFactory` / `buildLogFactory` and throw `IllegalArgumentException` for unrecognized values.

**ClientMessageSizer**: All `NewOrderSingle` construction and body-length padding lives in `ClientMessageSizer`. `ClientApp` calls `buildTemplate(len, random)` once at startup, then clones the template per send, updating only `TransactTime`. Follow this pattern for any new message types.

**Counter increments before send**: In `ClientApp`, `AtomicLong` counters are incremented *before* `Session.sendToTarget()` — intentional to count attempted sends.

**Producer thread rate**: Each producer thread sends the full `tps` rate independently (not `tps / prod`). Increasing `prod` multiplies total throughput.

**Latency reporting**: Client reports `p95_ms` (95th percentile `sendToTarget` latency) once per second using a Micrometer `Timer`.

**Dependency footprint**: `quickfix-server` depends only on `org.quickfixj:quickfixj-all:2.3.2`. `quickfix-client` also uses `io.micrometer:micrometer-core`. Keep dependencies minimal.

**Fat JARs only**: The Shadow plugin (`com.github.johnrengelman.shadow`) produces self-contained JARs with `mergeServiceFiles()` — required for QuickFIX/J's service loader entries to work correctly.
