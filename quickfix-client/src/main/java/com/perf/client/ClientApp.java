package com.perf.client;

import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ClientApp {

    public static void main(String[] args) throws Exception {
        Properties appProps = loadProperties("app.properties");
        int tps = Integer.parseInt(appProps.getProperty("tps", "100"));
        int prod = Integer.parseInt(appProps.getProperty("prod", "1"));
        int len = Integer.parseInt(appProps.getProperty("len", "100"));
        String store = appProps.getProperty("store", "memory");
        String log = appProps.getProperty("log", "none");
        boolean spread = Boolean.parseBoolean(appProps.getProperty("spread", "false"));
        final int finalTps = tps;
        final int finalProd = prod;
        final int finalLen = len;
        System.out.printf("Client starting: tps=%d prod=%d len=%d store=%s log=%s spread=%b%n",
            finalTps, finalProd, finalLen, store, log, spread);

        AtomicLong transientErrors = new AtomicLong(0);
        AtomicLong channelBreaks = new AtomicLong(0);
        ErrorLog errorLog;
        try {
            errorLog = new ErrorLog("errors.log");
        } catch (Exception e) {
            throw new RuntimeException("Failed to open errors.log", e);
        }
        System.out.println("Client error details logged to: errors.log");

        SessionErrorListener errorListener = new SessionErrorListener(transientErrors, channelBreaks, errorLog);
        ClientApplication application = new ClientApplication(errorListener);

        try (InputStream configStream = openConfigStream("client.cfg")) {
            SessionSettings settings = new SessionSettings(configStream);

            MessageStoreFactory storeFactory = buildStoreFactory(store, settings);
            LogFactory logFactory = buildLogFactory(log, settings, errorLog);
            MessageFactory messageFactory = new DefaultMessageFactory();

            SocketInitiator initiator = new SocketInitiator(
                    application, storeFactory, settings, logFactory, messageFactory);

            initiator.start();
            System.out.println("Client initiator started, connecting to localhost:9876");

            // Get sessionID from the application (populated by onCreate callback)
            SessionID sid = null;
            while ((sid = application.getSessionID()) == null) {
                Thread.sleep(100);
            }
            final SessionID sessionID = sid;
                NewOrderSingle template = ClientMessageSizer.buildTemplate(finalLen, ThreadLocalRandom.current());

            // Wait for logon
            System.out.println("Waiting for session logon...");
            while (!application.isLoggedOn()) {
                Thread.sleep(200);
            }
            System.out.println("Session established, starting load generators");

            AtomicLong counter = new AtomicLong(0);
            SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
            Timer sendTimer = Timer.builder("client.sendToTarget")
                .publishPercentiles(0.95, 0.99)
                .publishPercentileHistogram()
                .register(meterRegistry);
            AtomicLong lastP95Nanos = new AtomicLong(0);
            AtomicLong lastP99Nanos = new AtomicLong(0);
            AtomicLong lastP100Nanos = new AtomicLong(0);

            // spread=true: send one message every (1s/tps) to avoid concurrent TLS record
            // encoding in MINA's SSLHandlerG1.forward_writes() which causes Tag mismatch!
            // when messages exceed one TLS record (>16383 bytes). Each producer independently
            // sends the full tps rate; prod multiplies total throughput.
            // spread=false: burst all tps messages at once each second (may cause Tag mismatch
            // with SSL + large messages due to MINA 2.2.4 bug DIRMINA-1176 / forward_writes race).
            ScheduledExecutorService[] producers = new ScheduledExecutorService[finalProd];
            if (spread) {
                long intervalNanos = 1_000_000_000L / finalTps;
                for (int i = 0; i < finalProd; i++) {
                    producers[i] = Executors.newSingleThreadScheduledExecutor();
                    producers[i].scheduleAtFixedRate(() -> {
                        try {
                            NewOrderSingle msg = (NewOrderSingle) template.clone();
                            msg.set(new TransactTime());
                            counter.incrementAndGet();
                            long start = System.nanoTime();
                            Session.sendToTarget(msg, sessionID);
                            long elapsed = System.nanoTime() - start;
                            sendTimer.record(elapsed, TimeUnit.NANOSECONDS);
                        } catch (Exception e) {
                            long count = transientErrors.incrementAndGet();
                            errorLog.logTransient("sendToTarget", count, e);
                        }
                    }, 0, intervalNanos, TimeUnit.NANOSECONDS);
                }
            } else {
                int msgsPerProd = finalTps;
                for (int i = 0; i < finalProd; i++) {
                    producers[i] = Executors.newSingleThreadScheduledExecutor();
                    producers[i].scheduleAtFixedRate(() -> {
                        for (int j = 0; j < msgsPerProd; j++) {
                            try {
                                NewOrderSingle msg = (NewOrderSingle) template.clone();
                                msg.set(new TransactTime());
                                counter.incrementAndGet();
                                long start = System.nanoTime();
                                Session.sendToTarget(msg, sessionID);
                                long elapsed = System.nanoTime() - start;
                                sendTimer.record(elapsed, TimeUnit.NANOSECONDS);
                            } catch (Exception e) {
                                long count = transientErrors.incrementAndGet();
                                errorLog.logTransient("sendToTarget", count, e);
                            }
                        }
                    }, 0, 1, TimeUnit.SECONDS);
                }
            }

            AtomicLong lastCount = new AtomicLong(0);
            long[] iteration = {0};
            ScheduledExecutorService latencySampler = Executors.newSingleThreadScheduledExecutor();
            latencySampler.scheduleAtFixedRate(() -> {
                long p95Nanos = 0;
                long p99Nanos = 0;
                var snapshot = sendTimer.takeSnapshot();
                ValueAtPercentile[] percentiles = snapshot.percentileValues();
                for (ValueAtPercentile p : percentiles) {
                    switch ((int) Math.round(p.percentile() * 100)) {
                        case 95:
                            p95Nanos = (long) p.value();
                            break;
                        case 99:
                            p99Nanos = (long) p.value();
                            break;
                        default:
                            break;
                    }
                }
                if (p95Nanos > 0) {
                    lastP95Nanos.set(p95Nanos);
                }
                if (p99Nanos > 0) {
                    lastP99Nanos.set(p99Nanos);
                }
                long p100Nanos = (long) snapshot.max(TimeUnit.NANOSECONDS);
                if (p100Nanos > 0) {
                    lastP100Nanos.set(p100Nanos);
                }
            }, 1, 1, TimeUnit.SECONDS);

            ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
            reporter.scheduleAtFixedRate(() -> {
                iteration[0]++;
                long total = counter.get();
                long diff = total - lastCount.getAndSet(total);
                    long p95Nanos = lastP95Nanos.get();
                    long p99Nanos = lastP99Nanos.get();
                    long p100Nanos = lastP100Nanos.get();
                    double p95Millis = p95Nanos / 1_000_000.0;
                    double p99Millis = p99Nanos / 1_000_000.0;
                    double p100Millis = p100Nanos / 1_000_000.0;
                    System.out.printf("[Client] iter=%-4d  total=%-9d  diff=%-9d  transient_errors=%-6d  channel_breaks=%-4d  p95_ms=%-8.3f  p99_ms=%-8.3f  p100_ms=%-8.3f%n",
                        iteration[0], total, diff, transientErrors.get(), channelBreaks.get(), p95Millis, p99Millis, p100Millis);
            }, 1, 1, TimeUnit.SECONDS);

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Client shutting down...");
                reporter.shutdown();
                latencySampler.shutdown();
                for (ScheduledExecutorService p : producers) p.shutdown();
                try { initiator.stop(true); } catch (Exception e) { /* ignore */ }
                errorLog.close();
            }));

            // Block forever
            new CountDownLatch(1).await();
        }
    }

    private static Properties loadProperties(String fileName) throws IOException {
        Properties props = new Properties();
        try (InputStream stream = openConfigStream(fileName)) {
            props.load(stream);
        }
        return props;
    }

    private static InputStream openConfigStream(String fileName) throws IOException {
        Path workDirFile = Paths.get(fileName);
        if (Files.exists(workDirFile)) {
            return Files.newInputStream(workDirFile);
        }

        InputStream resourceStream = ClientApp.class.getResourceAsStream("/" + fileName);
        if (resourceStream == null) {
            throw new IOException("Config file not found in working directory or resources: " + fileName);
        }
        return resourceStream;
    }

    private static MessageStoreFactory buildStoreFactory(String store, SessionSettings settings) {
        String normalized = store == null ? "" : store.trim().toLowerCase();
        switch (normalized) {
            case "file":
                return new FileStoreFactory(settings);
            case "cachedfile":
                return new CachedFileStoreFactory(settings);
            case "memory":
            case "":
                return new MemoryStoreFactory();
            default:
                throw new IllegalArgumentException("Unsupported store type: " + store);
        }
    }

    private static LogFactory buildLogFactory(String log, SessionSettings settings, ErrorLog errorLog) {
        String normalized = log == null ? "" : log.trim().toLowerCase();
        switch (normalized) {
            case "file":
                return new FileLogFactory(settings);
            case "console":
                return new ScreenLogFactory(settings);
            case "none":
            case "":
                // Suppress FIX message traffic but capture error/warn events so the root
                // cause of disconnects (e.g. SSL BufferOverflowException) is visible in errors.log.
                return sessionID -> new Log() {
                    public void onIncoming(String message) {}
                    public void onOutgoing(String message) {}
                    public void onEvent(String text) {
                        // Log all session events so errors.log captures the real disconnect
                        // cause. QFJ routes IOException subclasses (including SSLException)
                        // through onEvent(), not onErrorEvent(), so filtering to just
                        // "Disconnecting:" would silently discard the underlying error.
                        errorLog.logSessionEvent(sessionID.toString(), "EVENT", text);
                    }
                    public void onErrorEvent(String text) {
                        errorLog.logSessionEvent(sessionID.toString(), "ERROR", text);
                    }
                    public void onWarnEvent(String text) {
                        errorLog.logSessionEvent(sessionID.toString(), "WARN", text);
                    }
                    public void clear() {}
                };
            default:
                throw new IllegalArgumentException("Unsupported log type: " + log);
        }
    }
}
