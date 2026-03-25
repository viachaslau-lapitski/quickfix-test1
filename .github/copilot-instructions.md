# Copilot Instructions

## Project Overview

A minimal FIX 4.2 throughput benchmarking harness built on QuickFIX/J 2.3.2. Two independent Java 17 console applications:

- **quickfix-server** — FIX acceptor listening on `localhost:9876`, counts inbound messages
- **quickfix-client** — FIX initiator that drives configurable message throughput via `--tps` and `--prod` flags

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

There are no tests — validation is manual (run both JARs, observe console throughput output).

## Running

```bash
# Terminal 1 — start server first
java -jar quickfix-server/build/libs/quickfix-server-all.jar

# Terminal 2 — start client
java -jar quickfix-client/build/libs/quickfix-client-all.jar --tps=1000 --prod=4
```

Client flags: `--tps=N` (messages/sec total, default 100), `--prod=N` (producer threads, default 1). Each thread sends `tps/prod` msg/s.

## Architecture

```
ClientApp  ──FIX 4.2/TCP:9876──►  ServerApp
(initiator)                        (acceptor)
```

Each module has exactly two Java classes:
- `*App.java` — wires QuickFIX/J components, manages lifecycle (startup, shutdown hook, reporting timer)
- `*Application.java` — implements `quickfix.Application`, handles session callbacks and counting

Both use `MemoryStoreFactory` (no disk I/O) and a custom no-op `LogFactory` to minimize benchmark overhead.

## Key Conventions

**No-op logging**: Both apps implement a custom `LogFactory` returning empty `Log` instances — do not replace with SLF4J or other frameworks, as this is intentional for performance.

**Message template cloning**: `ClientApp` pre-builds a `NewOrderSingle` template once and clones it per send, updating only `TransactTime`. Follow this pattern if adding new message types.

**Counter increments before send**: In `ClientApp`, `AtomicLong` counters are incremented *before* `Session.sendToTarget()` — this is intentional to count attempted sends.

**QuickFIX/J config**: Session parameters live in `src/main/resources/server.cfg` / `client.cfg` (ini-style). `UseDataDictionary=N` is set deliberately to skip validation overhead.

**Single dependency**: Both modules depend only on `org.quickfixj:quickfixj-all:2.3.2`. Keep the dependency footprint minimal.

**Fat JARs only**: The Shadow plugin (`com.github.johnrengelman.shadow`) produces self-contained JARs with `mergeServiceFiles()` — required for QuickFIX/J's service loader entries to work correctly.
