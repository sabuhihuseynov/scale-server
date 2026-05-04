# Scale Server

A Java 21 application that reads weight data from a physical weighbridge (truck scale) over a serial RS-232/USB cable and streams it in real-time to any browser or frontend via **Server-Sent Events (SSE)**.

```
Physical Scale
    │  RS-232 / USB-Serial
    ▼
┌──────────────────────────────────────────────┐
│                 scale-server                 │
│                                              │
│  CommPort ──► ScaleReader ──► SseServer      │
│  (serial)     (poll loop)     (HTTP :8435)   │
│                   │                          │
│              StatusWindow                    │
│            (JavaFX operator UI)              │
└──────────────────────────────────────────────┘
    │  SSE  http://localhost:8435/stream
    ▼
Browser / Frontend
```

---

## Table of Contents

1. [What problem does this solve?](#1-what-problem-does-this-solve)
2. [Quick start](#2-quick-start)
3. [Configuration — `scale.properties`](#3-configuration--scaleproperties)
4. [Architecture — how the components connect](#4-architecture--how-the-components-connect)
5. [Component deep-dive](#5-component-deep-dive)
   - [Main — entry point and orchestrator](#51-main--entry-point-and-orchestrator)
   - [AppConfig — configuration loader](#52-appconfig--configuration-loader)
   - [CommPort — serial port wrapper](#53-commport--serial-port-wrapper)
   - [ScaleReader — poll orchestrator](#54-scalereader--poll-orchestrator)
   - [ScaleProtocol — packet parser](#55-scaleprotocol--packet-parser)
   - [PortScanner — auto port discovery](#56-portscanner--auto-port-discovery)
   - [SseServer — raw-socket HTTP/SSE server](#57-sseserver--raw-socket-httpsse-server)
   - [WeightReading — immutable data model](#58-weightreading--immutable-data-model)
   - [IndicatorType — protocol enum](#59-indicatortype--protocol-enum)
   - [StatusWindow — JavaFX operator GUI](#510-statuswindow--javafx-operator-gui)
6. [jSerialComm — native serial I/O](#6-jserialcomm--native-serial-io)
7. [SSE event format](#7-sse-event-format)
8. [Supported scale protocols](#8-supported-scale-protocols)
9. [How Java solves the core problems efficiently](#9-how-java-solves-the-core-problems-efficiently)
10. [Thread model](#10-thread-model)
11. [Build and package](#11-build-and-package)
12. [Logging and debugging](#12-logging-and-debugging)

---

## 1. What problem does this solve?

Industrial weighbridge scales communicate over old serial protocols (RS-232, 9600 baud). Modern web frontends have no way to read a serial port directly. This server bridges the gap:

- It speaks the scale's binary/ASCII serial protocol on one side.
- It speaks standard HTTP on the other side, pushing every new weight reading as an SSE event.
- The frontend needs only `new EventSource("http://localhost:8435/stream")` — no polling, no WebSockets, no plugins.

The server also handles:
- **Auto-discovery** — if you don't know which COM port the scale is on, it scans all ports automatically.
- **Auto-reconnect** — if the USB cable is pulled and reinserted, the server reconnects within 5 seconds without a restart.
- **Multiple protocol variants** — CAS NT-500 v1, CAS NT-500 v2, and generic Type 5 burst-read devices are all supported.

---

## 2. Quick start

### Requirements

- Java 21+
- Scale connected via RS-232 or USB-to-serial adapter
- `scale.properties` file (optional; built-in defaults work for most setups)

### Run

```bash
java -jar scale-server-1.0.0-jar-with-dependencies.jar
```

Or on Windows, double-click `scale-server.exe`.

A setup window opens. Select your COM port and protocol type, then click **Başla** (Start). The SSE stream is available at:

```
http://localhost:8435/stream
```

### Connect from the browser

```javascript
const es = new EventSource("http://localhost:8435/stream");
es.onmessage = e => {
    const data = JSON.parse(e.data);
    console.log(data.weight, data.unit, data.stable);
};
```

---

## 3. Configuration — `scale.properties`

Place this file next to the JAR. Restart the server for changes to take effect.

```properties
# Serial port — AUTO scans all ports at startup
# Windows: COM3    Linux: /dev/ttyUSB0
port=AUTO

# Baud rate — must match the DIP-switch on the physical scale
baud=9600

# Protocol: 1=CAS_V1  2=CAS_V2  5=TYPE5
indicator.type=1

# Weight multiplier: 1.0=already kg  0.001=device reports grams
scale.to.kq=1.0

# Port for the embedded HTTP/SSE server
http.port=8435

# Human-readable label included in every SSE event's "device" field
device.name=

# Debug: writes raw byte traces to scale_debug.log (overwritten each run)
debug=false
```

All keys are optional — built-in defaults are used when the file is absent.

---

## 4. Architecture — how the components connect

```
startup
  │
  ├─► AppConfig.load()               reads scale.properties
  │
  ├─► StatusWindow(config)           JavaFX window — blocks until operator clicks Start
  │       └─ awaitSelection()        returns (port, type, debug) selection
  │
  ├─► SseServer.start()              binds 127.0.0.1:8435 immediately
  │                                  (frontend can connect before scale is found)
  │
  └─► [daemon thread] scale-init
          │
          ├─► PortScanner.scan()     if port=AUTO, probes each COM port
          │
          └─► ScaleReader.start()
                  │
                  ├─► CommPort.open()    starts rxThread (serial receive loop)
                  │       onData ──────────────────────────────────┐
                  │                                                │
                  └─► [daemon thread] poll:deviceName             │
                          │   sends poll command every 500 ms     │
                          │   blocks on SynchronousQueue          │
                          │◄──────────────────────────────────────┘
                          │   ScaleProtocol.parse(raw)
                          │
                          └─► onWeight callback
                                  │
                                  ├─► SseServer.publish(json)   wakes SSE handler
                                  └─► StatusWindow.updateScale()
```

All real work runs on daemon threads. The main thread parks on `Thread.currentThread().join()` after wiring everything together.

---

## 5. Component deep-dive

### 5.1 `Main` — entry point and orchestrator

`Main.java` is the wiring layer. It performs no I/O itself; it creates all components and connects them with callbacks.

**Startup sequence:**

1. `AppConfig.load()` reads `scale.properties`.
2. `StatusWindow` is created on the JavaFX Application Thread (via `Platform.startup()`). The main thread blocks on `awaitSelection()` until the operator clicks Start. If there is no display (headless server), the window is skipped.
3. `SseServer.start()` binds the HTTP port immediately — the browser can open the stream before the scale is even found.
4. A daemon thread (`scale-init`) is launched to resolve the port and start the reader. This keeps the SSE server responsive during potentially slow port scanning.
5. Inside `scale-init`, `ScaleReader` is created with two lambda callbacks:
   - `onWeight` — converts the `WeightReading` to JSON and calls `sseServer.publish(json)`.
   - `onStatus` — publishes a `{"type":"status", "connected":true/false}` SSE event and updates the status window.
6. A JVM shutdown hook (`Runtime.getRuntime().addShutdownHook(...)`) cleanly stops the reader and SSE server on Ctrl+C or SIGTERM.

**Why daemon threads?** All background threads are daemon threads. A daemon thread does not prevent JVM exit — when only daemon threads remain, the JVM shuts down. The main thread is the only non-daemon thread, and it parks on `join()`. This means Ctrl+C kills only the main thread, the shutdown hook fires, and everything stops cleanly.

---

### 5.2 `AppConfig` — configuration loader

`AppConfig` is an **immutable value object** — every field is `final`. Loading and UI overrides produce new instances rather than mutating state.

```java
public AppConfig withUiSettings(String port, IndicatorType type, boolean debugMode) {
    return new AppConfig(port, baud, type, scaleToKq, httpPort, deviceName, debugMode);
}
```

This means the config passed to `ScaleReader` is always a consistent snapshot — no risk of another thread changing a field mid-read.

Configuration is loaded via `java.util.Properties` from `scale.properties` next to the JAR. Each key has a typed parser (`parseIntOrDefault`, `parseDblOrDefault`) that logs a warning and uses the default on any parse failure, rather than crashing.

---

### 5.3 `CommPort` — serial port wrapper

`CommPort` wraps the `jSerialComm` library and provides:

- **Auto-reconnect** — if the port closes (cable pulled, device reset), `runLoop()` retries every 5 seconds indefinitely.
- **Two read strategies** selected by `IndicatorType`:

**Readline mode** (CAS_V1, CAS_V2):
```
BufferedReader.readLine()
  → blocks until \n or READ_TIMEOUT_MS (1 second)
  → fires onData() for each non-empty line
```
The 1-second read timeout means the thread is never stuck — it checks `running.get()` at least every second so `close()` takes effect quickly.

**Burst mode** (TYPE5):
```
Thread.sleep(300 ms)
  → p.bytesAvailable() > 0?
  → drain entire buffer into String
  → fire onData() once
```
Type 5 devices push data continuously without being asked. The burst reader gathers whatever accumulated in 300 ms and hands it to the parser in one call.

**Thread-safety:** `activePort` is declared `volatile`. The receive thread writes it once after a successful open; `write()` and `close()` read it from other threads. Because `volatile` ensures visibility of the latest write, a thread calling `write()` always sees either `null` (port not yet open) or the live `SerialPort` reference — never a stale pointer.

**Port parameters:** 9600 baud, 8 data bits, 1 stop bit, no parity (8N1). This is the standard RS-232 configuration for CAS weighbridges.

---

### 5.4 `ScaleReader` — poll orchestrator

`ScaleReader` wraps `CommPort` and adds the polling logic needed for request-response protocols.

**The core mechanism — `SynchronousQueue<Boolean> gotData`:**

A `SynchronousQueue` is a zero-capacity queue — it has no internal buffer. `offer()` succeeds only if a consumer is already waiting in `poll()`; otherwise it drops the item silently. This is exactly what we need:

```
Poll loop thread:
  1. gotData.poll()                  // drain any stale token from last round
  2. commPort.writeByte(0x01)        // send poll command to scale
  3. gotData.poll(500ms)             // WAIT — either data arrives, or timeout
  4. go to 1

CommPort rxThread (calls onData callback):
  1. gotData.offer(TOKEN)            // signal "data received" (non-blocking)
  2. ScaleProtocol.parse(raw)        // parse the packet
  3. emit(reading)                   // fire onWeight callback
```

**Why `SynchronousQueue` instead of a flag or `boolean[]`?**

A `volatile boolean` would require a `while (flag == false) { sleep(1) }` spin loop or explicit `synchronized/wait/notifyAll`. `SynchronousQueue.poll(timeout)` gives us exactly the blocking-with-timeout semantics for free, with no shared mutable state beyond the queue itself.

**TYPE5 polling:** For burst-mode devices, no poll command is sent. The poll thread still runs but it simply does `gotData.poll(500ms)` and loops — the 500ms sleep gives the CPU back rather than busy-waiting. The real work is done entirely in the `CommPort` rxThread.

---

### 5.5 `ScaleProtocol` — packet parser

`ScaleProtocol` parses raw byte strings into `WeightReading` objects. It dispatches by `IndicatorType` using a Java 21 **switch expression**:

```java
return switch (type) {
    case TYPE5       -> parseType5(packet, deviceName, scaleToKq);
    case CAS_V1, CAS_V2 -> parseCas(packet, deviceName, scaleToKq);
};
```

#### CAS NT-500 format (Type 1 and Type 2)

The scale sends a 20-character ASCII line terminated with `\r\n`. After stripping the line terminator:

```
Offset  Length  Content
──────  ──────  ─────────────────────────────────────────────
  0       2     Status: "ST"=stable  "US"=unstable  "OL"=overload
  2       1     ","
  3       2     Weight type: "GS"=gross  "NT"=net
  5       1     ","
  6       1     Device-ID character
  7       1     Lamp-flags byte (bit-field, decoded to named booleans)
  8       1     ","
  9       8     Weight — right-aligned float, e.g. "  110.900"
  17      2     Unit — e.g. "kg" or "lb"
  19      1     trailing character
```

Example raw packet: `ST,GS,1 ,  110.900kg`

**Lamp flags byte** (offset 7) is decoded bit-by-bit into a `Map<String, Boolean>`:

| Bit | Mask   | Key      | Meaning                        |
|-----|--------|----------|--------------------------------|
| 0   | `0x01` | `zero`   | Zero indicator lit             |
| 1   | `0x02` | `tare`   | Tare indicator lit             |
| 2   | `0x04` | `net`    | Net weight mode active         |
| 6   | `0x40` | `hold`   | Hold (frozen reading) active   |
| 7   | `0x80` | `stable` | Stable indicator lit           |

**Weight extraction** — only `"ST"` (stable) readings carry a real weight value. `"US"` (unstable, truck still moving) and `"OL"` (overload, weight exceeds scale capacity) return `0.0` because their weight fields are undefined.

#### Type 5 STX-framed format

Type 5 devices push 21-byte frames continuously without being polled:

```
Offset  Length  Content
──────  ──────  ─────────────────────────────────────────────
  0       1     STX (0x02)
  5       6     Weight string — 6 ASCII chars
  13      1     "!" landmark character
  20      1     CR (0x0D)
```

**Frame location strategy:** The `!` character is a reliable landmark. The parser finds it, steps back one byte, and arrives at STX. It then validates that `chars[0] == 0x02` and `chars[20] == 0x0D` before parsing the weight at `[5:11]`.

**Why `Double.NaN` for parse failure?** `0.0` is a valid weight (empty platform with tare applied). Using `NaN` as a sentinel means `if (Double.isNaN(weight)) return null` is unambiguous — `0.0` passes through correctly as a real reading.

All parsed weights are rounded to 3 decimal places: `Math.round(v * 1000.0) / 1000.0`.

---

### 5.6 `PortScanner` — auto port discovery

When `port=AUTO`, `PortScanner.scan()` is called before `ScaleReader` is constructed:

1. `SerialPort.getCommPorts()` lists every serial port the OS knows about.
2. Bluetooth ports are skipped by checking the port description for "bluetooth".
3. Each remaining port is **probed**:
   - Open at the configured baud rate (8N1).
   - Send the poll command appropriate for the configured indicator type.
   - Wait up to 1500 ms, polling every 50 ms for received bytes.
   - Call `matchesScale(data, type)` — check for protocol signatures:
     - CAS: looks for `"ST,"`, `"US,"`, or `"OL,"` in the response.
     - TYPE5: looks for STX byte `0x02` followed by `!` in the burst.
   - Close the port (whether it matched or not).
4. Return the first port that matched, or `null` if none responded.

If `null` is returned, `Main.resolvePort()` sleeps 5 seconds and tries again in a loop — the server keeps running and the SSE server stays up while waiting for the device to be plugged in.

---

### 5.7 `SseServer` — raw-socket HTTP/SSE server

`SseServer` is a minimal HTTP server built directly on `java.net.ServerSocket`. It does **not** use `com.sun.net.httpserver` because in JDK 21 that API closes the response body immediately after `sendResponseHeaders()`, making long-lived SSE streams impossible.

#### Accepting connections

```java
ServerSocket serverSocket = new ServerSocket();
serverSocket.setReuseAddress(true);
serverSocket.bind(new InetSocketAddress("127.0.0.1", httpPort));
```

Binding to `127.0.0.1` (loopback only) means the server is not reachable from other machines — only the local browser can connect. This is intentional for security in a single-machine weighbridge deployment.

A single daemon thread (`sse-accept`) runs `serverSocket.accept()` in a loop. Each new TCP connection spawns its own handler thread (`sse-handler`).

#### HTTP request handling

The handler reads the request line and headers (headers are discarded), then routes on path and method:

- `OPTIONS` → CORS preflight response (204 No Content) — required by browsers before the actual GET.
- `GET /stream` → SSE stream.
- anything else → 404.

**CORS headers** (`Access-Control-Allow-Origin: *`) allow the browser to connect even when the page is served from a different origin (e.g., a React dev server on port 3000 connecting to `localhost:8435`).

**`X-Accel-Buffering: no`** tells nginx or any reverse proxy in front of the server not to buffer the stream — without this, buffering proxy would hold SSE frames until it had enough data, breaking real-time delivery.

**`TCP_NO_DELAY`** disables Nagle's algorithm on the accepted socket. Nagle batches small writes to reduce packet count, which adds up to 200 ms of latency. For SSE, each event must be flushed immediately.

#### SSE stream loop

The server supports exactly **one active SSE client** at a time. This is intentional — a weighbridge has one operator screen. When a new client connects, the old one is evicted:

```java
synchronized (lock) {
    prev = activeClient;
    activeClient = out;
}
if (prev != null) prev.close(); // evict old client
```

The swap is inside `synchronized(lock)` to prevent a race condition (TOCTOU) where two threads could both read `null`, both think there is no existing client, and both set themselves as the active client simultaneously.

**Publish / wait mechanism:**

```java
// Publisher side (called from ScaleReader's callback thread)
public void publish(String json) {
    synchronized (lock) {
        latestPayload = json;
        payloadSeq++;
        lock.notifyAll();      // wake the sleeping SSE handler thread
    }
}

// Consumer side (runs on the sse-handler thread)
synchronized (lock) {
    if (payloadSeq == lastSeq) {
        lock.wait(HEARTBEAT_MS);   // sleep up to 15 seconds
    }
    data   = (payloadSeq != lastSeq) ? latestPayload : null;
    newSeq = payloadSeq;
}
```

`payloadSeq` is a monotonically increasing counter. The SSE handler remembers `lastSeq`. If `payloadSeq == lastSeq`, no new data has arrived and the handler sleeps (via `lock.wait()`). When `publish()` is called, `payloadSeq` increments and `notifyAll()` wakes the handler. The handler then reads `latestPayload` and writes it as an SSE event.

If no data arrives for 15 seconds, `lock.wait(15000)` times out and the handler sends an SSE comment (`": heartbeat\n\n"`). This prevents the browser from treating a silent connection as dead and closing it.

**SSE event format** on the wire:
```
data: {"type":"weight","weight":5430.0,...}\n\n
```

The `\n\n` double newline is mandatory per the SSE spec — it tells the browser that this event is complete and should be dispatched to the `onmessage` handler.

---

### 5.8 `WeightReading` — immutable data model

`WeightReading` is a plain immutable record-like class with `final` fields:

| Field        | Type                      | Description                                    |
|-------------|---------------------------|------------------------------------------------|
| `device`    | `String`                  | Human-readable device label from config        |
| `weight`    | `double`                  | Weight in kg, rounded to 3 decimal places      |
| `unit`      | `String`                  | `"kg"` or `"lb"` as reported by device         |
| `stable`    | `boolean`                 | `true` when reading is stable (not mid-swing)  |
| `overload`  | `boolean`                 | `true` when weight exceeds scale capacity       |
| `weightType`| `String`                  | `"gross"` or `"net"`                           |
| `raw`       | `String`                  | Original packet bytes (for debugging)           |
| `timestamp` | `String`                  | ISO-8601 instant of when this object was created |
| `lampFlags` | `Map<String, Boolean>`    | Named indicator lamp states (CAS only)          |

The `timestamp` is set in the constructor via `Instant.now().toString()` — it reflects when the server received and parsed the packet, not when the scale measured it.

`lampFlags` is wrapped in `Collections.unmodifiableMap()` so callers cannot modify the map.

---

### 5.9 `IndicatorType` — protocol enum

```java
public enum IndicatorType {
    CAS_V1(1),   // poll with raw byte 0x01
    CAS_V2(2),   // poll with ASCII "01RW\r"
    TYPE5(5);    // no poll — device pushes data continuously
}
```

`fromCode(int)` maps the integer from `scale.properties` to the enum constant, throwing `IllegalArgumentException` on unknown codes so misconfiguration is caught immediately at startup.

`isLineBased()` returns `true` for CAS_V1 and CAS_V2 — these use `BufferedReader.readLine()`. TYPE5 uses the burst strategy.

---

### 5.10 `StatusWindow` — JavaFX operator GUI

`StatusWindow` is a JavaFX window shown at startup so the operator can confirm settings before connecting to the scale.

**Configuration section:**
- Port dropdown (ComboBox) — lists all available COM ports plus "AUTO" option.
- Protocol type dropdown — CAS_V1, CAS_V2, TYPE5.
- Debug mode checkbox.

**Live status section (updated in real-time):**
- **Tərəzi (Scale)** indicator — green when connected, red when disconnected or not yet found.
- **Brauzer (Browser)** indicator — green when an SSE client is connected, orange when no browser is connected.

**Threading:** JavaFX requires all UI updates on the FX Application Thread. `StatusWindow.updateScale()` and `updateClient()` are called from background threads; they use `Platform.runLater(() -> { ... })` to dispatch the update correctly.

**Startup blocking:** `awaitSelection()` calls `selectionFuture.get()`, which blocks the calling thread (main) until the operator clicks **Başla**. `CompletableFuture.complete()` is called in the button's action handler (on the FX thread), unblocking main.

The window uses **Azerbaijani** labels (`Tərəzi`, `Brauzer`, `Başla`, `Əlaqə kəsildi`) since the target deployment is in Azerbaijan.

---

## 6. jSerialComm — native serial I/O

`jSerialComm` is not a pure-Java library — it is a thin Java API over a **native C++ core**. For each supported platform (Windows x86/x64, Linux x86/x64/ARM, macOS) it ships a compiled native shared library (`.dll` / `.so` / `.dylib`) bundled inside the JAR. On first use the matching binary is extracted to the Java temp directory and loaded via `System.load()`. All serial port calls — open, read, write, close — go through JNI directly into that native binary, which calls the OS serial API (`ReadFile`/`WriteFile` on Windows, `read`/`write` on Linux) with no Java layer in between.

**Why this matters for efficiency:**

- `readLine()` via `p.getInputStream()` ultimately calls the native `ReadFile` / `read` with `TIMEOUT_READ_BLOCKING`. The OS parks the Java thread at the kernel level — zero CPU, woken by a hardware UART interrupt when a byte arrives. This is identical to what a native C program would do.
- `p.bytesAvailable()` calls `ClearCommError` (Windows) or `ioctl(FIONREAD)` (Linux) — a single syscall, ~1 µs, no hardware I/O.
- `p.readBytes(buf, n)` in burst mode writes directly into the Java `byte[]` via a JNI pinned array, avoiding an extra copy.
- `p.writeBytes([0x01], 1)` hands the byte to the kernel transmit buffer and returns immediately — the UART driver drains it to hardware asynchronously.

In practice the `rxThread` spends ~99.9 % of its life blocked inside the kernel's `ReadFile` / `read`, showing as `WAITING` in a thread dump. The serial read pipeline adds no measurable CPU overhead compared to a native C++ application doing the same thing.

The only Java overhead is the JNI call boundary itself — roughly 50–100 ns per call — which is irrelevant at a 9600 baud / 500 ms polling cadence.

---

## 7. SSE event format

All events are JSON objects on the `data:` line.

### Weight event

Sent after every successful scale reading (approx. every 500 ms when stable):

```json
{
  "type":       "weight",
  "device":     "CAS NT-500 (v1)",
  "port":       "COM3",
  "weight":     5430.0,
  "unit":       "kg",
  "stable":     true,
  "overload":   false,
  "weightType": "gross",
  "timestamp":  "2026-05-04T10:23:45.123Z",
  "lampFlags": {
    "zero":   false,
    "tare":   false,
    "net":    false,
    "hold":   false,
    "stable": true
  }
}
```

### Status event

Sent when the scale connects or disconnects:

```json
{
  "type":      "status",
  "device":    "CAS NT-500 (v1)",
  "port":      "COM3",
  "connected": true
}
```

### Heartbeat (SSE comment)

Sent every 15 seconds when no weight data arrives, to keep the connection alive:

```
: heartbeat
```

This is an SSE comment — browsers do not dispatch it to `onmessage`. It only exists to prevent the browser from timing out the connection.

### Connected comment

Sent immediately when the browser opens the stream:

```
: connected
```

This flushes the browser's internal SSE buffer, which some browsers hold until the first byte arrives.

---

## 8. Supported scale protocols

| Code | Name     | Poll command         | Packet format                           |
|------|----------|---------------------|-----------------------------------------|
| 1    | CAS_V1   | Byte `0x01`         | 20-char ASCII: `ST,GS,1 ,  110.900kg`  |
| 2    | CAS_V2   | ASCII `"01RW\r"`    | Same 20-char CAS format                 |
| 5    | TYPE5    | None (burst)        | 21-byte STX-framed: `\x02...weight...!\r` |

CAS_V1 and CAS_V2 are both variants of the **CAS NT-500** weighbridge indicator protocol. V1 polls with a single binary byte; V2 polls with a human-readable ASCII command. The response packet format is identical for both.

TYPE5 devices stream data continuously without being polled. The server only needs to listen and parse.

---

## 9. How Java solves the core problems efficiently

### 8.1 Decoupling producer and consumer — `SynchronousQueue`

The serial receive thread and the poll thread need to coordinate: the poll thread must not send a new command while a response is still in flight. A `SynchronousQueue<Boolean>` acts as a zero-capacity, non-blocking signal:

```java
// In onData() — serial receive thread
gotData.offer(TOKEN);       // drops silently if poll loop hasn't started poll() yet

// In scalingLoop() — poll thread
gotData.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);  // blocks until signal or timeout
```

`offer()` never blocks and never throws — if the poll thread hasn't reached `poll()` yet, the token is simply dropped. The next round starts `poll()` fresh. This avoids a busy-wait spin loop and eliminates any risk of one round's response being mistaken for the next round's.

### 8.2 Waking a sleeping HTTP handler — monitor pattern

The SSE handler thread spends almost all its time sleeping inside `lock.wait(15000)`. When a weight reading arrives, `publish()` calls `lock.notifyAll()` to wake it immediately — there is no polling and no delay. The handler picks up the payload atomically inside the same `synchronized` block.

The `payloadSeq` counter ensures the handler never sends the same payload twice, even if `notifyAll()` fires spuriously (which Java allows by specification).

### 8.3 Single-client eviction without a race condition — `volatile` + `synchronized`

The `activeClient` field is `volatile` for cheap reads from non-critical paths (e.g., checking if anyone is connected). But the actual **swap** — read old, write new, close old — happens inside `synchronized(lock)`:

```java
synchronized (lock) {
    prev = activeClient;
    activeClient = out;
}
if (prev != null) prev.close();
```

Without `synchronized`, two browser tabs connecting simultaneously could both read the old `activeClient` as `null`, both set themselves as active, and the first one would not be closed — resulting in two active streams. The synchronized block makes the read-and-replace atomic.

### 8.4 Thread-safe callbacks with `volatile Consumer<>`

All callbacks (`onWeight`, `onStatus`, `onData`, `onConnect`) are `public volatile Consumer<T>` fields. They are set from the main thread before any background thread starts reading them. `volatile` guarantees that the write is immediately visible to all threads — no `synchronized` block needed for this one-time assignment.

Wrapping each callback invocation in a `try/catch` ensures a bug in the callback (e.g., in the `publish()` call) cannot crash the serial receive thread.

### 8.5 Immutable model with `final` fields

`WeightReading` uses `final` for every field. This means:
- No defensive copies needed when passing to multiple threads.
- The compiler enforces that every field is assigned in the constructor.
- There is no risk of a partially-constructed object being observed by another thread (the Java Memory Model guarantees that `final` fields are safely published after construction).

### 8.6 Auto-reconnect with blocking retry loop

`CommPort.runLoop()` is a simple `while (running.get())` loop. When the port throws an exception (cable pulled), the `finally` block closes it cleanly, calls `notifyConnect(false)`, sleeps 5 seconds, and loops back to `openPort()`. This design:
- Needs no external scheduler.
- Does not burn CPU — the thread sleeps between retries.
- Stops cleanly when `running.set(false)` + `rxThread.interrupt()` is called from `close()`.

### 8.7 Raw `ServerSocket` instead of `HttpServer`

`com.sun.net.httpserver.HttpServer` in JDK 21 calls `exchange.sendResponseHeaders(200, 0)` internally and then closes the response body — making SSE impossible. By building directly on `ServerSocket`, we control exactly when data is written and flushed, and we never close the stream until the client disconnects.

The HTTP implementation is intentionally minimal: it parses only the request line and method, handles `OPTIONS` for CORS, and routes `GET /stream`. Everything else is 404. There is no need for a full HTTP stack.

### 8.8 Fat JAR — zero-install deployment

`maven-assembly-plugin` bundles all dependencies (jSerialComm, Gson, JavaFX for all platforms) into a single JAR. `jSerialComm` includes its native `.dll`/`.so` files inside the JAR and extracts them to a temp directory at runtime — no JNI installation step.

`launch4j-maven-plugin` wraps the fat JAR as a Windows `.exe`, allowing double-click launch and specifying `minVersion=21` in the JRE manifest so Launch4j shows a clear error if the wrong JDK is installed.

---

## 10. Thread model

| Thread name          | Type   | Role                                                        |
|----------------------|--------|-------------------------------------------------------------|
| `main`               | non-daemon | Parks on `Thread.currentThread().join()` after setup   |
| `JavaFX Application` | daemon | Runs the FX event loop for `StatusWindow`                  |
| `scale-init`         | daemon | Resolves port (possibly scanning), creates `ScaleReader`    |
| `com:COMx`           | daemon | Serial receive loop — reads data from the port              |
| `poll:deviceName`    | daemon | Poll loop — sends commands every 500 ms                     |
| `sse-accept`         | daemon | TCP accept loop — spawns a handler per connection           |
| `sse-handler`        | daemon | One per SSE client — writes events, sleeps between them     |
| `shutdown-hook`      | non-daemon | Stops reader and server on JVM exit signal              |

Because all worker threads are daemon threads, the JVM exits the moment the main thread finishes (or when Ctrl+C is received and the shutdown hook completes).

---

## 11. Build and package

```bash
mvn package
```

Outputs:
- `target/scale-server-1.0.0-jar-with-dependencies.jar` — fat JAR for any OS with Java 21
- `target/scale-server.exe` — Windows executable (via launch4j)

**Dependencies:**

| Library           | Version | Purpose                                          |
|-------------------|---------|--------------------------------------------------|
| `jSerialComm`     | 2.10.4  | Cross-platform serial port I/O with bundled JNI  |
| `Gson`            | 2.10.1  | JSON serialization for SSE payloads              |
| `javafx-controls` | 21      | Operator status window (all platform classifiers bundled) |

---

## 12. Logging and debugging

**Normal mode** — only `INFO` and above is logged to the console and `scale_server_0.log`.

**Debug mode** (`debug=true` in `scale.properties`) — additionally writes `FINE`-level traces to `scale_debug.log`, including:
- Raw bytes received from the serial port (non-printable bytes shown as `\xNN`)
- Parsed field values for each packet
- Frame validation failures for Type 5 packets

The debug log is **overwritten** on every run — it is a temporary capture for diagnosing serial protocol issues, not a permanent audit log.

Log format:
```
2026-05-04 10:23:45 [INFO   ] ScaleReader      [CAS NT-500 (v1)] ScaleReader started (type=CAS_V1)
```

jSerialComm's own verbose logging is suppressed to `WARNING` to reduce noise. JavaFX module warnings are suppressed to `SEVERE` (the "Unsupported JavaFX configuration" message that appears when running from a fat JAR is cosmetic and does not affect functionality).
