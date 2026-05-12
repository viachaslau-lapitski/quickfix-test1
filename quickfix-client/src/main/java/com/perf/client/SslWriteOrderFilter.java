package com.perf.client;

import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * MINA filter placed HEAD-SIDE of {@code SslFilter} to serialize
 * {@code SSLHandlerG1.forward_writes()} calls from the MINA IO processor thread with those
 * from the application write thread, preventing {@code SSLException: Tag mismatch!}.
 *
 * <h3>Root cause</h3>
 * {@code SSLHandlerG1.write()} and {@code SSLHandlerG1.ack()} and {@code SSLHandlerG1.receive()}
 * each release their internal {@code synchronized} lock <em>before</em> calling
 * {@code forward_writes()}, which polls encrypted TLS records from an internal queue and submits
 * them toward the socket via {@code next.filterWrite()}. When two threads enter
 * {@code forward_writes()} concurrently they can forward records in the wrong order:
 * the application write thread (submitting via {@code write()}) and the MINA IO processor thread
 * (submitting via {@code ack()} on write-ACK or {@code receive()} on inbound data) can each
 * poll a record and then interleave their {@code next.filterWrite()} calls, causing the server's
 * SSLEngine to see TLS sequence numbers out of order →
 * {@code SSLException: Tag mismatch!}.
 *
 * <h3>Locking protocol</h3>
 * <ul>
 *   <li>This filter holds a per-session {@link ReentrantLock}.
 *   <li>{@link #messageSent}, {@link #messageReceived}, and {@link #sessionClosed} acquire the
 *       lock <em>before</em> forwarding the event toward tail-side filters, which include
 *       {@code SslFilter} and trigger {@code ack()}/{@code receive()}/{@code close()} +
 *       {@code forward_writes()}.
 *   <li>{@link SSLWriteThrottleFilter} acquires the same session lock in its
 *       {@code filterWrite()} before forwarding toward {@code SslFilter}, covering
 *       {@code write()} + {@code forward_writes()}.
 *   <li>{@code filterWrite()} in <em>this</em> filter is a pure pass-through (no lock) to
 *       avoid deadlock: {@code forward_writes()} calls {@code next.filterWrite(encryptedRecord)}
 *       back through this filter while the caller already holds the lock.
 * </ul>
 *
 * <h3>Reconnect safety</h3>
 * The lock is removed from the session map only <em>after</em> {@code nextFilter.sessionClosed()}
 * completes, ensuring {@code ssl.close()} → {@code forward_writes()} is covered. A new lock is
 * created for each new session via {@link #getLock}.
 */
class SslWriteOrderFilter extends IoFilterAdapter {

    static final String NAME = "ssl-write-order";

    private final ConcurrentHashMap<IoSession, ReentrantLock> locks = new ConcurrentHashMap<>();

    ReentrantLock getLock(IoSession session) {
        return locks.computeIfAbsent(session, s -> new ReentrantLock());
    }

    /**
     * Acquires the write-order lock before forwarding the ACK event to {@code SslFilter},
     * which triggers {@code SSLHandlerG1.ack()} + {@code forward_writes()}.
     */
    @Override
    public void messageSent(NextFilter nextFilter, IoSession session, WriteRequest writeRequest) throws Exception {
        ReentrantLock lock = getLock(session);
        lock.lock();
        try {
            nextFilter.messageSent(session, writeRequest);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Acquires the write-order lock before forwarding received data to {@code SslFilter},
     * which triggers {@code SSLHandlerG1.receive()} + {@code forward_writes()}.
     * Also re-entrant-safe if {@code SslFilter} decrypts inbound data and QFJ synchronously
     * sends a response (e.g. Heartbeat) on the same IO processor thread.
     */
    @Override
    public void messageReceived(NextFilter nextFilter, IoSession session, Object message) throws Exception {
        ReentrantLock lock = getLock(session);
        lock.lock();
        try {
            nextFilter.messageReceived(session, message);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Holds the write-order lock during session close so {@code ssl.close()} →
     * {@code forward_writes()} is still serialized. The lock is removed from the map
     * only after {@code nextFilter.sessionClosed()} completes.
     */
    @Override
    public void sessionClosed(NextFilter nextFilter, IoSession session) throws Exception {
        ReentrantLock lock = getLock(session);
        lock.lock();
        try {
            nextFilter.sessionClosed(session);
        } finally {
            locks.remove(session);
            lock.unlock();
        }
    }

    // filterWrite: pure pass-through — no lock.
    // forward_writes() calls next.filterWrite(encryptedRecord) back through this filter
    // while the calling thread already holds the lock; acquiring it here would deadlock
    // for non-reentrant callers (or add redundant lock count for reentrant ones).
}
