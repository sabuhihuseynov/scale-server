package com.scale.serial;

import com.scale.model.IndicatorType;
import com.scale.model.WeightReading;
import com.scale.protocol.ScaleProtocol;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Orchestrates CommPort + a polling thread.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * Poll-loop design (Type 1 / Type 2):
 * <p>
 * The poll loop follows a pattern where it fires every 500 ms:
 * timer_elapsed  → clear Packet, send poll byte / command
 * data_received  → stop timer, parse, finally re-enable
 * <p>
 * Effect: the next poll goes out exactly POLL_INTERVAL after the last
 * response was fully processed.  No poll overlaps with in-flight data.
 * <p>
 * Java equivalent:
 * Use a SynchronousQueue<Boolean> as a single-slot "got-data" flag.
 * Poll loop:  send command → offer(TOKEN, POLL_INTERVAL) to wait for ack
 * onData:     poll() to drain any pending → parse → queue.offer(TOKEN)
 * Result:     loop wakes as soon as data arrives, or retries after timeout.
 * <p>
 * ─────────────────────────────────────────────────────────────────────────
 * Type 5:
 * CommPort burst-reads every 300 ms and fires onData.
 * No poll command is sent; the poll loop simply sleeps POLL_INTERVAL
 * and lets onData drive the readings.
 * ─────────────────────────────────────────────────────────────────────────
 */
public final class ScaleReader {

    private static final Logger log = Logger.getLogger(ScaleReader.class.getName());

    /**
     * Poll interval in milliseconds.
     */
    private static final long POLL_INTERVAL_MS = 500L;

    // Sentinel value pushed into gotData queue when a response arrives
    private static final Boolean TOKEN = Boolean.TRUE;

    private final String deviceName;
    private final IndicatorType type;
    private final double scaleToKq;
    private final CommPort commPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread pollThread;

    /**
     * SynchronousQueue used as a single-slot, non-blocking signal between
     * onData() (producer) and the poll loop (consumer).
     * SynchronousQueue.offer() never blocks; poll(timeout) is our wait.
     */
    private final SynchronousQueue<Boolean> gotData = new SynchronousQueue<>();

    // ── Callbacks — set before calling start() ────────────────────────────────
    public volatile Consumer<WeightReading> onWeight;
    public volatile Consumer<Boolean> onStatus;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public ScaleReader(String portName, int baud, IndicatorType type,
                       String deviceName, double scaleToKq) {
        this.deviceName = deviceName;
        this.type = type;
        this.scaleToKq = scaleToKq;

        this.commPort = new CommPort(portName, baud, type);
        this.commPort.onData = this::onData;
        this.commPort.onConnect = this::onConnect;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start the serial port and the polling thread.
     */
    public void start() {
        commPort.open();
        running.set(true);
        pollThread = new Thread(this::scalingLoop, "poll:" + deviceName);
        pollThread.setDaemon(true);
        pollThread.start();
        log.info("[" + deviceName + "] ScaleReader started (type=" + type + ")");
    }

    /**
     * Stop polling and close the serial port.
     */
    public void stop() {
        running.set(false);
        commPort.close();
        Thread t = pollThread;
        if (t != null) {
            t.interrupt();
        }
        log.info("[" + deviceName + "] ScaleReader stopped");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Polling loop (runs on pollThread)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Main polling loop.
     * <p>
     * Each iteration:
     * 1. Drain any stale "got data" token (clear the signal).
     * 2. Send the poll command if TX buffer is empty.
     * 3. Wait up to POLL_INTERVAL for a response token from onData().
     * 4. If token received → data was parsed in onData(); restart loop.
     * 5. If timeout      → no response; send next poll command.
     */
    private void scalingLoop() {
        while (running.get()) {
            try {
                // Ensure port is open
                if (!commPort.isOpen()) {
                    Thread.sleep(POLL_INTERVAL_MS);
                    continue;
                }

                // Clear any stale token from a previous round
                gotData.poll();

                // Send poll command (Type 1 / Type 2 only)
                if (!commPort.bufferHasData()) {
                    if (type == IndicatorType.CAS_V1) {
                        commPort.writeByte(0x01);       // byte 0x01 poll
                    } else if (type == IndicatorType.CAS_V2) {
                        commPort.writeAscii("01RW\r");  // device 01, Read Weight
                    }
                    // TYPE5: no command — device sends continuously
                }

                // Block until onData() signals a response, or POLL_INTERVAL elapses.
                gotData.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.fine("[" + deviceName + "] poll thread interrupted");
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.warning("[" + deviceName + "] poll error: " + e.getMessage());
                }
                sleepQuiet(POLL_INTERVAL_MS);
            }
        }
        log.info("[" + deviceName + "] poll thread exited");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data receive callback (called from CommPort's rxThread)
    // ─────────────────────────────────────────────────────────────────────────

    private void onData(String raw) {
        // Signal the poll loop: "response received — don't re-poll yet."
        // offer() is non-blocking; if the loop hasn't reached poll() yet, the
        // token is simply dropped on the next gotData.poll() in the loop.
        gotData.offer(TOKEN);

        // ── Normal weight parse ───────────────────────────────────────────────
        WeightReading reading = ScaleProtocol.parse(raw, type, deviceName, scaleToKq);
        if (reading != null) {
            emit(reading);
        } else {
            log.fine("[" + deviceName + "] packet discarded (parse returned null): "
                    + raw.replace("\r", "\\r").replace("\n", "\\n"));
        }
    }

    private void onConnect(boolean connected) {
        Consumer<Boolean> cb = onStatus;
        if (cb != null) {
            try {
                cb.accept(connected);
            } catch (Exception e) {
                log.warning("onStatus callback threw: " + e.getMessage());
            }
        }
    }

    private void emit(WeightReading r) {
        Consumer<WeightReading> cb = onWeight;
        if (cb != null) {
            try {
                cb.accept(r);
            } catch (Exception e) {
                log.warning("onWeight callback threw: " + e.getMessage());
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Commands
    // ─────────────────────────────────────────────────────────────────────────

    // ─────────────────────────────────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────────────────────────────────

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
