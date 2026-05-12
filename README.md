# Scale Server

A Java 21 application that bridges a physical weighbridge (truck scale) connected via RS-232/USB serial to web frontends using **Server-Sent Events (SSE)**.

```
Physical Scale → RS-232/USB → scale-server (:8435) → Browser / Frontend
```

---

## Quick Start

**Requirements:** Java 21+, scale connected via RS-232 or USB-serial adapter.

```bash
java -jar scale-server-1.0.0-jar-with-dependencies.jar
```

On Windows, double-click `scale-server.exe`. A setup window opens — select your COM port and protocol, then click **Başla** (Start).

**Connect from the browser:**

```javascript
const es = new EventSource("http://localhost:8435/stream");
es.onmessage = e => {
    const { weight, unit, stable } = JSON.parse(e.data);
};
```

---

## Configuration — `scale.properties`

Place next to the JAR. All keys are optional; built-in defaults apply when absent.

```properties
port=AUTO              # COM port — AUTO scans all ports at startup
baud=9600              # Must match scale DIP-switch setting
indicator.type=1       # 1=CAS_V1  2=CAS_V2  5=TYPE5
scale.to.kq=1.0        # Weight multiplier (0.001 if device reports grams)
http.port=8435         # HTTP/SSE server port
device.name=           # Label included in every SSE event
debug=false            # Writes raw byte traces to scale_debug.log
```

---

## Architecture

```
AppConfig.load()
  └─► StatusWindow         operator selects port/protocol → clicks Start
  └─► SseServer.start()    binds :8435 immediately
  └─► [scale-init thread]
          └─► PortScanner  (if port=AUTO) probes each COM port
          └─► ScaleReader
                  └─► CommPort        serial receive loop
                  └─► poll loop       sends poll command every 500 ms
                          └─► ScaleProtocol.parse()
                                  └─► SseServer.publish(json)
                                  └─► StatusWindow.update()
```

The SSE server binds immediately so the browser can connect before the scale is found. All worker threads are daemon threads — the JVM exits cleanly when the main thread finishes or Ctrl+C fires.

---

## Components

| Component | Role |
|-----------|------|
| `AppConfig` | Immutable config value object loaded from `scale.properties` |
| `CommPort` | Serial port wrapper with auto-reconnect (5 s retry loop) |
| `ScaleReader` | Poll orchestrator; uses `SynchronousQueue` to sync poll/response |
| `ScaleProtocol` | Parses raw serial packets into `WeightReading` objects |
| `PortScanner` | Probes all COM ports at startup to auto-detect the scale |
| `SseServer` | Raw-socket HTTP server; supports one active SSE client at a time |
| `WeightReading` | Immutable data model with `final` fields, safe for cross-thread use |
| `StatusWindow` | JavaFX operator GUI — port/protocol selection, live status indicators |

---

## SSE Event Format

**Weight event** (sent ~every 500 ms):
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
  "timestamp":  "2026-05-04T10:23:45.123Z"
}
```

**Status event** (scale connects / disconnects):
```json
{ "type": "status", "device": "CAS NT-500 (v1)", "port": "COM3", "connected": true }
```

**Heartbeat** — SSE comment sent every 15 s to keep the connection alive:
```
: heartbeat
```

---

## Supported Protocols

| Code | Name   | Poll Command     | Packet Format |
|------|--------|------------------|---------------|
| 1    | CAS_V1 | Byte `0x01`      | 20-char ASCII: `ST,GS,1 ,  110.900kg` |
| 2    | CAS_V2 | ASCII `"01RW\r"` | Same 20-char CAS format |
| 5    | TYPE5  | None (burst)     | 21-byte STX-framed: `\x02...weight...!\r` |

CAS_V1 and CAS_V2 are both variants of the **CAS NT-500** indicator protocol. TYPE5 devices push data continuously — no polling required.

---

## Thread Model

| Thread | Role |
|--------|------|
| `main` | Parks on `join()` after wiring; shutdown hook fires on exit |
| `scale-init` | Resolves port (scanning if needed), starts `ScaleReader` |
| `com:COMx` | Serial receive loop — blocked at kernel level between bytes |
| `poll:deviceName` | Sends poll commands every 500 ms |
| `sse-accept` | TCP accept loop — spawns one handler per connection |
| `sse-handler` | Writes SSE events; sleeps on `lock.wait()` between readings |

---

## Build

```bash
mvn package
```

| Output | Description |
|--------|-------------|
| `target/scale-server-1.0.0-jar-with-dependencies.jar` | Fat JAR for any OS with Java 21 |
| `target/scale-server.exe` | Windows executable via launch4j (requires JRE 21+) |

**Key dependencies:**

| Library | Version | Purpose |
|---------|---------|---------|
| `jSerialComm` | 2.10.4 | Cross-platform serial I/O with bundled native drivers |
| `Gson` | 2.10.1 | JSON serialization for SSE payloads |
| `javafx-controls` | 21 | Operator status window |

---

## Logging

| Mode | Output |
|------|--------|
| Normal | `INFO`+ to console and `scale_server_0.log` |
| Debug (`debug=true`) | Adds raw byte traces to `scale_debug.log` (overwritten each run) |
