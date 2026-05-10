package com.perf.client;

import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

import java.util.concurrent.Semaphore;

/**
 * MINA filter that limits concurrent in-flight writes to {@code inflight} slots.
 *
 * <p>Installed via {@code SocketInitiator.setIoFilterChainBuilder} using {@code addFirst},
 * placing it before the SSL filter in the chain. The calling (producer) thread drives
 * {@code filterWrite} synchronously, so {@link Semaphore#acquire()} throttles the producer,
 * not the IO processor. The semaphore is sized to match {@code SSLHandlerG1.MAX_QUEUED_MESSAGES}
 * (64) to prevent {@code BufferOverflowException} when the SSL write queue is saturated.
 *
 * <p><b>Large-message note:</b> for messages exceeding one TLS record (&gt;16 383 bytes), MINA's
 * SSL layer fans out to N TLS records and fires {@code messageSent} once per record. This
 * means large messages release more permits than were acquired, so the effective ceiling
 * rises gradually. The throttle still prevents the initial burst from overflowing the queue.
 *
 * <p>A new filter instance is created per MINA session (via the IoFilterChainBuilder lambda),
 * so each session has its own semaphore. {@link #sessionClosed} releases all permits to
 * unblock any producer threads that are mid-acquire when the connection drops.
 */
class SSLWriteThrottleFilter extends IoFilterAdapter {

    static final String NAME = "ssl-throttle";

    private final int inflight;
    private volatile Semaphore semaphore;

    SSLWriteThrottleFilter(int inflight) {
        this.inflight = inflight;
        this.semaphore = new Semaphore(inflight);
    }

    @Override
    public void sessionCreated(NextFilter nextFilter, IoSession session) throws Exception {
        semaphore = new Semaphore(inflight);
        nextFilter.sessionCreated(session);
    }

    @Override
    public void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
        semaphore.release(inflight);
        nextFilter.sessionClosed(session);
    }

    @Override
    public void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        semaphore.acquire();
        nextFilter.filterWrite(session, writeRequest);
    }

    @Override
    public void messageSent(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        semaphore.release();
        nextFilter.messageSent(session, writeRequest);
    }
}
