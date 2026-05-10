package com.perf.client;

import quickfix.SessionID;
import quickfix.SessionStateListener;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hooks into QuickFIX/J session lifecycle to count transient errors and channel breaks,
 * logging details to {@link ErrorLog} so the console stays clean.
 *
 * <p>Register via {@code Session.lookupSession(sessionID).addStateListener(this)} in
 * {@link ClientApplication#onCreate}.
 *
 * <p>Transient errors (session still alive):
 * <ul>
 *   <li>{@link #onMissedHeartBeat()} — remote heartbeat overdue but session not yet closed</li>
 *   <li>{@link #onHeartBeatTimeout()} — heartbeat timeout threshold crossed</li>
 * </ul>
 *
 * <p>Channel breaks (connection severed):
 * <ul>
 *   <li>{@link #onDisconnect()} — TCP connection dropped</li>
 *   <li>{@link #onConnectException(Exception)} — connection attempt failed</li>
 * </ul>
 */
public class SessionErrorListener implements SessionStateListener {

    private final AtomicLong transientErrors;
    private final AtomicLong channelBreaks;
    private final ErrorLog errorLog;

    public SessionErrorListener(AtomicLong transientErrors, AtomicLong channelBreaks,
                                ErrorLog errorLog) {
        this.transientErrors = transientErrors;
        this.channelBreaks   = channelBreaks;
        this.errorLog        = errorLog;
    }

    @Override
    public void onMissedHeartBeat(SessionID sessionID) {
        long count = transientErrors.incrementAndGet();
        errorLog.logTransient(String.valueOf(sessionID), count, new RuntimeException("Missed heartbeat"));
    }

    @Override
    public void onHeartBeatTimeout(SessionID sessionID) {
        long count = transientErrors.incrementAndGet();
        errorLog.logTransient(String.valueOf(sessionID), count, new RuntimeException("Heartbeat timeout"));
    }

    @Override
    public void onDisconnect(SessionID sessionID) {
        long count = channelBreaks.incrementAndGet();
        System.err.printf("[Client] Channel broken #%d (disconnect) — see errors.log%n", count);
        errorLog.logChannelBroken(String.valueOf(sessionID), count, "onDisconnect");
    }

    @Override
    public void onConnectException(SessionID sessionID, Exception e) {
        long count = channelBreaks.incrementAndGet();
        System.err.printf("[Client] Channel broken #%d (%s) — see errors.log%n",
            count, e.getClass().getSimpleName());
        errorLog.logChannelBroken(String.valueOf(sessionID), count, e);
    }

    // ---- no-op lifecycle events ----

    @Override public void onConnect(SessionID sessionID) {}
    @Override public void onLogon(SessionID sessionID) {}
    @Override public void onLogout(SessionID sessionID) {}
    @Override public void onReset(SessionID sessionID) {}
    @Override public void onRefresh(SessionID sessionID) {}
    @Override public void onResendRequestSent(SessionID sessionID, int beginSeqNo, int endSeqNo, int currentEndSeqNo) {}
    @Override public void onSequenceResetReceived(SessionID sessionID, int newSeqNo, boolean gapFillFlag) {}
    @Override public void onResendRequestSatisfied(SessionID sessionID, int beginSeqNo, int endSeqNo) {}
}
