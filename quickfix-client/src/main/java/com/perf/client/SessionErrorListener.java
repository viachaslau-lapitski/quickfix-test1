package com.perf.client;

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
    public void onMissedHeartBeat() {
        long count = transientErrors.incrementAndGet();
        errorLog.logTransient("session", count, new RuntimeException("Missed heartbeat"));
    }

    @Override
    public void onHeartBeatTimeout() {
        long count = transientErrors.incrementAndGet();
        errorLog.logTransient("session", count, new RuntimeException("Heartbeat timeout"));
    }

    @Override
    public void onDisconnect() {
        long count = channelBreaks.incrementAndGet();
        System.err.printf("[Client] Channel broken #%d (disconnect) — see errors.log%n", count);
        errorLog.logChannelBroken("session", count, "onDisconnect");
    }

    @Override
    public void onConnectException(Exception e) {
        long count = channelBreaks.incrementAndGet();
        System.err.printf("[Client] Channel broken #%d (%s) — see errors.log%n",
            count, e.getClass().getSimpleName());
        errorLog.logChannelBroken("session", count, e);
    }

    // ---- no-op lifecycle events ----

    @Override public void onConnect() {}
    @Override public void onLogon() {}
    @Override public void onLogout() {}
    @Override public void onReset() {}
    @Override public void onRefresh() {}
    @Override public void onResendRequestSent(int beginSeqNo, int endSeqNo, int currentEndSeqNo) {}
    @Override public void onSequenceResetReceived(int newSeqNo, boolean gapFillFlag) {}
    @Override public void onResendRequestSatisfied(int beginSeqNo, int endSeqNo) {}
}
