package com.scale.serial;

import com.fazecast.jSerialComm.SerialPort;
import com.scale.model.IndicatorType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Thread-safe serial port wrapper with automatic reconnect.
 * <p>
 * Lifecycle:
 * open()  → starts background receive thread, reconnects on error
 * close() → stops thread, closes port
 * write() → thread-safe port write (byte or ASCII string)
 * <p>
 * Two receive strategies, selected by IndicatorType:
 * readLines() — Type 1 / Type 2: readline loop, fires onData per line
 * readBurst() — Type 5: sleep 300 ms, drain available bytes, fires onData
 * <p>
 * Callbacks (set before open()):
 * onData    Consumer<String>  — fired for every complete packet
 * onConnect Consumer<Boolean> — fired on connect (true) / disconnect (false)
 */
public final class CommPort {

    private static final Logger log = Logger.getLogger(CommPort.class.getName());

    /**
     * How long to wait before retrying after a port error.
     */
    private static final int RECONNECT_DELAY_MS = 5_000;

    /**
     * Read timeout for readline mode: 1 s means readline() returns within 1 s
     * even if no newline arrived, keeping the thread responsive to stop().
     */
    private static final int READ_TIMEOUT_MS = 1_000;

    /**
     * How long to sleep between burst reads.
     */
    private static final int BURST_SLEEP_MS = 300;

    /**
     * Pre-allocated receive buffer for burst mode.
     * Type 5 frames are 21 bytes; at 9600 baud over 300 ms a device can send at most
     * ~360 bytes, so 512 comfortably covers several frames with room to spare.
     */
    private static final int BURST_BUF_SIZE = 512;

    private final String portName;
    private final int baud;
    private final IndicatorType type;

    /**
     * Volatile so close() from any thread is immediately visible to runLoop().
     */
    private volatile SerialPort activePort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread rxThread;

    // ── Callbacks — set these before calling open() ───────────────────────────
    public volatile Consumer<String> onData;
    public volatile Consumer<Boolean> onConnect;

    public CommPort(String portName, int baud, IndicatorType type) {
        this.portName = portName;
        this.baud = baud;
        this.type = type;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start the receive thread. Safe to call multiple times (idempotent).
     */
    public synchronized void open() {
        if (running.getAndSet(true)) {
            return;   // already running
        }
        rxThread = new Thread(this::runLoop, "com:" + portName);
        rxThread.setDaemon(true);
        rxThread.start();
        log.info("[" + portName + "] receive thread started");
    }

    /**
     * Stop the receive thread and close the serial port.
     */
    public synchronized void close() {
        running.set(false);
        SerialPort p = activePort;
        activePort = null;
        if (p != null && p.isOpen()) {
            p.closePort();
            log.info("[" + portName + "] port closed");
        }
        if (rxThread != null) {
            rxThread.interrupt();
        }
    }

    public boolean isOpen() {
        SerialPort p = activePort;
        return p != null && p.isOpen();
    }

    /**
     * Write raw bytes.
     * Thread-safe; returns false if port is not open.
     */
    public boolean write(byte[] data) {
        SerialPort p = activePort;
        if (p == null || !p.isOpen()) {
            log.warning("[" + portName + "] write() called but port not open");
            return false;
        }
        try {
            int n = p.writeBytes(data, data.length);
            return n == data.length;
        } catch (Exception e) {
            log.log(Level.WARNING, "[" + portName + "] write error", e);
            return false;
        }
    }

    /**
     * Write a single byte (the poll byte).
     */
    public boolean writeByte(int b) {
        return write(new byte[] {(byte) b});
    }

    /**
     * Write an ASCII string.
     */
    public boolean writeAscii(String s) {
        return write(s.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * True if there are bytes waiting to be transmitted.
     */
    public boolean bufferHasData() {
        SerialPort p = activePort;
        if (p == null || !p.isOpen()) {
            return false;
        }
        try {
            return p.bytesAwaitingWrite() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Receive loop (runs on rxThread)
    // ─────────────────────────────────────────────────────────────────────────

    private void runLoop() {
        while (running.get()) {
            SerialPort p = null;
            try {
                p = openPort();
                if (p == null) {
                    // Port not found / could not open — wait before retrying
                    sleepOrExit(RECONNECT_DELAY_MS);
                    continue;
                }

                activePort = p;
                notifyConnect(true);
                log.info("[" + portName + "] opened @ " + baud + " baud (type=" + type + ")");

                // Delegate to the appropriate read strategy
                if (type == IndicatorType.TYPE5) {
                    readBurst(p);
                } else {
                    readLines(p);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                if (running.get()) {
                    log.log(Level.WARNING, "[" + portName + "] unexpected error, reconnecting", e);
                }
            } finally {
                // Always clean up the port before looping
                silentClose(p);
                if (running.get()) {
                    notifyConnect(false);
                    sleepOrExitQuiet(RECONNECT_DELAY_MS);
                }
            }
        }
        log.info("[" + portName + "] receive loop exited");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Port open
    // ─────────────────────────────────────────────────────────────────────────

    private SerialPort openPort() {
        // getCommPort() always returns a non-null object; it creates a port handle
        // regardless of whether the port physically exists. The real failure is
        // openPort() returning false below — that is the only check needed.
        SerialPort p = SerialPort.getCommPort(portName);

        // Serial parameters: 9600, 8N1
        p.setBaudRate(baud);
        p.setNumDataBits(8);
        p.setNumStopBits(SerialPort.ONE_STOP_BIT);
        p.setParity(SerialPort.NO_PARITY);

        // Readline mode: blocking with a read timeout so threads are interruptible.
        // Burst mode: non-blocking (we sleep manually between polls).
        if (type.isLineBased()) {
            p.setComPortTimeouts(
                    SerialPort.TIMEOUT_READ_BLOCKING,
                    READ_TIMEOUT_MS,   // read timeout
                    0                  // write timeout (0 = non-blocking)
            );
        } else {
            p.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
        }

        if (!p.openPort()) {
            log.warning("[" + portName + "] openPort() returned false — port busy or missing?");
            return null;
        }

        return p;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Read strategies
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Readline loop for CAS Type 1 and Type 2.
     * Blocks until a newline arrives or READ_TIMEOUT_MS elapses.
     */
    private void readLines(SerialPort p) throws IOException {
        // BufferedReader.readLine() handles \n, \r, \r\n automatically
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.US_ASCII))) {

            while (running.get() && p.isOpen()) {
                String line;
                try {
                    line = reader.readLine(); // blocks up to READ_TIMEOUT_MS
                } catch (IOException e) {
                    if (running.get()) {
                        log.warning("[" + portName + "] readline error: " + e.getMessage());
                    }
                    break;
                }

                if (line == null) {
                    // stream closed
                    break;
                }

                // Strip any stray \r characters
                String trimmed = line.replace("\r", "").strip();

                if (!trimmed.isEmpty()) {
                    fireOnData(trimmed);
                }
            }
        }
    }

    /**
     * Burst-read loop for Type 5.
     * <p>
     * The device streams data continuously; we drain the buffer every 300 ms.
     */
    private void readBurst(SerialPort p) throws InterruptedException {
        byte[] buf = new byte[BURST_BUF_SIZE];
        while (running.get() && p.isOpen()) {
            Thread.sleep(BURST_SLEEP_MS);

            int available = p.bytesAvailable();
            if (available <= 0) {
                continue;
            }

            int toRead = Math.min(available, buf.length);
            int read = p.readBytes(buf, toRead);
            if (read <= 0) {
                continue;
            }

            String text = new String(buf, 0, read, StandardCharsets.US_ASCII);
            fireOnData(text);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void fireOnData(String data) {
        Consumer<String> cb = onData;
        if (cb != null) {
            try {
                cb.accept(data);
            } catch (Exception e) {
                log.log(Level.WARNING, "[" + portName + "] onData callback threw", e);
            }
        }
    }

    private void notifyConnect(boolean up) {
        Consumer<Boolean> cb = onConnect;
        if (cb != null) {
            try {
                cb.accept(up);
            } catch (Exception e) {
                log.log(Level.WARNING, "[" + portName + "] onConnect callback threw", e);
            }
        }
    }

    private void silentClose(SerialPort p) {
        SerialPort cur = activePort;
        activePort = null;
        // Close whichever reference we have
        for (SerialPort target : new SerialPort[] {cur, p}) {
            if (target != null && target.isOpen()) {
                try {
                    target.closePort();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void sleepOrExit(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    private void sleepOrExitQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
