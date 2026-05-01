# Scale Server — Architecture Reference

Single JAR desktop app. A weighbridge scale (CAS NT-500 or Tunaylar Load Line) talks
RS-232C → Java reads weight over serial → pushes to one browser tab via SSE.

---

## Component Map

```
Main
├── AppConfig          scale.properties loader (port=AUTO default)
├── PortScanner        auto-discovers the scale's COM port at startup
├── ScaleReader        poll loop + packet dispatch
│   ├── CommPort       jSerialComm wrapper, auto-reconnect on error
│   └── ScaleProtocol  stateless packet parser, returns WeightReading or null
└── SseServer          embedded HTTP server
    ├── GET /          status dashboard (HTML from resources/dashboard.html)
    │                  polls /info every 2 s — never connects to /stream
    ├── GET /stream    SSE event stream (for the frontend app only)
    └── GET /info      live JSON: port, device, scaleConnected, clientConnected
```

---

## Thread Model

Three threads at steady state:

```
┌─────────────────────────────────────────────────────────────────────────┐
│  Thread 1 — rxThread  (CommPort)                                        │
│                                                                         │
│  openPort() → read loop → fireOnData(raw) ──────────────────────────┐  │
│       ↑                                                             │  │
│  error: closePort() → sleep 5s → retry                             │  │
└─────────────────────────────────────────────────────────────────────│──┘
                                                                      │
                                                                      ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  Thread 2 — pollThread  (ScaleReader)                                   │
│                                                                         │
│  CAS_V1:  write 0x01    ─► scale                                        │
│  CAS_V2:  write "01RW\r" ─► scale       wait 500ms for gotData signal  │
│  TYPE5:   no command      (burst device)                                │
│                                                                         │
│  onData() ─► ScaleProtocol.parse() ─► onWeight() ─► SseServer.publish()│
└──────────────────────────────────────────────────┬──────────────────────┘
                                                   │
                   publish(): synchronized(lock)   │
                   latestPayload = json            │
                   payloadSeq++                    │
                   lock.notifyAll() ───────────────┘
                                                   │
┌──────────────────────────────────────────────────▼──────────────────────┐
│  Thread 3 — sse-handler  (SseServer)                                    │
│                                                                         │
│  synchronized(lock) {                                                   │
│    while (payloadSeq == lastSeq) lock.wait(15_000);  ← blocks here      │
│  }                                                                      │
│  write "data: {json}\n\n" → flush → browser                            │
│  on 15s timeout → write ": heartbeat\n\n"  (keeps TCP alive)           │
└─────────────────────────────────────────────────────────────────────────┘
```

The `synchronized(lock)` between threads 2 and 3 is standard producer-consumer
coordination — not related to multi-client. Even with a single client, two threads
share `latestPayload` and `payloadSeq`, and the lock ensures the SSE thread wakes
immediately when new data arrives.

---

## Port Auto-Discovery Flow

```
startup: port=AUTO?
         │
         yes
         │
         ▼
   getCommPorts()  ← jSerialComm enumerates only ports that physically exist
         │
   ┌─────▼──────────────────────────────────────────────┐
   │  for each port (typically 1–3 on a real machine):   │
   │                                                     │
   │  openPort(baud, 8N1)                                │
   │       │                                             │
   │  CAS_V1 → write 0x01                               │
   │  CAS_V2 → write "01RW\r"         listen 1.5s       │
   │  TYPE5  → (no command)                              │
   │       │                                             │
   │  response contains "ST," / "US," / "OL,"?  → MATCH │  CAS
   │  response contains STX (0x02) + '!'?        → MATCH │  TYPE5
   │       │                                             │
   │  closePort()                                        │
   └─────────────────────────────────────────────────────┘
         │
    match found ──► use that port name
         │
    no match ──► log warning, sleep 5s, retry all ports
```

If `port=COM5` is set explicitly, the scan is skipped entirely.

---

## Serial Protocols

### CAS NT-500 — Type 1 & 2 (20-char ASCII line)

```
Offset  Len  Field
──────  ───  ─────────────────────────────────────────────────────
0       2    Status:      ST = stable | US = unstable | OL = overload
2       1    ","
3       2    Weight type: GS = gross  | NT = net
5       1    ","
6       1    Device-ID char
7       1    Lamp-flags byte  (see bit layout below)
8       1    ","
9       8    Weight, right-aligned ASCII  e.g. "  1234.50"
17      2    Unit  e.g. "kg"
19      1    trailing char

Wire example:
  ST,GS,\x05\x80,  1234.500kg
```

Lamp-flag bits (offset 7):
```
bit 0 (0x01) → zero
bit 1 (0x02) → tare
bit 2 (0x04) → net
bit 6 (0x40) → hold
bit 7 (0x80) → stable
```

Poll commands:
```
CAS_V1:  0x01          (single byte)
CAS_V2:  "01RW\r"      (device 01, Read Weight)
```

### Type 5 — 21-byte STX-framed burst (Tunaylar Load Line)

```
Offset  Len  Field
──────  ───  ──────────────────────────────────────────────────────
0       1    STX (0x02)
1       4    "!10 "
5       6    Weight ASCII  e.g. "000140" = 140 kg
11      9    second field (unused)
20      1    CR (0x0D)

Wire example:
  \x02 !  1  0     0  0  0  1  4  0  ...  \x0D
  [0] [1][2][3][4][5][6][7][8][9][10]     [20]
```

Device pushes frames continuously — no poll command is sent.
Server drains the buffer every 300 ms.

> **⚠ Verify before deployment:** The working Python script saw ETX (0x03) as the
> terminator, not CR (0x0D). If the real device uses ETX, `parseType5()` will
> silently reject every frame. Capture raw bytes to confirm.

---

## HTTP Endpoints

### GET /info — dashboard status poll (JSON)
```json
{
  "port":            "COM3",
  "device":          "CAS NT-500 (v1)",
  "scaleConnected":  true,
  "clientConnected": false
}
```
`scaleConnected` — serial port is open and the scale is responding.  
`clientConnected` — the frontend app has an active `/stream` SSE connection.

The status dashboard polls this every 2 s. No SSE is involved.

### GET /stream — SSE event stream (frontend app only)

**Weight event** (emitted on every successful parse):
```json
{
  "type":       "weight",
  "device":     "CAS NT-500 (v1)",
  "port":       "COM3",
  "weight":     1234.5,
  "unit":       "kg",
  "stable":     true,
  "overload":   false,
  "weightType": "gross",
  "timestamp":  "2026-05-01T08:00:00Z",
  "lampFlags":  { "zero": false, "tare": false, "net": false, "hold": false, "stable": true }
}
```

**Status event** (on scale connect / disconnect):
```json
{ "type": "status", "device": "CAS NT-500 (v1)", "port": "COM3", "connected": true }
```

**Heartbeat** (SSE comment, not a data event, every 15 s):
```
: heartbeat
```

---

## Configuration (`scale.properties`)

| Key              | Default | Description                                              |
|------------------|---------|----------------------------------------------------------|
| `port`           | `AUTO`  | `AUTO` = scan all ports; or explicit e.g. `COM5`         |
| `baud`           | `9600`  | Must match the DIP-switch setting on the scale indicator |
| `indicator.type` | `1`     | `1` = CAS_V1, `2` = CAS_V2, `5` = TYPE5                 |
| `scale.to.kq`    | `1.0`   | Weight multiplier — `1.0` means device already sends kg  |
| `http.port`      | `8435`  | TCP port for the embedded HTTP server                    |
| `device.name`    | *(auto)*| Label shown in SSE events and status dashboard           |

---

## Log Files

`scale_server_0.log` — most recent. Rotates at 5 MB; three files kept (`_0`, `_1`, `_2`).
Restarts append to the current file rather than creating a new one.
Console shows INFO and above; file captures ALL levels (DEBUG, FINE, etc.).
