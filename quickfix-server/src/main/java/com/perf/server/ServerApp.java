package com.perf.server;

import quickfix.*;
import org.apache.mina.filter.ssl.SslFilter;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ServerApp {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static void main(String[] args) throws Exception {
        AtomicLong counter = new AtomicLong(0);
        ServerApplication application = new ServerApplication(counter);

        InputStream configStream = openConfigStream("server.cfg");
        SessionSettings settings = new SessionSettings(configStream);

        PrintWriter errorFile = openErrorFile("server-errors.log");
        errorFile.printf("%n=== Server session started %s ===%n", LocalDateTime.now().format(FMT));
        errorFile.printf("    server-errors.log: session events and errors (file only)%n");
        errorFile.flush();

        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = sessionID -> new Log() {
            public void onIncoming(String message) {}
            public void onOutgoing(String message) {}
            public void onEvent(String text) {
                // Skip high-volume QFJ internal store events (contain raw FIX message bodies).
                // Only write meaningful session lifecycle / error events to the log file.
                if (text.startsWith("Enqueued at pos")
                        || text.startsWith("Persistent store")
                        || text.startsWith("Resent requested")
                        || text.startsWith("Resending message")) {
                    return;
                }
                String line = String.format("%s  server-EVENT  sid=%-40s  %s",
                    LocalDateTime.now().format(FMT), sessionID, text);
                synchronized (errorFile) {
                    errorFile.println(line);
                    errorFile.flush();
                }
            }
            public void onErrorEvent(String text) {
                String line = String.format("%s  server-ERROR  sid=%-40s  %s",
                    LocalDateTime.now().format(FMT), sessionID, text);
                synchronized (errorFile) {
                    errorFile.println(line);
                    errorFile.flush();
                }
            }
            public void clear() {}
        };
        MessageFactory messageFactory = new DefaultMessageFactory();

        SocketAcceptor acceptor = new SocketAcceptor(
                application, storeFactory, settings, logFactory, messageFactory);

        // Force SSLHandlerG1 (ENABLE_ASYNC_TASKS=false, fixed) instead of SSLHandlerG0.
        // See ClientApp.java for full explanation.
        acceptor.setIoFilterChainBuilder(chain ->
            chain.getAll().stream()
                .filter(e -> e.getFilter() instanceof SslFilter)
                .forEach(e -> ((SslFilter) e.getFilter()).setUseNonBlockingPipeline(true)));

        acceptor.start();
        List<String> ports = new ArrayList<>();
        for (SessionID sid : acceptor.getSessions()) {
            try { ports.add(settings.getString(sid, "SocketAcceptPort")); } catch (Exception ignored) {}
        }
        System.out.println("Server started, listening on port(s): " + String.join(", ", ports));
        System.out.println("Session errors → server-errors.log");

        AtomicLong lastCount = new AtomicLong(0);
        long[] iteration = {0};
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            iteration[0]++;
            long total = counter.get();
            long diff = total - lastCount.getAndSet(total);
            System.out.printf("[Server] iter=%-4d  total=%-9d  diff=%-9d%n", iteration[0], total, diff);
        }, 1, 1, TimeUnit.SECONDS);

        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Server shutting down...");
            scheduler.shutdown();
            try { acceptor.stop(true); } catch (Exception e) { /* ignore */ }
            synchronized (errorFile) { errorFile.close(); }
            latch.countDown();
        }));

        latch.await();
    }

    private static PrintWriter openErrorFile(String fileName) {
        try {
            return new PrintWriter(new BufferedWriter(new FileWriter(fileName, true)));
        } catch (IOException e) {
            System.err.println("WARNING: cannot open " + fileName + ": " + e.getMessage());
            return new PrintWriter(System.err);
        }
    }

    private static InputStream openConfigStream(String fileName) throws IOException {
        Path workDirFile = Paths.get(fileName);
        if (Files.exists(workDirFile)) {
            return Files.newInputStream(workDirFile);
        }
        InputStream resourceStream = ServerApp.class.getResourceAsStream("/" + fileName);
        if (resourceStream == null) {
            throw new IOException("Config file not found in working directory or resources: " + fileName);
        }
        return resourceStream;
    }
}
