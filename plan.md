# Plan: QuickFIX/J Performance Test Harness

Create two Java 17 Gradle projects (`quickfix-server`, `quickfix-client`) in the working directory. Both are console apps using QuickFIX/J 2.3.2 with in-memory storage. Client drives configurable TPS across N load-generator threads; both sides count messages and report diffs every second. Built as fat JARs via Shadow plugin.

---

## Phase 1 — Create Projects (Tasks 1A and 1B run in parallel)

### Task 1A: Create `quickfix-server`
**Acceptance criteria:**
- `quickfix-server/build.gradle` declares Java 17, `quickfixj-all:2.3.2`, Shadow plugin; `settings.gradle` sets rootProject name to `quickfix-server`
- `src/main/resources/server.cfg`: FIX.4.2 Acceptor, port 9876, `MemoryStoreFactory`, `NullLogFactory`, SenderCompID=SERVER, TargetCompID=CLIENT
- `ServerApplication.java` implements `quickfix.Application`; increments `AtomicLong` counter in `fromApp()`
- `ServerApp.java` main: loads config from classpath, creates `SocketAcceptor`, starts it, blocks via `CountDownLatch`, registers shutdown hook for clean stop
- Reporting `ScheduledExecutorService` (1 s period) prints: iteration #, total count, diff since last tick
- `./gradlew shadowJar` produces `build/libs/quickfix-server-all.jar`

### Task 1B: Create `quickfix-client`
**Acceptance criteria:**
- `quickfix-client/build.gradle` mirrors server build setup (Java 17, `quickfixj-all:2.3.2`, Shadow plugin); `settings.gradle` sets rootProject name to `quickfix-client`
- `src/main/resources/client.cfg`: FIX.4.2 Initiator, connects to `localhost:9876`, `MemoryStoreFactory`, `NullLogFactory`, SenderCompID=CLIENT, TargetCompID=SERVER
- `ClientApplication.java` implements `quickfix.Application`; exposes logon/logout state via `AtomicBoolean`
- `NewOrderSingle` pre-built/cached at startup; each send uses `message.clone()`
- `ClientApp.java` main: reads `--tps=N` (default 100) and `--prod=N` (default 1) from args; waits for session logon; starts `prod` load-generator threads; each schedules a 1 s periodic task that loops `tps/prod` times calling `Session.sendToTarget()` and increments `AtomicLong` counter
- Reporting timer: same format as server (iteration #, total, diff per second)
- Shutdown hook stops initiator cleanly
- `./gradlew shadowJar` produces `build/libs/quickfix-client-all.jar`

---

## Phase 2 — Verify Compilation (Tasks 2A and 2B run in parallel)

### Task 2A: Build server JAR
**Acceptance criteria:**
- `cd quickfix-server && ./gradlew shadowJar` exits 0
- `build/libs/quickfix-server-all.jar` exists and is non-empty

### Task 2B: Build client JAR
**Acceptance criteria:**
- `cd quickfix-client && ./gradlew shadowJar` exits 0
- `build/libs/quickfix-client-all.jar` exists and is non-empty

---

## Phase 3 — Smoke-Test Startup (Tasks 3A and 3B run in parallel)

### Task 3A: Server starts cleanly
**Acceptance criteria:**
- `java -jar quickfix-server/build/libs/quickfix-server-all.jar` starts without exception
- Console shows acceptor listening and reporting timer ticking

### Task 3B: Client starts cleanly (no server required)
**Acceptance criteria:**
- `java -jar quickfix-client/build/libs/quickfix-client-all.jar` starts without exception
- Console shows initiator attempting connection and reporting timer ticking (counter = 0 until session established)

---

## Phase 4 — Integration Test (sequential, coordinated)

### Task 4: End-to-end throughput validation
**Acceptance criteria:**
1. Start server: `java -jar quickfix-server/build/libs/quickfix-server-all.jar`
2. Start client: `java -jar quickfix-client/build/libs/quickfix-client-all.jar --tps=25 --prod=2`
3. Within ~3 s of session logon, **client** reports diff ≈ 50/s (25 TPS × 2 producers)
4. Within same window, **server** reports diff ≈ 50/s
5. Terminate client (Ctrl+C), then server (Ctrl+C) — both exit cleanly

---

## Files to Create
- `quickfix-server/settings.gradle`
- `quickfix-server/build.gradle`
- `quickfix-server/src/main/resources/server.cfg`
- `quickfix-server/src/main/java/com/perf/server/ServerApp.java`
- `quickfix-server/src/main/java/com/perf/server/ServerApplication.java`
- `quickfix-client/settings.gradle`
- `quickfix-client/build.gradle`
- `quickfix-client/src/main/resources/client.cfg`
- `quickfix-client/src/main/java/com/perf/client/ClientApp.java`
- `quickfix-client/src/main/java/com/perf/client/ClientApplication.java`

---

## Decisions
- FIX 4.2, port 9876
- `NullLogFactory` + `MemoryStoreFactory` on both sides (no I/O overhead during perf test)
- SenderCompID=CLIENT / TargetCompID=SERVER
- Client counter incremented in the generator loop (before `sendToTarget`)
- Load spread: each producer sends `tps/prod` messages per second (integer division)
- Fat JAR via `com.github.johnrengelman.shadow` plugin
- No error handling beyond QuickFIX/J defaults — POC only