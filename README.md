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
# Terminal 1 — server
java -jar quickfix-server/build/libs/quickfix-server-all.jar

# Terminal 2 — client
java -jar quickfix-client/build/libs/quickfix-client-all.jar
```

### Using the `run/` workspace

The `run/` directory is a ready-to-use launch environment:

| File | Purpose |
|------|---------|
| `run/client.cfg` | FIX session config with `FileStorePath=./store`, `FileLogPath=./logs`, `FileStoreSync=N`, pointing to `localhost:9876` |
| `run/app.properties` | Example: `tps=5000`, `prod=1`, `len=5000`, `store=file`, `log=file` (heavy I/O scenario) |
| `run/run.sh` | Bash launcher that picks up both config files automatically |

#### `run/run.sh` flags

```bash
./run/run.sh [--timeout=N] [--runs=N] [--pause=N]
```

| Flag        | Default | Description |
|-------------|---------|-------------|
| `--timeout` | `65`    | Max seconds per run (`0` = unlimited). Uses `gtimeout`/`timeout` |
| `--runs`    | `1`     | Number of sequential runs |
| `--pause`   | `5`     | Seconds to wait between runs |

Any unrecognised flags are passed through to the client JAR. The script locates the JAR relative to itself, runs from `run/` so `client.cfg` and `app.properties` are picked up automatically, and handles `Ctrl+C` / `Ctrl+Z` gracefully.

**Example — three 60-second runs with a 5-second pause between each:**

```bash
./run/run.sh --timeout=60 --runs=3 --pause=5
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
