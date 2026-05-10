package com.perf.client;

import org.apache.mina.core.session.IoSession;
import quickfix.*;

import java.lang.reflect.Method;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClientApplication implements Application {

    private final AtomicBoolean loggedOn = new AtomicBoolean(false);
    private volatile SessionID sessionID;
    private final SessionErrorListener errorListener;
    private final int inflight;
    private final Semaphore writeSem;

    public ClientApplication(SessionErrorListener errorListener, int inflight, Semaphore writeSem) {
        this.errorListener = errorListener;
        this.inflight = inflight;
        this.writeSem = writeSem;
    }

    public boolean isLoggedOn() {
        return loggedOn.get();
    }

    public SessionID getSessionID() {
        return sessionID;
    }

    @Override public void onCreate(SessionID sessionID) {
        this.sessionID = sessionID;
        Session session = Session.lookupSession(sessionID);
        if (session != null) {
            session.addStateListener(errorListener);
        }
    }

    @Override
    public void onLogon(SessionID sessionID) {
        System.out.println("Client: session logon " + sessionID);
        installBackpressureFilter(sessionID);
        // Reset semaphore each connection: clear stale permits from old session,
        // then refill to inflight so producers unblock immediately.
        writeSem.drainPermits();
        writeSem.release(inflight);
        loggedOn.set(true);
    }

    @Override
    public void onLogout(SessionID sessionID) {
        System.out.println("Client: session logout " + sessionID);
        loggedOn.set(false);
        // Do not release here — producers already holding permits will get
        // sendToTarget() returning false and release on their own.
        // Producers blocked in acquire() will unblock on the next onLogon().
    }

    /**
     * Reaches into QFJ's MINA session via the public {@code getResponder()} API
     * plus one reflection call to the package-private {@code getIoSession()}, then
     * appends {@link BackpressureFilter} at the end of the MINA filter chain.
     *
     * <p>{@code addLast} positions the filter after the SSL and codec filters.
     * {@code messageSent()} propagation goes HEAD→TAIL, so our filter fires after
     * SSL has aggregated per-TLS-record acks into the original application-level
     * write request — exactly one release per {@code Session.sendToTarget()} call.
     */
    private void installBackpressureFilter(SessionID sessionID) {
        try {
            Session qfj = Session.lookupSession(sessionID);
            if (qfj == null) return;
            Responder responder = qfj.getResponder();
            if (responder == null) return;

            Method getIoSession = responder.getClass().getDeclaredMethod("getIoSession");
            getIoSession.setAccessible(true);
            IoSession mina = (IoSession) getIoSession.invoke(responder);

            var chain = mina.getFilterChain();
            if (chain.contains(BackpressureFilter.NAME)) {
                chain.remove(BackpressureFilter.NAME);
            }
            // addLast: after SSL and codec — sees aggregated per-message messageSent,
            // not individual TLS-record completions.
            chain.addLast(BackpressureFilter.NAME, new BackpressureFilter(writeSem));
        } catch (Exception e) {
            System.err.println("[Client] Warning: could not install backpressure filter: " + e);
        }
    }

    @Override public void toAdmin(Message message, SessionID sessionID) {}
    @Override public void fromAdmin(Message message, SessionID sessionID) {}
    @Override public void toApp(Message message, SessionID sessionID) throws DoNotSend {}
    @Override public void fromApp(Message message, SessionID sessionID)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {}
}
