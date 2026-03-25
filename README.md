# QuickFIX/J Performance Test Harness

A minimal FIX 4.2 throughput test harness built on [QuickFIX/J 2.3.2](https://www.quickfixj.org/). Two console apps — a **server** (acceptor) and a **client** (initiator) — count messages independently and print a `diff/s` report every second so you can gauge end-to-end message rate.

## Architecture

```
quickfix-client  ──── FIX 4.2 / TCP :9876 ────▶  quickfix-server
  SocketInitiator                                    SocketAcceptor
  SenderCompID=CLIENT                                SenderCompID=SERVER
  TargetCompID=SERVER                                TargetCompID=CLIENT
  N producer threads × (TPS/N) msgs/s
  AtomicLong send counter                            AtomicLong recv counter
  1 s reporting timer                                1 s reporting timer
```

- **Transport:** `SocketInitiator` → `SocketAcceptor` on `localhost:9876`
- **Storage:** `MemoryStoreFactory` (no disk I/O)
- **Logging:** `NullLogFactory` (no I/O overhead during perf test)
- **Message:** pre-built `NewOrderSingle`, cloned per send

## Prerequisites

- Java 17+

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

## Run

Start the server first:

```bash
java -jar quickfix-server/build/libs/quickfix-server-all.jar
```

Then start the client (in a separate terminal):

```bash
java -jar quickfix-client/build/libs/quickfix-client-all.jar [--tps=<N>] [--prod=<N>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--tps` | `100` | Total messages per second to send |
| `--prod` | `1` | Number of parallel producer threads |

Each producer sends `tps / prod` messages per second (integer division).

**Example** — 50 msg/s across 2 threads:

```bash
java -jar quickfix-client/build/libs/quickfix-client-all.jar --tps=50 --prod=2
```

## Console Output

Both sides print a line every second:

```
[Client] iter=48    total=720000     diff=15000      p95_ms=0.004
[Client] iter=49    total=735000     diff=15000      p95_ms=0.004
[Client] iter=50    total=750000     diff=15000      p95_ms=0.004
[Client] iter=51    total=765000     diff=15000      p95_ms=0.004
[Client] iter=52    total=780000     diff=15000      p95_ms=0.004
[Client] iter=53    total=795000     diff=15000      p95_ms=0.004
[Client] iter=54    total=810385     diff=15385      p95_ms=0.004
[Client] iter=55    total=825480     diff=15095      p95_ms=0.004
...
```

- **client** — messages sent plus `p95_ms` (95th percentile `sendToTarget` latency)
- **server** — messages received

Stop both processes with `Ctrl+C`; shutdown hooks cleanly disconnect the FIX session.
