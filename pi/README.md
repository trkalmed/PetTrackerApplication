# Pet Tracker — Raspberry Pi service

The sensor side of the app. Reads the feeder, load cell, PIR sensors, camera and
GPS, buffers what it finds, and serves it over HTTP on the LAN.

**Standard library only.** Nothing to `pip install` on a fresh Raspberry Pi OS
image — no dependency that might lack an ARM wheel, nothing to keep updated. The
real-hardware drivers are the only exception, and each is imported lazily so the
service still starts when a sensor is missing.

## Run it now, without a Pi

```bash
python3 run.py --fake
```

Simulated sensors, same service, same HTTP contract. Point the Android app's
**Pi Connection** dialog at the machine's IP (the service prints it on startup)
and the entire app-to-Pi path works before any wiring exists.

The fakes are not stubs returning constants. The bowl loses weight while a cat
eats and gains it on a refill, PIR sensors fire in bursts and go quiet, the GPS
reports no fix for its first few reads. Code that copes with those will mostly
cope with the real thing.

On the Pi, once the hardware is wired:

```bash
python3 run.py
```

Useful flags: `--port` (default 8080), `--rooms "Kitchen,Hall,Bedroom"`,
`--fps`, `--db`, `-v`.

## The HTTP contract

The app is the client and polls; the Pi never pushes. The Pi is always on and
the phone is not, so the Pi buffers and the phone catches up when it can.

### `GET /health`

```json
{
  "ok": true,
  "time_ms": 1750000000000,
  "uptime_s": 3600,
  "sensors": {
    "scale": true, "feeder": true, "camera": true, "location": false,
    "motion": {"Kitchen": true, "Living room": true, "Hall": false}
  },
  "latest_cursor": 42,
  "latest_weight_grams": 118.4
}
```

Per-sensor availability is the point: it distinguishes a Pi with the load cell
unplugged from one that simply has nothing to report. Bring sensors up one at a
time and watch this.

### `GET /events?since=<cursor>`

```json
{
  "events": [
    {"id": 43, "kind": "feeding", "timestamp_ms": 1750000000000,
     "source": "load-cell", "grams": 40, "type": "EATEN"},
    {"id": 44, "kind": "motion", "timestamp_ms": 1750000005000,
     "source": "pir", "room": "Kitchen"}
  ],
  "next_cursor": 44,
  "has_more": false
}
```

Send `since=0` first, then the `next_cursor` from the previous reply. The cursor
is **exclusive**, so re-sending the last one returns nothing rather than a
duplicate. Keep polling while `has_more` is true.

The cursor is a row id, not a timestamp. A Pi has no real-time clock, so its
clock jumps when NTP first syncs after boot — a timestamp cursor would skip or
repeat events across that jump.

Event kinds:

| `kind` | Fields | Meaning |
|---|---|---|
| `feeding` | `grams`, `type` | `EATEN` (weight left the bowl) or `DISPENSED` (weight arrived) |
| `motion` | `room` | A PIR fired, debounced to one event per room per 30 s |
| `weight` | `grams` | Raw bowl weight, logged once a minute for diagnostics |

**Events carry no pet id.** A load cell cannot tell which cat ate; the app
attributes readings to whichever pet is active. That is why
`PetDataRepository.recordFeeding(petId, ...)` takes the id on the app side.

### `GET /stream`

`multipart/x-mixed-replace` MJPEG — the format `MjpegInputStream` already
parses, on the port `PiConnectionManager` already builds. Nothing on the app
side needs changing to consume it.

### `POST /feed` — `{"grams": 40}`

Runs the servo. Rejects non-positive values and anything over 500 g.

**It does not record a feeding event**, on purpose. The servo controls how long
the gate is open, not how much comes out. The load cell measures what actually
landed and the meal detector logs that. Recording the request as well would
double-count and would store a number nothing verified. Expect the `DISPENSED`
event that follows to differ from what you asked for — that difference is real.

### `POST /tare`

Zeroes the scale and resets the meal detector's baseline.

## How meals are worked out

`meals.py`, and it is the part most worth understanding.

A load cell does not report "the cat ate 40 g". It reports a weight, several
times a second, that wanders a gram or two from electrical noise, dips when the
bowl is nudged, and falls in fits and starts while an animal eats.

Three rules turn that into meals:

- **Size.** Changes under `min_change_grams` (5 g) are never food.
- **Settling.** A change is only committed once the weight has held steady for
  `settle_ms` (4 s). Without this, one meal with a pause in it becomes several.
- **Direction.** Down is `EATEN`, up is `DISPENSED`.

Two subtleties that cost real debugging, both pinned by tests:

**Stability is judged across a window, not between neighbours.** A cat eating
produces a gradual decline whose step-to-step change is *smaller than the noise
floor*, so comparing consecutive readings reports "not moving" throughout an
entire meal.

**Drift is separated from meals by timescale, not size.** Thermal drift and the
opening seconds of a slow meal look identical in magnitude. Drift is grams per
hour; eating is grams per second. So the baseline only follows a weight that has
held steady for `drift_settle_ms` (30 s) — far longer than any meal takes. An
earlier version adopted on size alone and quietly reported a 60 g meal as 57 g,
having absorbed the start of it.

Tune the constants against your own hardware. `replay()` runs a recorded trace
through the detector, so capture real readings and check the answer offline
rather than guessing at a live bowl.

## Wiring, and the traps

`sensors/real.py` holds the details per sensor. The ones that bite:

**Power the servo from its own 5 V supply, never the Pi's 5 V pin.** Stall and
start-up current browns out the Pi, and a brown-out during an SD-card write
corrupts the filesystem. Tie the grounds together, signal to a GPIO. This is the
most likely cause of a Pi that "randomly reboots" in this build, and it looks
exactly like a software bug.

**The HX711 calibration factor cannot be guessed.** Tare with an empty bowl, put
a known mass on it, and divide: `factor = raw / known_grams`. Redo it if the
bowl or its mounting changes.

**PIR sensors need ~60 s to settle** after power-up and fire constantly until
they do. They also have two physical pots — sensitivity and hold time. Set hold
short and let the software debounce, or the two fight each other.

**GPS needs the serial console disabled** (`raspi-config` → Interface Options →
Serial Port → login shell **no**, hardware serial **yes**), then use
`/dev/serial0` — `/dev/ttyAMA0` is wrong on a Pi 4. TX goes to RX and vice
versa. It will not get a fix indoors.

**One camera per stream.** The CSI and USB cameras cannot share `/stream`; run a
second instance on another port for the second camera.

And worth restating: **GPS on the Pi measures where the *Pi* is.** For a feeder
on a kitchen floor that is a constant, not pet tracking. The PIR sensors are
what answer "which room is the cat in".

## Bringing the hardware up

Each sensor reports itself unavailable rather than crashing when its hardware is
missing, so the service starts with everything unplugged. Add one at a time and
watch `/health`:

1. **Pi camera → `/stream`.** Highest value, lowest risk, and the app already
   consumes it, so you get a real end-to-end result first.
2. **HX711 + load cell.** Calibrate, then watch `kind: "weight"` events and tune
   the detector constants.
3. **Servo** — external supply first.
4. **PIR ×3.**
5. **GPS**, last.

## Tests

```bash
python3 -m unittest discover -s tests
```

38 tests, no hardware and no network required. They cover the meal detector
against realistic weight traces, the event store and its cursor (including a
clock that jumps backwards), and the HTTP contract driven against a real server
on a real socket — including parsing the MJPEG stream the same way the Android
`MjpegInputStream` does.

## Layout

```
run.py                  entry point, --fake or real
pettracker/
  server.py             HTTP endpoints and MJPEG streaming
  monitor.py            the polling loop: sensors in, events out
  meals.py              weight readings -> meals (pure logic, no I/O)
  store.py              SQLite event buffer and the polling cursor
  sensors/
    base.py             what the service needs from hardware
    fake.py             simulated implementations
    real.py             Raspberry Pi implementations
tests/
```

`sensors/base.py` is the seam. The service only ever talks to those interfaces,
which is what lets the whole thing be finished and tested before the hardware
exists — and what will let each fake be swapped for a real driver one at a time.
