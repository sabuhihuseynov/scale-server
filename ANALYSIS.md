# Scale Server — Implementation Analysis

## Hardware Overview

| Device | Protocol | Serial | Notes |
|--------|----------|--------|-------|
| CAS NT-500 Series | RS-232C, polled | 9600 8N1 | 20-char ASCII line per reading |
| Tunaylar Load Line 2 | RS-232/422/485, burst | 9600 8N1 | STX-framed data pushed continuously |

---

## Java Project Architecture

```
Main → AppConfig (scale.properties)
     → ScaleReader → CommPort (jSerialComm, auto-reconnect)
                  → ScaleProtocol (packet parser)
                  → onWeight callback
     → Broadcaster (ReentrantLock pub/sub, sequence cursor)
     → SseServer   (Java 21 virtual threads, HTTP /stream + /health)
```

**Transport:** Python used WebSocket (port 6789) — Java replaces it with **SSE over HTTP** (port 8080).  
**Dependencies:** jSerialComm 2.10.4 (cross-platform, no JNI install), Gson 2.10.1, JDK 21 built-in HTTP server.  
**Build output:** single fat JAR (`scale-server-1.0.0-jar-with-dependencies.jar`).

---

## Class Reference

### `com.scale.protocol`

**`ScaleProtocol`**  
Pure-static parser with no state. The single entry point `parse(packet, type, deviceName, scaleToKq)` dispatches to the appropriate private method based on `IndicatorType`. Returns an immutable `WeightReading` or `null` on malformed input — it never throws.

- `parseCas` — handles CAS_V1 and CAS_V2. Validates the 20-char length, extracts status/weight/unit/lampFlags at fixed offsets, and delegates field extraction to private helpers so every local variable is effectively final (required for lazy-lambda logging).
- `parseType5` — handles the burst format. Anchors on the `!` character, validates STX at `[0]` and CR at `[20]`, then extracts the 6-char weight from bytes `[5, 11)`. Returns `null` if either boundary byte is wrong — this is the source of the [critical terminator risk](#critical-issue-type5-packet-terminator-mismatch).
- `decodeLampFlags` — decodes the CAS byte at offset 7 into a `{zero, tare, net, hold, stable}` boolean map included in every SSE weight event.

---

### `com.scale.serial`

**`CommPort`**  
Thin wrapper around jSerialComm. Owns one background `rxThread` that opens the port, reads data in a loop, fires `onData` / `onConnect` callbacks, and automatically reconnects after a 5-second delay on any error. Thread-safe: `write*` methods capture `activePort` into a local reference so a concurrent `close()` cannot cause a null-pointer mid-write. Two read strategies selected by `IndicatorType`:

- `readLines` — used by CAS_V1 / CAS_V2. `BufferedReader.readLine()` blocks up to 1 second per call, which keeps the thread interruptible without busy-polling.
- `readBurst` — used by TYPE5. Sleeps 300 ms, then drains all available bytes in one read. Matches the C# `Thread.Sleep(300); com1.ReadExisting()` pattern.

**`ScaleReader`**  
Orchestrates `CommPort` and the polling rhythm. Runs a `pollThread` that sends the poll command (`0x01` for CAS_V1, `"01RW\r"` for CAS_V2, nothing for TYPE5), then blocks on a `SynchronousQueue` for up to 500 ms waiting for `onData` to signal a response. When data arrives, it calls `ScaleProtocol.parse` and fires the `onWeight` callback. This mirrors the C# timer pattern: send command → wait for response → repeat.

---

### `com.scale.server`

**`Broadcaster`**  
Single-slot pub/sub bus protected by a `ReentrantLock`. Holds the latest JSON string and a monotonic `seq` counter. `publish(json)` stores the payload, increments `seq`, and calls `signalAll()`. `waitNext(lastSeq, timeoutMs)` blocks until `seq` advances past the caller's cursor or the timeout elapses — the timeout is used as a heartbeat interval by `SseServer`. Each SSE client thread keeps its own `seq` cursor so slow clients jump straight to the most recent reading rather than draining a backlog.

**`SseServer`**  
Embedded HTTP server (JDK `com.sun.net.httpserver`). Uses a virtual-thread-per-task executor so each SSE connection gets its own virtual thread — cheap to block, cheap to create, no pool sizing needed. Exposes three routes:

| Route | Method | Response |
|-------|--------|----------|
| `/stream` | GET | `text/event-stream`, chunked, long-lived |
| `/health` | GET | `{"ok":true}` |
| `/` (catch-all) | * | 404 |

The SSE loop writes `data: <json>\n\n` for each new reading and `: heartbeat\n\n` every 15 seconds to keep proxies and firewalls from closing idle connections. `IOException` on write is the normal client-disconnect path; setting `connected = false` exits the loop cleanly without re-throwing.

---

## Protocol Support Matrix

| Code | Enum | Poll command | Packet format |
|------|------|-------------|---------------|
| 1 | `CAS_V1` | byte `0x01` | 20-char ASCII line (see below) |
| 2 | `CAS_V2` | `"01RW\r"` | same 20-char CAS format |
| 5 | `TYPE5` | none (burst) | 21-byte STX-framed (see below) |

### CAS NT-500 packet (Type 1 / 2)
```
Offset  Len  Content
0       2    Status: ST=stable | US=unstable | OL=overload
2       1    ","
3       2    Weight type: GS=gross | NT=net
5       1    ","
6       1    Device-ID char
7       1    Lamp-flags byte (bitmask)
8       1    ","
9       8    Weight right-aligned ASCII (e.g. "  110.900")
17      2    Unit (e.g. "kg")
19      1    trailing
```

### Type 5 burst packet (Tunaylar / working Python device)
```
Offset  Len  Content
0       1    STX (0x02)
1       4    "!10 "
5       6    Weight ASCII (e.g. "000140" = 140 kg)
11-19   ...  remainder
20      1    CR (0x0D)  ← Java/C# expect this
```

---

## End-to-End Flow

### 1. Startup (all indicator types)

```
java -jar scale-server.jar
 │
 ├─ AppConfig.load()
 │    reads scale.properties next to the JAR
 │    → port=COM3, baud=9600, indicator.type=1, http.port=8080
 │
 ├─ new Broadcaster()          in-memory pub/sub bus
 ├─ new SseServer(8080, ...)   binds TCP :8080
 ├─ new ScaleReader(...)       wires callbacks:
 │    scaleReader.onWeight  →  broadcaster.publish(json)
 │    scaleReader.onStatus  →  broadcaster.publish(json)
 │
 ├─ sseServer.start()
 │    HTTP server ready — browsers can connect to /stream immediately
 │    (they'll just block waiting for the first weight reading)
 │
 └─ scaleReader.start()
      CommPort.open() — starts rxThread (background, daemon)
      ScaleReader starts pollThread (background, daemon)
```

---

### 2. Reading cycle — CAS_V1 (`indicator.type=1`, CAS NT-500)

The scale is **polled**: the server must ask before the scale responds.  
Poll command: single byte `0x01`.

```
pollThread                        rxThread                        Browser
    │                                 │                               │
    │── send byte 0x01 ──────────────►│  (serial out to scale)        │
    │                                 │                               │
    │                      Scale receives 0x01 and replies:           │
    │                      "ST,GS,ÿ\x80,  1234.500kg\n"              │
    │                                 │                               │
    │                    readLine() unblocks                          │
    │                    CommPort.fireOnData("ST,GS,ÿ\x80,  1234.500kg")
    │                                 │                               │
    │◄── gotData.offer(TOKEN) ────────┤                               │
    │                                 │                               │
    │                    ScaleProtocol.parseCas(raw):                 │
    │                      [0:2]  "ST"  → stable=true                │
    │                      [3:5]  "GS"  → weightType="gross"         │
    │                      [7]    0x80  → lamp: stable bit set        │
    │                      [9:17] "1234.500" → weight=1234.5 kg       │
    │                      [17:19] "kg" → unit="kg"                   │
    │                                 │                               │
    │                    onWeight fired:                              │
    │                    broadcaster.publish(json)                    │
    │                    Broadcaster.seq++ + signalAll()              │
    │                                 │                               │
    │                                 │   virtual thread unparks ────►│
    │                                 │   write "data: {...}\n\n"     │
    │                                 │   flush ──────────────────────►browser receives event
    │                                 │                               │
    │  gotData.poll(500ms) returns     │                               │
    │  → loop restarts after 500ms    │                               │
```

**Packet wire example:**
```
"ST,GS,\x05\x80,  1234.500kg"
 ^^  ^^  ^  ^    ^^^^^^^^  ^^
 ST  GS  id lmp  weight    unit
```

---

### 3. Reading cycle — CAS_V2 (`indicator.type=2`, CAS NT-500)

Identical to CAS_V1 except the poll command is the ASCII string `"01RW\r"` instead of byte `0x01`.  
`01` = device address, `RW` = Read Weight, `\r` = terminator.  
The response packet format is exactly the same 20-char CAS line.

```
pollThread
    │
    │── write "01RW\r" (5 ASCII bytes) ──► scale
    │                                          │
    │                               scale replies with same
    │                               20-char CAS line as CAS_V1
    │                                          │
    │◄─────────── (same flow as CAS_V1 from here) ────────────────────
```

---

### 4. Reading cycle — TYPE5 (`indicator.type=5`, Tunaylar Load Line 2)

The scale is **not polled**: it pushes frames continuously on its own.  
The server just drains the buffer every 300 ms.

```
pollThread                        rxThread                        Browser
    │                                 │                               │
    │  (no poll command sent)         │                               │
    │                                 │                               │
    │                      Scale pushes continuously:                 │
    │                      "\x02!10 000140 000000\x0D"               │
    │                      "\x02!10 000141 000000\x0D"  ...          │
    │                                 │                               │
    │                    sleep(300ms) then drain buffer               │
    │                    CommPort.fireOnData("\x02!10 000140 ...")    │
    │                                 │                               │
    │◄── gotData.offer(TOKEN) ────────┤                               │
    │                                 │                               │
    │                    ScaleProtocol.parseType5(raw):               │
    │                      find '!' at index 1                        │
    │                      step back → STX at index 0                 │
    │                      chars = raw[0:21]                          │
    │                      validate chars[0]=0x02, chars[20]=0x0D    │
    │                      chars[5:11] = "000140" → weight=140 kg    │
    │                                 │                               │
    │                    onWeight fired:                              │
    │                    broadcaster.publish(json)                    │
    │                    Broadcaster.seq++ + signalAll()              │
    │                                 │                               │
    │                                 │   virtual thread unparks ────►│
    │                                 │   write "data: {...}\n\n"     │
    │                                 │   flush ──────────────────────►browser receives event
    │                                 │                               │
    │  gotData.poll(500ms) returns     │                               │
    │  → loop restarts                │                               │
```

**Packet wire example:**
```
\x02 !  1  0     0  0  0  1  4  0     0  0  0  0  0  0  \x0D
[0]  [1][2][3][4][5][6][7][8][9][10][11]...           [20]
STX  !  1  0  sp weight(6)  sp  second-field(6)        CR
```

---

### 5. Browser connects to `/stream`

Each browser tab gets its own virtual thread. Multiple tabs are all served simultaneously with no extra OS threads.

```
Browser opens EventSource("http://localhost:8080/stream")
 │
 └─ SseServer.handleStream()  [new virtual thread spawned]
      │
      ├─ send HTTP 200 headers (Content-Type: text/event-stream, chunked)
      │
      └─ loop:
           │
           ├─ broadcaster.waitNext(lastSeq=0, timeout=15s)
           │    virtual thread PARKS here (OS carrier thread freed)
           │    ↑ woken by Broadcaster.signalAll() when scale sends data
           │
           ├─ [new data] → write "data: {json}\n\n" + flush → browser receives it
           │               lastSeq advances, loop repeats
           │
           ├─ [15s timeout, no data] → write ": heartbeat\n\n" + flush
           │                            keeps TCP alive through proxies
           │
           └─ [client closes tab] → os.flush() throws IOException
                                     connected=false, loop exits cleanly
```

---

### 6. Scale disconnects and reconnects

```
Cable unplugged (or scale powered off)
 │
 └─ rxThread: readLine() / readBurst() throws IOException
      │
      ├─ silentClose(port)
      ├─ notifyConnect(false)
      │    └─ onStatus(false) → broadcaster.publish({"type":"status","connected":false})
      │         └─ all SSE clients receive: data: {"type":"status","connected":false}
      │
      ├─ sleep 5 seconds
      │
      └─ retry openPort()
           ├─ port still missing → sleep 5s → retry (indefinite loop)
           │
           └─ port back → notifyConnect(true)
                └─ onStatus(true) → broadcaster.publish({"type":"status","connected":true})
                     └─ all SSE clients receive: data: {"type":"status","connected":true}
                          normal weight readings resume
```

---

## Critical Issue: TYPE5 Packet Terminator Mismatch

**This is the highest-risk finding.**

The Python script that works on the client machine processes packets like:
```
b'\x02!10 000140 000000\x03\x07'
```
It finds `STX`, then finds `ETX (0x03)`, and extracts the payload between them.  
The terminator it sees is **ETX (0x03)**, not **CR (0x0D)**.

The Java `ScaleProtocol.parseType5()` (mirroring the C# `ParseWeight` for Type 5) validates:
```java
if (chars.charAt(0) != 0x02 || chars.charAt(20) != 0x0D) return null;
```

**If the real device sends ETX-terminated packets (`\x03\x07`), `chars.charAt(20)` will not be `0x0D` and every TYPE5 packet will be silently discarded — the server will never emit a weight reading for this device.**

**Recommendation:** Confirm the actual terminator byte from the device with a raw serial capture, then either:
- Add ETX (0x03) as an accepted terminator in `parseType5`, or
- Add a second `extract_complete_packets`-style method that mirrors the Python logic.

---

## Other Gaps and Observations

### 1. No port auto-discovery
Python scans all COM ports for `b'\x02!10'` until it finds the scale. Java requires the port to be set in `scale.properties` (defaults to `COM3`). On a new machine this will fail silently until the file is configured.

**Recommendation:** Add a one-shot port scan on startup (same logic as Python's `find_scale_port`) when `port` is not set or the configured port fails to open.

### 2. Parallel-port commands removed
`sendGreen`, `sendRed`, and `sendZero` were stubs — nothing in the codebase called them. The C# reference used `PortAccess` (a Windows parallel-port DLL) to drive physical lamps on the weighbridge gantry. There is no portable Java equivalent. All three methods and the associated `zeroPending` field have been removed. If lamp control is needed in the future, it would require a dedicated HTTP endpoint and Windows-only native integration.

### 3. `CommPort.openPort()` null-check — fixed
`SerialPort.getCommPort(name)` in jSerialComm **never returns null** — it creates a port handle regardless of whether the port physically exists. The dead `if (p == null)` guard has been removed; a comment explains the actual failure path (`openPort()` returning false).

### 4. TYPE5 weight extraction is consistent between Python and Java
Despite the terminator difference, the weight field position is the same:
- Python: `tokens = payload.split()` → `tokens[1]` = chars 5-10 relative to STX
- Java: `chars.substring(5, 11)` = same bytes

Both apply `scaleToKq` multiplier. No logic discrepancy here assuming a valid frame is found.

### 5. Unstable/overload readings still emitted
For CAS format, `extractCasWeight` returns `0.0` for non-ST packets (US, OL), but `parseCas` still constructs a `WeightReading` and emits it with `stable=false` or `overload=true`. SSE clients will receive these. This is intentional and correct — the client can filter by `stable` flag — but worth documenting for frontend consumers.

### 6. C# `CommPort.Close()` is a no-op
The reference C# `Close()` method body is entirely commented out. This is a known defect in the client's existing codebase, not something inherited by the Java port.

---

## Configuration Reference (`scale.properties`)

| Key | Default | Description |
|-----|---------|-------------|
| `port` | `COM3` | Serial port (`/dev/ttyUSB0` on Linux) |
| `baud` | `9600` | Must match DIP-switch on device |
| `indicator.type` | `1` | 1=CAS_V1, 2=CAS_V2, 5=TYPE5 |
| `scale.to.kq` | `1.0` | Weight multiplier (1.0 = already kg) |
| `http.port` | `8080` | SSE server port |
| `device.name` | *(auto)* | Label in SSE events |

---

## SSE Event Format

**Weight event:**
```json
{
  "type": "weight",
  "device": "CAS NT-500 (v1)",
  "weight": 1234.567,
  "unit": "kg",
  "stable": true,
  "overload": false,
  "weightType": "gross",
  "timestamp": "2026-04-30T10:00:00Z",
  "lampFlags": { "zero": false, "tare": false, "net": false, "hold": false, "stable": true }
}
```

**Status event:**
```json
{ "type": "status", "device": "CAS NT-500 (v1)", "connected": true }
```

**Heartbeat (keep-alive, not a data event):**
```
: heartbeat
```

---

## Summary

The Java implementation is a solid, production-ready translation of the Python + C# reference implementations. The architecture is clean, the threading model is sound, and virtual threads make SSE handling efficient.

**The one issue that must be verified before deployment to the client machine is the TYPE5 packet terminator.** Everything else (missing port discovery, stub commands) is lower priority and can be addressed incrementally.
