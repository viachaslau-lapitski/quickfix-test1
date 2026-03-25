package com.perf.server;

import quickfix.*;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ServerApp {

    public static void main(String[] args) throws Exception {
        AtomicLong counter = new AtomicLong(0);
        ServerApplication application = new ServerApplication(counter);

        InputStream configStream = ServerApp.class.getResourceAsStream("/server.cfg");
        SessionSettings settings = new SessionSettings(configStream);

        MessageStoreFactory storeFactory = new MemoryStoreFactory();
        LogFactory logFactory = sessionID -> new Log() {
            public void onIncoming(String message) {}
            public void onOutgoing(String message) {}
            public void onEvent(String text) {}
            public void onErrorEvent(String text) {}
            public void clear() {}
        };
        MessageFactory messageFactory = new DefaultMessageFactory();

        SocketAcceptor acceptor = new SocketAcceptor(
                application, storeFactory, settings, logFactory, messageFactory);

        acceptor.start();
        System.out.println("Server started, listening on port 9876");

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
}
