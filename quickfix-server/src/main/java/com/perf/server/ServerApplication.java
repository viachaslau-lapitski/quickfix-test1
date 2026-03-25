package com.perf.server;

import quickfix.*;
import java.util.concurrent.atomic.AtomicLong;

public class ServerApplication implements Application {

    private final AtomicLong counter;

    public ServerApplication(AtomicLong counter) {
        this.counter = counter;
    }

    @Override public void onCreate(SessionID sessionID) {}
    @Override public void onLogon(SessionID sessionID) {
        System.out.println("Server: session logon " + sessionID);
    }
    @Override public void onLogout(SessionID sessionID) {
        System.out.println("Server: session logout " + sessionID);
    }
    @Override public void toAdmin(Message message, SessionID sessionID) {}
    @Override public void fromAdmin(Message message, SessionID sessionID) {}
    @Override public void toApp(Message message, SessionID sessionID) throws DoNotSend {}

    @Override
    public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {
        counter.incrementAndGet();
    }
}
