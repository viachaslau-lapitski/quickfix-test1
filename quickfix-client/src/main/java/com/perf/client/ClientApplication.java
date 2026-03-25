package com.perf.client;

import quickfix.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientApplication implements Application {

    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private volatile SessionID sessionID;

    public boolean isLoggedOn() {
        return loggedOn.get();
    }

    public SessionID getSessionID() {
        return sessionID;
    }

    @Override public void onCreate(SessionID sessionID) {
        this.sessionID = sessionID;
    }

    @Override
    public void onLogon(SessionID sessionID) {
        System.out.println("Client: session logon " + sessionID);
        loggedOn.set(true);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        System.out.println("Client: session logout " + sessionID);
        loggedOn.set(false);
    }

    @Override public void toAdmin(Message message, SessionID sessionID) {}
    @Override public void fromAdmin(Message message, SessionID sessionID) {}
    @Override public void toApp(Message message, SessionID sessionID) throws DoNotSend {}
    @Override public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {}
}
