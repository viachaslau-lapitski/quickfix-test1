package com.perf.client;

import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MINA filter that limits concurrent in-flight SSL writes to {@code inflight} slots, preventing
 * {@code BufferOverflowException} when MINA's SSL handler queue ({@code mEncodeQueue},
 * {@code MAX_QUEUED_MESSAGES = 64}) is saturated.
 *
 * <p>Installed via {@code SocketInitiator.setIoFilterChainBuilder} using
 * {@code addAfter(sslFilterName, ...)} so the filter sits between the ssl and codec entries
 * in the chain list. Because MINA write events travel tail→head, this places the throttle
 * <em>before</em> the ssl filter in the write-processing path:
 * {@code codec → throttle → ssl → SslWriteOrderFilter → socket}.
 *
 * <p><b>Write-order lock:</b> {@link #filterWrite} acquires the per-session
 * {@link java.util.concurrent.locks.ReentrantLock} from {@link SslWriteOrderFilter} before
 * delegating to {@code SslFilter.filterWrite()}. This serializes {@code SSLHandlerG1.write()} +
 * {@code forward_writes()} (write thread) against {@code SSLHandlerG1.ack()} +
 * {@code forward_writes()} (IO processor thread, via {@code SslWriteOrderFilter.messageSent})
 * and {@code SSLHandlerG1.receive()} + {@code forward_writes()} (IO processor thread, via
 * {@code SslWriteOrderFilter.messageReceived}), preventing TLS records from being forwarded
 * to the socket out of order.
 *
 * <p><b>Usage:</b> callers acquire the permit via {@link #acquire()} <em>before</em> calling
 * {@code Session.sendToTarget()}, not inside the MINA pipeline. This matches the pattern used
 * in the reference MINA PoC (mina-test1) and avoids blocking QFJ's Message Processor thread
 * inside the filter chain. The filter's {@link #messageSent} releases one permit once the
 * underlying write completes.
 *
 * <p><b>Semaphore balance:</b> MINA's {@code SslFilter.messageSent} propagates the original
 * {@code WriteRequest} upward only when the <em>last</em> encrypted chunk completes
 * (intermediate TLS-record chunks carry {@code originalRequest == self} and are suppressed).
 * Therefore {@code messageSent} fires exactly <em>once</em> per {@code session.write()} call,
 * regardless of message size (even multi-TLS-record messages). One acquire by the caller and
 * one release in {@code messageSent} keeps the semaphore perfectly balanced.
 *
 * <p><b>Reconnect safety:</b> a single filter instance is shared across all reconnects.
 * {@link #sessionClosed} drains all current permits and releases exactly {@code inflight} new
 * ones, resetting the semaphore cleanly regardless of how many permits were leaked when the
 * previous connection dropped. Any thread blocked in {@link #acquire()} will unblock and should
 * check the session state before proceeding.
 */
class SSLWriteThrottleFilter extends IoFilterAdapter {

    static final String NAME = "ssl-throttle";

    private final int inflight;
    private final Semaphore semaphore;
    private final SslWriteOrderFilter orderFilter;

    SSLWriteThrottleFilter(int inflight, SslWriteOrderFilter orderFilter) {
        this.inflight = inflight;
        this.semaphore = new Semaphore(inflight);
        this.orderFilter = orderFilter;
    }

    /** Acquire one permit before calling {@code Session.sendToTarget()}. */
    void acquire() throws InterruptedException {
        semaphore.acquire();
    }

    /** Release one permit (e.g. when skipping a send due to offline session). */
    void release() {
        semaphore.release();
    }

    @Override
    public void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
        // Reset semaphore to exactly inflight permits so the next connection starts clean,
        // regardless of how many permits were lost when this connection dropped.
        semaphore.drainPermits();
        semaphore.release(inflight);
        nextFilter.sessionClosed(session);
    }

    @Override
    public void filterWrite(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        // Acquire the per-session write-order lock to serialize ssl.write.forward_writes()
        // (this thread) against ssl.ack.forward_writes() and ssl.receive.forward_writes()
        // (MINA IO processor thread, covered by SslWriteOrderFilter.messageSent/messageReceived).
        // Both forward_writes() paths must be mutually exclusive to preserve TLS record order.
        ReentrantLock lock = orderFilter.getLock(session);
        lock.lock();
        try {
            nextFilter.filterWrite(session, writeRequest);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void messageSent(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        semaphore.release();
        nextFilter.messageSent(session, writeRequest);
    }
}
