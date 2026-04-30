package com.scale.server;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe pub/sub broadcaster for SSE clients.
 * <p>
 * Design rationale:
 * We only care about the LATEST weight reading.
 * If a browser tab is slow or paused, it should jump to the most recent
 * value rather than drain a backlog of stale measurements.
 * <p>
 * Mechanism:
 * publish()  → stores latest JSON payload, increments a monotonic sequence
 * counter, and wakes all threads blocked in waitNext().
 * waitNext() → blocks until sequence advances past the caller's last-seen
 * value, or until the timeout elapses (for heartbeat).
 * <p>
 * Each SSE handler thread keeps its own `lastSeq` cursor and calls waitNext()
 * in a loop:
 * - New data → send "data: ...\n\n"
 * - Timeout  → send ": heartbeat\n\n" (keeps TCP connection alive)
 */
public final class Broadcaster {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition available = lock.newCondition();

    /**
     * Latest published JSON payload. Null until first publish().
     */
    private volatile String payload = null;

    /**
     * Monotonic sequence counter.  Incremented on every publish().
     * SSE threads compare their last-seen value to detect new data.
     * Using long avoids any practical overflow concern.
     */
    private volatile long seq = 0L;

    // ─────────────────────────────────────────────────────────────────────────
    // Publisher side (called from scale-reader callback thread)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Publish a new JSON payload to all waiting SSE threads.
     * Non-blocking; returns immediately after waking all waiters.
     *
     * @param jsonPayload fully-formed JSON string to send as SSE data
     */
    public void publish(String jsonPayload) {
        lock.lock();
        try {
            payload = jsonPayload;
            seq++;
            available.signalAll();   // wake every SSE handler thread
        } finally {
            lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer side (called from SSE handler threads)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Block until a new payload is available or {@code timeoutMs} elapses.
     *
     * @param lastSeq   the sequence number last seen by this SSE client
     * @param timeoutMs maximum wait in milliseconds (use as heartbeat interval)
     * @return WaitResult with the new payload and updated seq,
     * or WaitResult with null payload (and unchanged seq) on timeout
     * @throws InterruptedException if the calling thread is interrupted
     */
    public WaitResult waitNext(long lastSeq, long timeoutMs) throws InterruptedException {
        lock.lock();
        try {
            // Fast path: new data already available
            if (seq != lastSeq) {
                return new WaitResult(payload, seq);
            }

            // Slow path: wait with deadline
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (seq == lastSeq) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;   // timed out → caller sends heartbeat
                }
                available.await(remaining, TimeUnit.MILLISECONDS);
            }

            if (seq != lastSeq) {
                return new WaitResult(payload, seq);
            }
            return new WaitResult(null, lastSeq);   // timeout

        } finally {
            lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result type
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returned by waitNext().
     *
     * @param payload null on timeout, otherwise the latest JSON string
     * @param seq     updated sequence counter (pass back as lastSeq next call)
     */
    public record WaitResult(String payload, long seq) {
    }
}
