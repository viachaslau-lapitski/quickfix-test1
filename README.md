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
- (Docker runs) [Docker Desktop](https://www.docker.com/products/docker-desktop/) with the `docker compose` v2 plugin

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
| `spread` | `false` | `true` = send one message every `(1s/tps)` instead of bursting all at second boundaries. **Required for SSL with `len > 16383`** — see [SSL note](#ssl-large-message-note) below. |

**Store backends:** `memory` → `MemoryStoreFactory` (no I/O); `file` → `FileStoreFactory` (needs `FileStorePath` in `client.cfg`); `cachedfile` → `CachedFileStoreFactory`.

**Log backends:** `none` → no-op (best performance); `file` → `FileLogFactory` (needs `FileLogPath` in `client.cfg`); `console` → `ScreenLogFactory`.

### SSL large-message note

When running with `--ssl` and `len > 16383` (one TLS record = 16384 bytes max), burst mode causes `SSLException: Tag mismatch!` on the server and channel breaks every few seconds. Root cause: a race condition in MINA 2.2.4's `SSLHandlerG1.forward_writes()` (not synchronized) that allows TLS record chunks for a single large message to be submitted to the socket in wrong order — the server's AEAD tag verification then fails.

Set `spread=true` in `app.properties` (or use `len ≤ 16383`) to eliminate this. Both are recorded in `run/errors.log` (client) and `run/server-errors.log` (server).

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
| `run/run-docker.sh` | Launches both containers via `docker-compose.yml`; accepts all constraint env vars |
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

## Run with Docker

Docker runs both components in isolated containers on a shared bridge network (`fix-net`), with optional network emulation (`tc netem`) and hard resource limits.

### Quick start

```bash
# First run, or after changing Java source / build.gradle — build images (~30 s)
./run/run-docker.sh --build

# Change docker/app.properties or any env var — no rebuild needed (~1 s)
./run/run-docker.sh

# mTLS — uses docker-compose-ssl.yml; server accepts plain :9876 + SSL :9877
./run/run-docker.sh --ssl --build   # first run
./run/run-docker.sh --ssl           # subsequent runs

# Detached
./run/run-docker.sh -d
./run/run-docker.sh --ssl -d
```

Edit **`docker/app.properties`** to tune throughput (same properties as `run/app.properties`):

```properties
tps=1000
prod=4
len=75
store=memory
log=none
```

### Constraint env vars

All vars have defaults in `run/run-docker.sh`; override inline or via a `.env` file.

| Variable | Default | Description |
|----------|---------|-------------|
| `LAT_MS` | `25` | One-way egress delay (ms) — applied on **both** containers via `tc netem`, so RTT ≈ `2 × LAT_MS` |
| `LAT_JITTER_MS` | `0` | ± packet jitter (ms) |
| `PLR` | `0.1` | Packet loss rate (%) |
| `BW_MBIT` | `10` | Egress bandwidth cap (Mbit/s); `0` = unlimited |
| `SERVER_CPUS` | `1.0` | CPU quota for the server container |
| `SERVER_MEM` | `512m` | Memory limit for the server container |
| `CLIENT_CPUS` | `2.0` | CPU quota for the client container |
| `CLIENT_MEM` | `256m` | Memory limit for the client container |
| `JVM_OPTS` | _(empty)_ | Extra JVM flags applied to both containers |

**Examples:**

```bash
# 50 ms RTT, 5 Mbit/s, no loss, server pinned to half a core
LAT_MS=25 BW_MBIT=5 SERVER_CPUS=0.5 ./run/run-docker.sh

# Baseline — no network constraints, generous resources
LAT_MS=0 BW_MBIT=0 PLR=0 ./run/run-docker.sh

# Extra heap for high-tps runs
JVM_OPTS="-Xmx1g" tps=5000 ./run/run-docker.sh
```

### docker/ directory

```
docker/
├── server.cfg           — FIX acceptor config (plain :9876, no SSL)
├── server-ssl.cfg       — FIX acceptor config (plain :9876 + mTLS :9877, used by docker-compose-ssl.yml)
├── client.cfg           — FIX initiator config (fix-server:9876, plain TCP)
├── client-ssl.cfg       — FIX initiator config (fix-server:9877, mTLS, used by docker-compose-ssl.yml)
├── app.properties       — client tuning; edit freely — no rebuild required
├── entrypoint-server.sh — applies tc netem constraints, then starts server JAR
└── entrypoint-client.sh — applies tc netem constraints, then starts client JAR
```

Config files are **volume-mounted** into `/app/` at runtime — never baked into the images. Changing any of them takes effect on the next `docker compose up` without `--build`.

`docker/client.cfg` and `docker/client-ssl.cfg` set `FileStorePath=/tmp/store` and `FileLogPath=/tmp/logs`. These paths live in the container's ephemeral `/tmp` filesystem — writable without any volume mount. To measure I/O overhead, set `store=file` and/or `log=file` in `docker/app.properties`; to benchmark without I/O, use `store=memory` + `log=none`.

### Compose files

| File | Usage |
|------|-------|
| `docker-compose.yml` | Plain TCP only — client connects to `fix-server:9876` |
| `docker-compose-ssl.yml` | mTLS — server accepts plain :9876 + SSL :9877; client connects via SSL; mounts `run/certs/` on both containers |



Both sides print a line every second:

```
[Client] iter=48    total=720000     diff=15000      p95_ms=0.004  p99_ms=0.007  p100_ms=0.009
[Server] iter=48    total=720000     diff=720000
```

- **client** — messages attempted plus `p95_ms` (95th-percentile), `p99_ms` (99th-percentile), and `p100_ms` (max) `sendToTarget` latency
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

The server hosts two sessions simultaneously. Use `--ssl` to switch between them:

**Local (JAR-based):**

```bash
./run/run-server.sh                    # accepts both plain :9876 and mTLS :9877

./run/run-client.sh --timeout=60       # plain TCP baseline
./run/run-client.sh --ssl --timeout=60 # mTLS — same tps settings, encrypted channel
```

**Docker:**

```bash
./run/run-docker.sh --build            # plain TCP baseline
./run/run-docker.sh --ssl --build      # mTLS — uses docker-compose-ssl.yml
```

Compare `diff/s`, `p95_ms`, `p99_ms`, and `p100_ms` between runs to measure TLS overhead. For a clean comparison set `store=memory` and `log=none` in the relevant `app.properties` to eliminate I/O noise.

To regenerate the self-signed test certificates:

```bash
bash run/gen-certs.sh
```
