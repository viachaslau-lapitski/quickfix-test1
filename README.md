# QuickFIX/J Performance Test Harness

A minimal FIX 4.2 throughput benchmarking harness built on [QuickFIX/J 2.3.2](https://www.quickfixj.org/). Two independent Java 17 console apps — a **server** (acceptor) and a **client** (initiator) — count messages and print a `diff/s` report every second.

## Architecture

```
quickfix-client  ──── FIX 4.2 / TCP :9876 ────▶  quickfix-server
  SocketInitiator                                    SocketAcceptor
  SenderCompID=CLIENT                                SenderCompID=SERVER
  TargetCompID=SERVER                                TargetCompID=CLIENT
  N producer threads × TPS msgs/s each
  AtomicLong send counter                            AtomicLong recv counter
  1 s reporting timer                                1 s reporting timer
```

### Java classes

| Module | Classes |
|--------|---------|
| `quickfix-server` | `ServerApp.java`, `ServerApplication.java` |
| `quickfix-client` | `ClientApp.java`, `ClientApplication.java`, `ClientMessageSizer.java` |

Unit tests: `quickfix-client/src/test/java/com/perf/client/ClientAppTest.java` (covers `ClientMessageSizer`).

## Prerequisites

- Java 17+
- (macOS, for timed runs) `brew install coreutils`

## Build

**Both modules at once** (from repo root):

```bash
./gradlew shadowJar
# → quickfix-server/build/libs/quickfix-server-all.jar
# → quickfix-client/build/libs/quickfix-client-all.jar
```

**Individual modules** (still supported):

```bash
cd quickfix-server && ./gradlew shadowJar
cd quickfix-client && ./gradlew shadowJar
```

## Configuration — `app.properties`

The client reads `app.properties` from the working directory first, then the classpath.

| Property | Default | Description |
|----------|---------|-------------|
| `tps`    | `100`   | Total messages per second to send |
| `prod`   | `1`     | Number of parallel producer threads. Each sends `tps` msgs/s |
| `len`    | `75`    | Target FIX message body length in bytes (padded via `Text` field) |
| `store`  | `memory`| Message store: `memory`, `file`, `cachedfile` |
| `log`    | `none`  | Logging backend: `none`, `file`, `console` |

**Store backends:** `memory` → `MemoryStoreFactory` (no I/O); `file` → `FileStoreFactory` (needs `FileStorePath` in `client.cfg`); `cachedfile` → `CachedFileStoreFactory`.

**Log backends:** `none` → no-op (best performance); `file` → `FileLogFactory` (needs `FileLogPath` in `client.cfg`); `console` → `ScreenLogFactory`.

## Run

### Quick start (manual)

```bash
# Terminal 1 — server (from run/ so run/server.cfg and certs/ are found)
./run/run-server.sh

# Terminal 2 — client plain TCP
./run/run-client.sh --timeout=60

# Terminal 2 — client mTLS (port 9877, CLIENT_SSL session)
./run/run-client.sh --ssl --timeout=60
```

Direct JAR invocation (uses bundled classpath config, plain only):

```bash
java -jar quickfix-server/build/libs/quickfix-server-all.jar
java -jar quickfix-client/build/libs/quickfix-client-all.jar
```

### Using the `run/` workspace

The `run/` directory is a self-contained launch environment with server and client scripts, FIX configs, and committed mTLS certificates.

| File | Purpose |
|------|---------|
| `run/run-server.sh` | Starts the server from `run/` (resolves `server.cfg` and `certs/` paths) |
| `run/run-client.sh` | Starts the client; picks up `client.cfg` and `app.properties` automatically |
| `run/client-plain.cfg` | Plain TCP initiator config — port 9876, `SenderCompID=CLIENT` |
| `run/client-ssl.cfg` | mTLS initiator config — port 9877, `SenderCompID=CLIENT_SSL`, TLSv1.2 |
| `run/server.cfg` | Two-session acceptor — plain on `:9876`, mTLS on `:9877` (`NeedClientAuth=Y`) |
| `run/app.properties` | Client tuning: `tps`, `prod`, `len`, `store`, `log` |
| `run/gen-certs.sh` | Regenerates `run/certs/` via `keytool` (idempotent, password: `changeit`) |
| `run/certs/` | Committed self-signed JKS keystores for mTLS |

#### `run/run-client.sh` flags

```bash
./run/run-client.sh [--ssl] [--timeout=N] [--runs=N] [--pause=N]
```

| Flag        | Default | Description |
|-------------|---------|-------------|
| `--ssl`     | off     | Use mTLS config (port 9877, `CLIENT_SSL` session) |
| `--timeout` | `65`    | Max seconds per run (`0` = unlimited). Uses `gtimeout`/`timeout` |
| `--runs`    | `1`     | Number of sequential runs |
| `--pause`   | `5`     | Seconds to wait between runs |

Any unrecognised flags are passed through to the client JAR. The script handles `Ctrl+C` / `Ctrl+Z` gracefully.

**Examples:**

```bash
# Plain TCP — three 60-second runs
./run/run-client.sh --timeout=60 --runs=3 --pause=5

# mTLS — single 60-second run
./run/run-client.sh --ssl --timeout=60
```

## Console Output

Both sides print a line every second:

```
[Client] iter=48    total=720000     diff=15000      p95_ms=0.004
[Server] iter=48    total=720000     diff=720000
```

- **client** — messages attempted plus `p95_ms` (95th-percentile `sendToTarget` latency)
- **server** — messages received

Stop both processes with `Ctrl+C`; shutdown hooks cleanly disconnect the FIX session.

## Benchmarking Store / Log Impact

Use `store` and `log` in `app.properties` to isolate persistence overhead:

| Configuration | Scenario |
|---------------|----------|
| `store=memory` + `log=none` | Baseline — no I/O |
| `store=file` + `log=none` | File store overhead only |
| `store=cachedfile` + `log=none` | Cached file store (intermediate) |
| `store=file` + `log=file` | Maximum I/O (matches `run/app.properties` example) |

## Benchmarking SSL / mTLS Impact

The server hosts two sessions simultaneously. Use `run-client.sh --ssl` to switch between them:

```bash
./run/run-server.sh                    # accepts both plain :9876 and mTLS :9877

./run/run-client.sh --timeout=60       # plain TCP baseline
./run/run-client.sh --ssl --timeout=60 # mTLS — same tps settings, encrypted channel
```

Compare `diff/s` and `p95_ms` between runs to measure TLS overhead. For a clean comparison set `store=memory` and `log=none` in `run/app.properties` to eliminate I/O noise.

To regenerate the self-signed test certificates:

```bash
bash run/gen-certs.sh
```
