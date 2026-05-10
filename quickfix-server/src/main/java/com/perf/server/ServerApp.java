package com.perf.server;

import quickfix.*;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ServerApp {

    public static void main(String[] args) throws Exception {
        AtomicLong counter = new AtomicLong(0);
        ServerApplication application = new ServerApplication(counter);

        InputStream configStream = openConfigStream("server.cfg");
        SessionSettings settings = new SessionSettings(configStream);

        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = sessionID -> new Log() {
            public void onIncoming(String message) {}
            public void onOutgoing(String message) {}
            public void onEvent(String text) {
                // Capture disconnect reasons so the root cause of session drops is visible.
                if (text.startsWith("Disconnecting:")) {
                    System.err.printf("%s [Server-EVENT] %s: %s%n",
                        java.time.LocalTime.now(), sessionID, text);
                }
            }
            public void onErrorEvent(String text) {
                System.err.printf("%s [Server-ERROR] %s: %s%n",
                    java.time.LocalTime.now(), sessionID, text);
            }
            public void clear() {}
        };
        MessageFactory messageFactory = new DefaultMessageFactory();

        SocketAcceptor acceptor = new SocketAcceptor(
                application, storeFactory, settings, logFactory, messageFactory);

        acceptor.start();
        List<String> ports = new ArrayList<>();
        for (SessionID sid : acceptor.getSessions()) {
            try { ports.add(settings.getString(sid, "SocketAcceptPort")); } catch (Exception ignored) {}
        }
        System.out.println("Server started, listening on port(s): " + String.join(", ", ports));

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
            latch.countDown();
        }));

        latch.await();
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
