package com.perf.client;

import quickfix.*;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class ClientApp {

    public static void main(String[] args) throws Exception {
        int tps = 100;
        int prod = 1;
        for (String arg : args) {
            if (arg.startsWith("--tps=")) tps = Integer.parseInt(arg.substring(6));
            if (arg.startsWith("--prod=")) prod = Integer.parseInt(arg.substring(7));
        }
        final int finalTps = tps;
        final int finalProd = prod;
        System.out.printf("Client starting: tps=%d prod=%d%n", finalTps, finalProd);

        ClientApplication application = new ClientApplication();

        InputStream configStream = ClientApp.class.getResourceAsStream("/client.cfg");
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
        NewOrderSingle template = new NewOrderSingle(
                new ClOrdID("ORDER"),
                new HandlInst('1'),
                new Symbol("AAPL"),
                new Side(Side.BUY),
                new TransactTime(),
                new OrdType(OrdType.MARKET)
        );
        template.set(new OrderQty(100));

        // Wait for logon
        System.out.println("Waiting for session logon...");
        while (!application.isLoggedOn()) {
            Thread.sleep(200);
        }
        System.out.println("Session established, starting load generators");

        AtomicLong counter = new AtomicLong(0);
        int msgsPerProd = finalTps;

        ScheduledExecutorService[] producers = new ScheduledExecutorService[finalProd];
        for (int i = 0; i < finalProd; i++) {
            producers[i] = Executors.newSingleThreadScheduledExecutor();
            producers[i].scheduleAtFixedRate(() -> {
                for (int j = 0; j < msgsPerProd; j++) {
                    try {
                        NewOrderSingle msg = (NewOrderSingle) template.clone();
                        msg.set(new TransactTime());
                        counter.incrementAndGet();
                        Session.sendToTarget(msg, sessionID);
                    } catch (Exception e) {
                        // ignore send errors in perf test
                    }
                }
            }, 0, 1, TimeUnit.SECONDS);
        }

        AtomicLong lastCount = new AtomicLong(0);
        long[] iteration = {0};
        ScheduledExecutorService reporter = Executors.newSingleThreadScheduledExecutor();
        reporter.scheduleAtFixedRate(() -> {
            iteration[0]++;
            long total = counter.get();
            long diff = total - lastCount.getAndSet(total);
            System.out.printf("[Client] iter=%-4d  total=%-9d  diff=%-9d%n", iteration[0], total, diff);
        }, 1, 1, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("Client shutting down...");
            reporter.shutdown();
            for (ScheduledExecutorService p : producers) p.shutdown();
            try { initiator.stop(true); } catch (Exception e) { /* ignore */ }
        }));

        // Block forever
        new CountDownLatch(1).await();
    }
}
