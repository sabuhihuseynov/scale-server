# Scale Server — Developer Guide

This document is a detailed technical reference for developers who will read, maintain, or extend the scale-server codebase. It covers every component, the threading model, synchronization strategy, data flow from serial byte to SSE event, and the reasoning behind key design decisions.

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Package Structure](#2-package-structure)
3. [Startup Sequence](#3-startup-sequence)
4. [Component Reference](#4-component-reference)
   - [Main](#41-main)
   - [AppConfig](#42-appconfig)
   - [IndicatorType](#43-indicatortype)
   - [WeightReading](#44-weightreading)
   - [ScaleProtocol](#45-scaleprotocol)
   - [CommPort](#46-commport)
   - [PortScanner](#47-portscanner)
   - [ScaleReader](#48-scalereader)
   - [SseServer](#49-sseserver)
   - [StatusWindow](#410-statuswindow)
5. [Threading Model](#5-threading-model)
6. [Synchronization Strategy](#6-synchronization-strategy)
7. [Data Flow: Serial Byte to SSE Event](#7-data-flow-serial-byte-to-sse-event)
8. [Serial Protocol Formats](#8-serial-protocol-formats)
9. [Auto-Reconnect and Port Scanning](#9-auto-reconnect-and-port-scanning)
10. [SSE Server Internals](#10-sse-server-internals)
11. [Error Handling Philosophy](#11-error-handling-philosophy)
12. [Build and Packaging](#12-build-and-packaging)

---

## 1. Project Overview

Scale Server reads weight measurements from an industrial weighbridge scale connected via RS-232/USB serial and streams each reading in real time to a browser or frontend via Server-Sent Events (SSE).

**Core challenges the code solves:**

- **Protocol translation** — The scale speaks a proprietary binary/ASCII serial protocol. The server translates each packet into a JSON object.
- **Serial reliability** — USB-to-serial adapters disconnect. The server reconnects automatically within 5 seconds without a restart.
- **Port unknown** — In a fresh deployment the operator may not know the COM port. `PortScanner` probes every port until the scale responds.
- **Long-lived HTTP connection** — SSE requires the HTTP response body to stay open indefinitely. JDK 21's built-in `HttpServer` closes the body immediately, so the server uses raw `ServerSocket` instead.
- **Single-operator model** — A weighbridge has one display. The SSE server intentionally supports exactly one active client at a time; a second connection evicts the first.

---

## 2. Package Structure

```
com.scale
├── Main.java                   Entry point and application wiring
├── config
│   └── AppConfig.java          Immutable configuration value object
├── model
│   ├── IndicatorType.java      Protocol variant enum (CAS_V1, CAS_V2, TYPE5)
│   └── WeightReading.java      Immutable snapshot of one scale measurement
├── protocol
│   └── ScaleProtocol.java      Stateless packet parser
├── serial
│   ├── CommPort.java           Serial port wrapper with auto-reconnect
│   ├── PortScanner.java        COM port auto-discovery
│   └── ScaleReader.java        Poll orchestrator (CommPort + polling thread)
├── server
│   └── SseServer.java          Raw-socket HTTP/SSE server
└── ui
    └── StatusWindow.java       JavaFX operator window
```

---

## 3. Startup Sequence

Understanding the startup order is critical for debugging and for knowing which components are live at any given moment.

```
1. Main.main()
   │
   ├─ AppConfig.load()
   │    Reads scale.properties from working directory.
   │    Falls back to hardcoded defaults if absent.
   │
   ├─ Logging setup
   │    Custom SimpleFormatter (timestamp + level + logger name).
   │    Suppresses jSerialComm (→ WARNING) and JavaFX (→ SEVERE) noise.
   │    If debug=true, also attaches a FileHandler writing to scale_debug.log.
   │
   ├─ StatusWindow creation (skipped on headless servers)
   │    Platform.startup() launches the JavaFX Application Thread.
   │    CountDownLatch ensures the FX thread is ready before main continues.
   │    main thread blocks on awaitSelection() until operator clicks Start.
   │    AppConfig is updated with port/type/debug from the window selection.
   │
   ├─ SseServer.start()
   │    Binds 127.0.0.1:8435 immediately.
   │    The browser can open the SSE stream before the scale is found.
   │    Spawns the sse-accept daemon thread.
   │
   ├─ scale-init daemon thread
   │    Resolves the serial port:
   │      - If portName == "AUTO" → PortScanner.scan() (blocks until found)
   │      - If portName is explicit → use it directly
   │    Loops with 5-second retry until a port is found.
   │    Creates ScaleReader with onWeight and onStatus callbacks wired.
   │    Calls ScaleReader.start() → opens CommPort → starts poll thread.
   │
   ├─ Runtime.addShutdownHook()
   │    On Ctrl+C / SIGTERM: calls reader.stop() then sseServer.stop().
   │
   └─ Thread.currentThread().join()
        Main thread parks here indefinitely.
        All real work runs on daemon threads.
        When main exits (via Ctrl+C), daemon threads are killed automatically.
```

---

## 4. Component Reference

### 4.1 Main

**File:** `com/scale/Main.java`

`Main` is a pure wiring layer. It creates components and connects them with lambda callbacks. It performs no I/O directly.

**Key static field:**

```java
private static final Gson GSON = new Gson();
```

`Gson` is thread-safe after construction. The single instance is shared across all callback threads.

**onWeight callback (wired in Main, called by ScaleReader):**

```java
reader.onWeight = reading -> {
    String json = GSON.toJson(reading);    // WeightReading → JSON
    sseServer.publish(json);               // push to SSE stream
    window.updateScale(true, portName);    // update UI indicator
};
```

**onStatus callback:**

```java
reader.onStatus = connected -> {
    String json = buildStatusJson(connected, portName);
    sseServer.publish(json);
    window.updateScale(connected, portName);
};
```

**Why park the main thread?**

All worker threads are daemon threads. Daemon threads are killed when the JVM exits — and the JVM exits when no non-daemon threads remain. If `main()` returned immediately, the JVM would exit, killing every daemon thread. Parking on `join()` keeps the JVM alive. Ctrl+C interrupts `main`, the shutdown hook fires and stops everything cleanly.

---

### 4.2 AppConfig

**File:** `com/scale/config/AppConfig.java`

An immutable value object. Every field is `final`. Mutation produces a new instance.

| Field | Type | Default | Description |
|-------|------|---------|-------------|
| `portName` | String | `"AUTO"` | COM port or `"AUTO"` for scanning |
| `baud` | int | `9600` | Baud rate |
| `indicatorType` | IndicatorType | `CAS_V1` | Protocol variant |
| `scaleToKq` | double | `1.0` | Multiplier to convert device weight to kg |
| `httpPort` | int | `8435` | HTTP/SSE server port |
| `deviceName` | String | `""` | Human-readable label included in SSE events |
| `debug` | boolean | `false` | Enables FINE-level byte trace logging |

**`load()`** — static factory. Reads `scale.properties` via `java.util.Properties`. Each value has a dedicated parser (`parseIntOrDefault`, `parseDblOrDefault`) that catches `NumberFormatException`, logs a warning, and returns the default. The app never crashes on a bad config value.

**`withUiSettings(port, type, debug)`** — returns a new `AppConfig` with three fields replaced by the operator's window selection. Used in `Main` after `awaitSelection()` returns.

---

### 4.3 IndicatorType

**File:** `com/scale/model/IndicatorType.java`

```java
public enum IndicatorType {
    CAS_V1(1),   // poll with byte 0x01
    CAS_V2(2),   // poll with ASCII "01RW\r"
    TYPE5(5);    // no poll — device pushes continuously
}
```

**`fromCode(int)`** — reverse lookup used when parsing `indicator.type` from config. Throws `IllegalArgumentException` on unknown code — bad config is caught at startup, not silently ignored.

**`isLineBased()`** — returns `true` for CAS_V1 and CAS_V2. Controls which read strategy `CommPort` uses (readline vs burst). Controls whether `ScaleReader` sends a poll command.

---

### 4.4 WeightReading

**File:** `com/scale/model/WeightReading.java`

A plain immutable object. All fields are `final`. It is the unit of transfer between the serial layer and the SSE layer.

| Field | Type | Description |
|-------|------|-------------|
| `device` | String | Label (deviceName from config or port name) |
| `weight` | double | Weight in kg, rounded to 3 decimal places |
| `unit` | String | `"kg"` or `"lb"` as reported by device; defaults to `"kg"` |
| `stable` | boolean | `true` if device reports stable reading |
| `overload` | boolean | `true` if weight exceeds scale capacity |
| `weightType` | String | `"gross"` or `"net"`; defaults to `"gross"` |
| `raw` | String | Original bytes for debugging |
| `timestamp` | String | ISO-8601 instant captured in constructor |

The `timestamp` reflects when the server parsed the packet, not when the scale measured the weight. There is no way to recover the scale's internal measurement time — the serial protocols do not include it.

Because every field is `final`, the Java Memory Model guarantees safe publication: once a reference to a `WeightReading` escapes the constructor, every other thread that holds that reference sees all fields fully initialized. No `synchronized` is needed when passing it between threads.

---

### 4.5 ScaleProtocol

**File:** `com/scale/protocol/ScaleProtocol.java`

Stateless packet parser. All methods are static. No instance state.

**Entry point:**

```java
public static WeightReading parse(String packet, IndicatorType type,
                                   String deviceName, double scaleToKq)
```

Dispatches on `type` using a Java 21 switch expression:

```java
return switch (type) {
    case TYPE5          -> parseType5(packet, deviceName, scaleToKq);
    case CAS_V1, CAS_V2 -> parseCas(packet, deviceName, scaleToKq);
};
```

Returns `null` if the packet is malformed or does not contain a usable weight.

---

**CAS parsing (`parseCas`):**

The CAS NT-500 format is a fixed-width 20-character ASCII line. Fields are extracted by hard-coded byte offsets (no splitting, no regex):

```
Position  Length  Field
0–1       2       Status: "ST"=stable / "US"=unstable / "OL"=overload
2         1       ","
3–4       2       Weight type: "GS"=gross / "NT"=net
5         1       ","
6         1       Device ID character
7         1       Device status (reserved)
8         1       ","
9–16      8       Weight: right-aligned float, e.g. "  110.900"
17–18     2       Unit: "kg" or "lb"
19        1       Trailing character
```

Example packet: `ST,GS,1 ,  110.900kg`

Status determines which weight value is meaningful:
- `"ST"` (stable) — parse and return the weight value.
- `"US"` (unstable, truck still moving) — return 0.0; weight field is undefined while moving.
- `"OL"` (overload) — return 0.0; weight exceeds scale capacity.

`NumberFormatException` during weight parsing is caught and returns `null` from `parse()`.

---

**TYPE5 parsing (`parseType5`):**

TYPE5 devices push a 21-byte binary frame without being polled:

```
Position  Length  Content
0         1       STX byte (0x02)
1–4       4       (undefined/reserved)
5–10      6       Weight — 6 ASCII chars, right-padded
11–12     2       (undefined)
13        1       "!" landmark character
14–19     6       (undefined)
20        1       CR (0x0D)
```

The `!` character at position 13 is the anchor. The parser finds `!` in the buffer, steps back to find STX, then validates both ends of the frame (`chars[0] == 0x02` and `chars[20] == 0x0D`) before parsing.

**Why `Double.NaN` as the failure sentinel instead of `0.0`?**

`0.0` is a legitimate weight reading — an empty platform with tare applied. Using `NaN` means `if (Double.isNaN(weight)) return null` is unambiguous. `0.0` passes through as a real measurement.

All parsed weights are rounded: `Math.round(v * 1000.0) / 1000.0` → 3 decimal places.

---

### 4.6 CommPort

**File:** `com/scale/serial/CommPort.java`

A thread-safe serial port wrapper. It owns one daemon thread (`rxThread`) and manages one `SerialPort` reference (`activePort`).

**Key fields:**

| Field | Type | Role |
|-------|------|------|
| `activePort` | `volatile SerialPort` | Currently open port; null when closed |
| `running` | `AtomicBoolean` | Controls the receive loop |
| `rxThread` | `Thread` | Daemon thread running `runLoop()` |
| `onData` | `volatile Consumer<String>` | Called for each received packet |
| `onConnect` | `volatile Consumer<Boolean>` | Called on open/close |

**Key constants:**

| Constant | Value | Purpose |
|----------|-------|---------|
| `RECONNECT_DELAY_MS` | 5000 | Seconds before reconnect after disconnect |
| `READ_TIMEOUT_MS` | 1000 | Max block time per `readLine()` call |
| `BURST_SLEEP_MS` | 300 | Poll interval for TYPE5 |
| `BURST_BUF_SIZE` | 512 | Pre-allocated byte buffer for TYPE5 reads |

**`open()`** — synchronized, idempotent. Starts `rxThread` only once. The thread runs `runLoop()`.

**`runLoop()` — the reconnect loop:**

```
while (running.get()) {
    try {
        openPort()         // configure baud, parity, read timeout
        onConnect(true)    // notify listener
        readLines() or readBurst()  // blocks until disconnect
    } catch (Exception) {
        // port closed or cable pulled
    } finally {
        silentClose()      // close port, clear activePort
        onConnect(false)   // notify listener
        sleep(5s)          // wait before retry
    }
}
```

No external scheduler is needed. The reconnect logic is a plain `while` loop with a sleep. When `close()` is called (`running = false` + interrupt), the thread exits the loop cleanly.

**Readline mode (CAS_V1, CAS_V2):**

```java
BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
while (running.get()) {
    String line = br.readLine();   // blocks up to READ_TIMEOUT_MS (1 second)
    if (line != null && !line.isBlank()) {
        onData.accept(line.replace("\r", ""));
    }
}
```

The 1-second timeout means the thread checks `running.get()` at least once per second. `close()` sets `running = false` and the thread exits within 1 second.

**Burst mode (TYPE5):**

```java
while (running.get()) {
    Thread.sleep(BURST_SLEEP_MS);  // 300 ms
    int available = p.bytesAvailable();
    if (available > 0) {
        byte[] buf = new byte[Math.min(available, BURST_BUF_SIZE)];
        p.readBytes(buf, buf.length);
        onData.accept(new String(buf, StandardCharsets.US_ASCII));
    }
}
```

TYPE5 devices push frames continuously. The 300 ms sleep is a rate limiter; it yields the CPU instead of busy-waiting.

**Write methods:**

`write(byte[])`, `writeByte(int)`, `writeAscii(String)` — all read `activePort` (volatile), return `false` without throwing if port is null (not yet open or reconnecting). The poll thread calls these and handles the `false` return by simply waiting until the next poll interval.

**`activePort` is `volatile` — why it matters:**

`activePort` is written by `rxThread` after a successful open. `write()` and `close()` read it from other threads. Without `volatile`, a CPU cache could serve a stale value (null or a closed port reference). `volatile` guarantees every read sees the latest write.

---

### 4.7 PortScanner

**File:** `com/scale/serial/PortScanner.java`

Stateless. All methods are static.

**`scan(IndicatorType type, int baud)`:**

1. Calls `SerialPort.getCommPorts()` — OS-level enumeration of all available ports.
2. Skips any port whose description contains `"bluetooth"` (case-insensitive).
3. Calls `probe()` on each remaining port.
4. Returns the port name of the first match, or `null` if nothing responded.

**`probe(SerialPort, IndicatorType, int baud)`:**

- Opens the port in non-blocking mode (so `readBytes` returns immediately if no data).
- For CAS_V1: sends byte `0x01`.
- For CAS_V2: sends ASCII `"01RW\r"`.
- For TYPE5: sends nothing (device pushes on its own).
- Polls `bytesAvailable()` every 50 ms for up to 1500 ms.
- Reads accumulated bytes.
- Closes the port.
- Returns the port name if `matchesScale()` succeeds, `null` otherwise.

**`matchesScale(String data, IndicatorType type)`:**

- CAS: searches for substrings `"ST,"`, `"US,"`, or `"OL,"` — the status prefix of the CAS packet.
- TYPE5: searches for STX byte (`0x02`) AND `'!'` landmark.

If `probe()` fails to open a port (another process has it, or the driver has a lock), it returns `null` and `scan()` moves on to the next port. The scan never crashes on a port error.

---

### 4.8 ScaleReader

**File:** `com/scale/serial/ScaleReader.java`

Wraps `CommPort` and adds a polling thread. The split between `CommPort` and `ScaleReader` is intentional: `CommPort` knows only how to move bytes; `ScaleReader` knows the poll-response semantics.

**Key fields:**

| Field | Type | Role |
|-------|------|------|
| `commPort` | `CommPort` | Serial port wrapper |
| `running` | `AtomicBoolean` | Controls the poll loop |
| `pollThread` | `Thread` | Daemon running `scalingLoop()` |
| `gotData` | `SynchronousQueue<Boolean>` | Signals when `onData` is called |
| `onWeight` | `volatile Consumer<WeightReading>` | Fired after successful parse |
| `onStatus` | `volatile Consumer<Boolean>` | Fired on connect/disconnect |

**`start()`** — opens `CommPort`, wires `onData` and `onConnect` callbacks, starts `pollThread`.

**`stop()`** — sets `running = false`, closes `CommPort`, interrupts `pollThread`.

---

**Poll loop (`scalingLoop`) — the heart of the request/response logic:**

For CAS_V1 and CAS_V2:

```
LOOP:
  1. gotData.poll()                      // drain any stale token
  2. if (commPort.bufferEmpty())
         commPort.writeByte(0x01)        // send poll command
  3. gotData.poll(500 ms)                // BLOCK — wait for onData signal or timeout
  4. goto LOOP
```

For TYPE5:

```
LOOP:
  gotData.poll(500 ms)                   // just sleep (TYPE5 doesn't need polling)
  goto LOOP
```

**`onData` callback (called from `CommPort.rxThread`):**

```java
commPort.onData = raw -> {
    gotData.offer(TOKEN);               // non-blocking signal to poll loop
    WeightReading r = ScaleProtocol.parse(raw, type, deviceName, scaleToKq);
    if (r != null) emit(r);
};
```

**Why `SynchronousQueue` instead of a flag?**

A `volatile boolean` would require a spin loop (`while (!flag) { sleep(1ms) }`) or `synchronized/wait/notifyAll`. `SynchronousQueue.poll(timeout)` gives blocking-with-timeout semantics with no shared mutable state. `offer()` is non-blocking and drops the token silently if the poll loop has not yet called `poll()` — there is no buildup of stale signals.

The zero-capacity nature of `SynchronousQueue` is exactly the property needed here: there is never a queue of "pending responses". If the poll thread is busy, the token is dropped and the next cycle starts fresh.

---

### 4.9 SseServer

**File:** `com/scale/server/SseServer.java`

A minimal HTTP server built directly on `java.net.ServerSocket`.

**Why not `com.sun.net.httpserver.HttpServer`?**

In JDK 21, `HttpServer` calls `exchange.sendResponseHeaders(200, 0)` and then internally closes the response body, making long-lived SSE streams impossible. Raw `ServerSocket` gives complete control over when data is written and when the connection is closed.

**Key fields:**

| Field | Type | Role |
|-------|------|------|
| `serverSocket` | `ServerSocket` | Bound to `127.0.0.1:8435` |
| `running` | `volatile boolean` | Controls accept loop |
| `lock` | `Object` | Guard for payload state |
| `latestPayload` | `String` | Most recent JSON (guarded by `lock`) |
| `payloadSeq` | `long` | Monotonic counter (guarded by `lock`) |
| `activeClient` | `volatile OutputStream` | Current SSE client's output stream |
| `onClientChange` | `volatile Consumer<Boolean>` | Notifies when a client connects/disconnects |

**Binding:**

```java
serverSocket.setReuseAddress(true);
serverSocket.bind(new InetSocketAddress("127.0.0.1", httpPort));
```

`127.0.0.1` (loopback only) means only the local machine can connect. This is intentional — the weighbridge has one local browser, not remote clients.

`setReuseAddress(true)` allows the server to restart quickly without waiting for the OS `TIME_WAIT` state to expire on the previous socket.

---

**Accept loop:**

```java
while (running) {
    Socket socket = serverSocket.accept();   // blocks
    Thread handler = new Thread(() -> handleClient(socket), "sse-handler");
    handler.setDaemon(true);
    handler.start();
}
```

Each connection gets its own handler thread. Since this is a single-operator system, there is rarely more than one simultaneous connection.

---

**Request parsing (in `handleClient`):**

The handler reads the HTTP request line and headers (headers are discarded — only the method and path matter). Routing:

- `OPTIONS *` → CORS preflight → `204 No Content` + CORS headers.
- `GET /stream` → `handleStream()`.
- Anything else → `404 Not Found`.

`TCP_NODELAY` is set on the accepted socket immediately. Nagle's algorithm batches small writes to reduce packet count, adding up to 200 ms of latency. Disabling it ensures each SSE frame is sent as soon as it is flushed.

**`X-Accel-Buffering: no`** in the response headers tells nginx (or any reverse proxy) not to buffer the stream. Without this, a buffering proxy would hold SSE frames until it had enough data to fill a buffer, breaking real-time delivery.

---

**Single-client eviction (in `handleStream`):**

```java
PrintWriter prev;
synchronized (lock) {
    prev = activeClient;
    activeClient = out;         // atomically replace
}
if (prev != null) prev.close(); // evict old client outside the lock
```

The read-replace-evict is inside `synchronized(lock)` to prevent a TOCTOU race: without the lock, two tabs connecting simultaneously could both read `activeClient` as `null`, both set themselves as active, and neither would be closed. Inside the lock the swap is atomic.

The actual `close()` call is outside the lock to avoid holding the lock while doing I/O (which could block and create a deadlock risk).

---

**Publish/wait mechanism:**

```java
// Publisher (called from ScaleReader's onData callback thread)
public void publish(String json) {
    synchronized (lock) {
        latestPayload = json;
        payloadSeq++;
        lock.notifyAll();       // wake sleeping SSE handler thread(s)
    }
}

// Consumer (runs on sse-handler thread)
synchronized (lock) {
    if (payloadSeq == lastSeq) {
        lock.wait(HEARTBEAT_MS);    // sleep up to 15 seconds
    }
    data   = (payloadSeq != lastSeq) ? latestPayload : null;
    newSeq = payloadSeq;
}
```

The handler thread spends almost all its life blocked inside `lock.wait()`. When `publish()` fires, `payloadSeq` increments and `notifyAll()` wakes the handler immediately. The handler compares its `lastSeq` to `payloadSeq` — if they differ, new data has arrived and is sent to the client.

If no data arrives for `HEARTBEAT_MS` (15 seconds), `wait()` times out and the handler sends an SSE comment:

```
: heartbeat
```

SSE comments are not dispatched to `onmessage`. They exist only to prevent the browser from treating a silent connection as dead and closing it.

`payloadSeq` prevents the handler from sending the same payload twice, even if `notifyAll()` fires spuriously (the Java specification allows spurious wake-ups).

---

**SSE wire format:**

```
data: {"type":"weight","weight":5430.0,...}\n\n
```

The double `\n\n` is mandatory per the SSE spec — it signals that the event is complete and should be dispatched to `onmessage`. A single `\n` is a field separator, not an event boundary.

An initial comment is sent immediately when the connection is established:

```
: connected
```

Some browsers buffer SSE responses until the first byte arrives. This comment flushes the buffer immediately so the browser enters listening state.

---

### 4.10 StatusWindow

**File:** `com/scale/ui/StatusWindow.java`

A JavaFX window for operator configuration. Shown once at startup; stays visible as a status monitor while the server runs.

**Startup blocking:**

```java
// Main thread calls:
Selection sel = window.awaitSelection();  // blocks on CompletableFuture.get()

// FX thread (Start button handler):
selectionFuture.complete(new Selection(port, type, debug));  // unblocks main
```

`CompletableFuture<Selection>` cleanly bridges FX-thread events to the main thread blocking call without `synchronized` or `CountDownLatch`.

**UI update threading:**

JavaFX requires all scene-graph mutations on the FX Application Thread. Background threads call:

```java
public void updateScale(boolean connected, String port) {
    Platform.runLater(() -> {
        scaleIndicator.setStyle("-fx-text-fill: " + (connected ? COL_OK : COL_ERR));
        scaleValue.setText(connected ? port : "Əlaqə kəsildi");
    });
}
```

`Platform.runLater()` queues the lambda on the FX event loop. It returns immediately — the background thread is never blocked waiting for UI to update.

**Color scheme:**

| Constant | Value | Meaning |
|----------|-------|---------|
| `COL_OK` | `#27AE60` | Green — connected |
| `COL_ERR` | `#C0392B` | Red — disconnected / error |
| `COL_WARN` | `#E67E22` | Orange — browser not connected |
| `COL_IDLE` | `#95A5A6` | Gray — initial state |

**Language:** The UI uses Azerbaijani labels (`Başla` = Start, `Tərəzi` = Scale, `Brauzer` = Browser, `Əlaqə kəsildi` = Connection lost) because the target deployment is in Azerbaijan.

---

## 5. Threading Model

| Thread Name | Type | Created By | Role |
|-------------|------|------------|------|
| `main` | non-daemon | JVM | Initialization; parks on `join()` after wiring |
| `JavaFX Application Thread` | daemon | `Platform.startup()` | FX event loop for `StatusWindow` |
| `scale-init` | daemon | `Main` | Port resolution and `ScaleReader` lifecycle |
| `com:COMx` | daemon | `CommPort.open()` | Serial receive loop (`readLines` or `readBurst`) |
| `poll:deviceName` | daemon | `ScaleReader.start()` | Poll loop — sends commands, waits for data |
| `sse-accept` | daemon | `SseServer.start()` | `ServerSocket.accept()` loop |
| `sse-handler` | daemon | `sse-accept` | One per connected SSE client |
| `shutdown-hook` | non-daemon | `Runtime.addShutdownHook()` | Calls `reader.stop()` + `sseServer.stop()` |

All worker threads are daemon threads. The JVM exits when no non-daemon threads remain. The only non-daemon threads are `main` (parked) and the `shutdown-hook` (short-lived). Ctrl+C interrupts `main`, the shutdown hook fires, and daemon threads are automatically killed after the hook completes.

---

## 6. Synchronization Strategy

The codebase uses four distinct synchronization primitives, each chosen for a specific reason.

### `volatile` — single-writer visibility

Used for fields written once by one thread and read by others, where atomic read-modify-write is not needed:

- `CommPort.activePort` — written by `rxThread` after open, read by `write()` and `close()`.
- `ScaleReader.onWeight`, `onStatus` — assigned by `main` before any thread reads them.
- `CommPort.onData`, `onConnect` — same as above.
- `SseServer.activeClient` — written by `sse-handler`, read by `publish()` for null check.
- `SseServer.running` — written by `stop()`, read by accept loop.

`volatile` is cheaper than `synchronized` — it adds a memory barrier without mutex overhead.

### `AtomicBoolean` — atomic state flag

Used for `running` in `CommPort` and `ScaleReader`. `AtomicBoolean.get()` / `set()` are atomic without a lock, which is all we need for a boolean stop flag.

### `SynchronousQueue<Boolean>` — zero-capacity signal

Used in `ScaleReader.gotData` to coordinate the poll loop and the receive callback.

- `offer(TOKEN)` is non-blocking — token dropped if poll loop is not waiting.
- `poll(timeout)` blocks the poll loop thread until either a token arrives or the timeout expires.
- The zero-capacity constraint ensures no token buildup: exactly one signal per data arrival, not a growing queue.

### `synchronized (lock)` + `Object.wait/notifyAll` — payload delivery

Used in `SseServer` to coordinate `publish()` (from `ScaleReader`'s callback thread) and the SSE handler thread's sleep/wake cycle. `lock.wait(15000)` parks the handler; `lock.notifyAll()` wakes it when new data arrives.

`payloadSeq` (a monotonic `long` guarded by `lock`) prevents double-delivery after spurious wakeups.

### `CompletableFuture<Selection>` — cross-thread blocking startup

Used once in `StatusWindow.awaitSelection()` to block `main` until the operator clicks Start. `CompletableFuture` was chosen over `CountDownLatch` because it carries a value (the `Selection` record) — no shared mutable variable is needed.

---

## 7. Data Flow: Serial Byte to SSE Event

```
Scale hardware
  │ RS-232 / USB-serial
  ▼
jSerialComm native driver (C++ / OS UART driver)
  │  bytes land in kernel receive buffer
  │  hardware interrupt wakes the kernel
  ▼
CommPort.rxThread
  │  readLine() or readBurst() drains the buffer
  │  fires: onData(rawString)
  ▼
ScaleReader.onData callback (on rxThread)
  │  gotData.offer(TOKEN)             → wakes poll loop
  │  ScaleProtocol.parse(rawString)   → WeightReading
  │  emit(reading)                    → fires onWeight callback
  ▼
Main.onWeight callback (on rxThread)
  │  GSON.toJson(reading)             → JSON string
  │  SseServer.publish(json)
  ▼
SseServer.publish (on rxThread)
  │  synchronized: latestPayload = json; payloadSeq++; notifyAll()
  ▼
SseServer.sse-handler thread (woken by notifyAll)
  │  synchronized: read latestPayload; update lastSeq
  │  out.print("data: " + payload + "\n\n")
  │  out.flush()
  ▼
TCP socket → browser
  │  EventSource dispatches to onmessage
  ▼
JavaScript handler: JSON.parse(e.data) → { weight, unit, stable, ... }
```

The critical path from serial byte to browser is:

1. **rxThread** — reads, parses, fires callback, calls `publish()`.
2. **sse-handler** — woken by `notifyAll()`, writes and flushes.

There is no intermediate queue or buffer accumulation. The total latency is dominated by the poll interval (500 ms for CAS protocols) and TCP flushing (near-instant with `TCP_NODELAY`).

---

## 8. Serial Protocol Formats

### CAS NT-500 (CAS_V1 and CAS_V2)

Both variants use the same response format. The only difference is the poll command.

**Poll commands:**

| Variant | Command | Encoding |
|---------|---------|----------|
| CAS_V1 | `0x01` | Single raw byte |
| CAS_V2 | `"01RW\r"` | ASCII string with CR |

**Response packet (20-char ASCII, terminated with `\r\n`):**

```
 0  1  2  3  4  5  6  7  8  9 10 11 12 13 14 15 16 17 18 19
[S][T][,][G][S][,][1][ ][,][ ][ ][1][1][0][.][9][0][0][k][g]
```

| Position | Length | Field | Values |
|----------|--------|-------|--------|
| 0–1 | 2 | Status | `"ST"` stable / `"US"` unstable / `"OL"` overload |
| 2 | 1 | Separator | `","` |
| 3–4 | 2 | Weight type | `"GS"` gross / `"NT"` net |
| 5 | 1 | Separator | `","` |
| 6 | 1 | Device ID | ASCII character |
| 7 | 1 | Device status | Reserved |
| 8 | 1 | Separator | `","` |
| 9–16 | 8 | Weight | Right-aligned float, e.g. `"  110.900"` |
| 17–18 | 2 | Unit | `"kg"` or `"lb"` |
| 19 | 1 | Trailing | Ignored |

**Parsing rules:**
- Only `"ST"` status yields a real weight. `"US"` and `"OL"` return weight=0.0 because their weight field is undefined.
- `stable = status.equals("ST")`
- `overload = status.equals("OL")`
- `weightType = weightTypeStr.equals("NT") ? "net" : "gross"`

---

### Tunaylar (TYPE5, STX-framed burst)

TYPE5 devices push frames continuously without being polled.

**Frame format (21 bytes):**

```
Byte  0    1    2    3    4    5    6    7    8    9   10   11   12   13   14 ... 19   20
     [STX][   ][   ][   ][   ][W0][W1][W2][W3][W4][W5][   ][   ][ ! ][   ]...[   ][CR]
      0x02                     ←— weight: 6 chars —→              0x21                0x0D
```

| Position | Length | Content |
|----------|--------|---------|
| 0 | 1 | STX (`0x02`) — frame start |
| 1–4 | 4 | Undefined / reserved |
| 5–10 | 6 | Weight as 6-char ASCII, right-padded |
| 11–12 | 2 | Undefined |
| 13 | 1 | `'!'` — landmark anchor |
| 14–19 | 6 | Undefined |
| 20 | 1 | CR (`0x0D`) — frame end |

**Frame location strategy:**

The parser finds the `'!'` character (position 13), steps back 13 bytes to reach position 0 (STX), then validates `chars[0] == 0x02` and `chars[0+20] == 0x0D`. If either check fails the frame is discarded.

TYPE5 readings are always `stable=true, overload=false, weightType="gross"` — the device only transmits when the reading is stable.

---

## 9. Auto-Reconnect and Port Scanning

### Port Scanning

When `port=AUTO`, `Main.resolvePort()` calls `PortScanner.scan()` in a retry loop:

```java
while (true) {
    String port = PortScanner.scan(config.indicatorType, config.baud);
    if (port != null) return port;
    Thread.sleep(5_000);   // wait before re-scanning
}
```

`PortScanner.scan()` is synchronous and blocking — it opens and closes each port in turn. The SSE server is already bound and accepting connections during this time; the browser can connect before the scale is found and will receive a status event when the scale does connect.

### Serial Reconnect

`CommPort.runLoop()` is an infinite retry loop:

```
open → read (blocks) → exception → close → sleep(5s) → open → ...
```

On disconnect (cable pulled, adapter reset), `SerialPort.getInputStream().readLine()` throws an exception. The `finally` block closes the port cleanly, fires `onConnect(false)`, and sleeps 5 seconds before retrying. The scale-init thread does not need to be involved — `CommPort` handles the entire reconnect lifecycle independently.

`ScaleReader` calls `onStatus(false)` when `CommPort` fires `onConnect(false)`, which propagates to `Main`, which publishes a `{"type":"status","connected":false}` SSE event.

---

## 10. SSE Server Internals

### Why One Client at a Time?

A weighbridge has one operator screen. Supporting multiple simultaneous streams would require broadcasting to a list of clients, which adds complexity and the risk of a slow client blocking faster ones. The single-client model keeps the SSE server code simple and deterministic.

### CORS Headers

```
Access-Control-Allow-Origin: *
Access-Control-Allow-Headers: *
Access-Control-Allow-Methods: GET, OPTIONS
```

These headers allow browsers to connect to the SSE server from any origin. A React dev server on port 3000 connecting to `localhost:8435` would otherwise be blocked by the same-origin policy. Because the server binds to `127.0.0.1` (loopback only), permitting `*` origin does not open the server to external machines.

### OPTIONS Preflight

Browsers send an HTTP `OPTIONS` request before a cross-origin `GET`. The server responds with `204 No Content` + CORS headers. Only then does the browser send the actual `GET /stream`.

### Heartbeat

Every 15 seconds of silence, the handler sends:

```
: heartbeat\n\n
```

SSE comments (lines starting with `:`) are not dispatched to `onmessage`. They prevent the browser from treating a long silence as a dead connection and closing it. 15 seconds is chosen as a comfortable margin below typical browser and proxy connection timeout thresholds.

---

## 11. Error Handling Philosophy

**Parse errors are silent drops.** `ScaleProtocol.parse()` returns `null` on malformed input. `ScaleReader.onData` checks for `null` and skips the `emit()` call. No exception is thrown and no log noise is generated for transient garbled bytes (which are normal on serial lines). Debug mode logs every raw packet so frame errors are diagnosable without cluttering production logs.

**Config errors are caught at startup.** `IndicatorType.fromCode()` throws `IllegalArgumentException` on unknown codes. `AppConfig` catches `NumberFormatException` on every numeric field and logs a warning with the default value used. The app never starts in a silently broken configuration state.

**Callback exceptions are isolated.** Each callback invocation is wrapped in `try/catch(Exception)` in `CommPort` and `ScaleReader`. A bug in a callback (e.g., in `publish()`) cannot crash the serial receive thread. The error is logged and the receive loop continues.

**Write failures are silent.** `CommPort.write*()` methods return `false` if `activePort` is null. The poll loop discards the `false` return — sending a poll command while the port is reconnecting is harmless; the next cycle will try again.

---

## 12. Build and Packaging

**Build command:**

```bash
mvn package
```

**Maven plugins:**

| Plugin | Role |
|--------|------|
| `maven-compiler-plugin` | Compiles with `--release 21` |
| `maven-assembly-plugin` | Creates fat JAR with all dependencies merged |
| `jpackage-maven-plugin` | Windows-only profile; wraps fat JAR as `.exe` via launch4j |

**Fat JAR:**

`maven-assembly-plugin` uses the `jar-with-dependencies` descriptor. All dependency JARs (jSerialComm, Gson, JavaFX for all platform classifiers) are unpacked and merged into a single JAR. The `Main-Class` manifest attribute is set to `com.scale.Main`.

**Native serial drivers:**

`jSerialComm` ships pre-compiled native binaries (`.dll` for Windows, `.so` for Linux, `.dylib` for macOS) inside its JAR. At runtime, it extracts the matching binary to `java.io.tmpdir` and loads it via `System.load()`. No manual JNI installation is required.

**JavaFX bundling:**

JavaFX modules are included with all platform classifiers (`win`, `linux`, `mac`) so the fat JAR runs on any OS. A warning `"Unsupported JavaFX configuration"` is printed at startup — this is cosmetic and does not affect functionality. It is suppressed to `SEVERE` log level in the logging setup.

**Windows `.exe`:**

`jpackage-maven-plugin` (launch4j mode) wraps the fat JAR in a Windows PE executable. The manifest specifies `minVersion=21` — if the installed JRE is older, launch4j shows a clear "Java 21 required" dialog instead of a cryptic error.

**Outputs after `mvn package`:**

| File | Description |
|------|-------------|
| `target/scale-server-1.0.0-jar-with-dependencies.jar` | Fat JAR — runs on any OS with JRE 21+ |
| `target/scale-server.exe` | Windows executable — double-click launch |
