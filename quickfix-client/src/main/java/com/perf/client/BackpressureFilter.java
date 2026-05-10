package com.perf.client;

import org.apache.mina.core.filterchain.IoFilterAdapter;
import org.apache.mina.core.session.IoSession;
import org.apache.mina.core.write.WriteRequest;

import java.util.concurrent.Semaphore;

/**
 * MINA IoFilter that releases one back-pressure permit each time MINA confirms
 * a logical write request has been fully sent to the OS socket buffer.
 *
 * <p>Install with {@code chain.addLast()} so it sits after the SSL and codec
 * filters. At that position {@code messageSent()} fires exactly once per
 * {@code Session.sendToTarget()} call: the SSL filter aggregates per-TLS-record
 * acks into the original application-level {@link WriteRequest} before the event
 * reaches us.
 *
 * <p>Paired with a {@code Semaphore.acquire()} before each
 * {@code Session.sendToTarget()}, this limits how many messages can be
 * concurrently queued inside MINA's write pipeline and prevents the concurrent
 * {@code forward_writes()} race in MINA 2.2.x {@code SSLHandlerG1} that causes
 * {@code SSLException: Tag mismatch!} when messages exceed one TLS record.
 *
 * <p>Re-install with a fresh semaphore reference on every reconnect (see
 * {@link ClientApplication#onLogon}).
 */
class BackpressureFilter extends IoFilterAdapter {

    static final String NAME = "qfj-backpressure";

    private final Semaphore semaphore;

    BackpressureFilter(Semaphore semaphore) {
        this.semaphore = semaphore;
    }

    @Override
    public void messageSent(NextFilter nextFilter, IoSession session,
                            WriteRequest writeRequest) throws Exception {
        semaphore.release();
        nextFilter.messageSent(session, writeRequest);
    }
}
