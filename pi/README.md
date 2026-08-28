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
`--pir-pins "17,27,22"`, `--pir-warmup`, `--camera csi|usb|none`,
`--camera-device`, `--snapshot-dir`, `--no-snapshots`, `--fps`, `--db`, `-v`.

While wiring the PIR sensors, `--check-motion` skips the service entirely and
shows the pins live — see **Bringing the hardware up**.

## Running it as a service

Started by hand over SSH, the service dies with the terminal — and the app then
looks broken rather than the Pi looking stopped. To have it start at boot and
restart itself:

```bash
./install-service.sh
```

Run it from `pi/`, as the user the service should run as. It substitutes that
user and directory into `pettracker.service`, installs the unit, and starts it.

```bash
journalctl -u pettracker -f          # what it is doing
sudo systemctl restart pettracker    # after changing anything
sudo systemctl status pettracker     # is it up, and since when
```

Rooms, pins and camera come from environment variables with defaults in the
unit. Override them without editing the unit or needing root by writing
`pi/service.env`:

```
PETTRACKER_ROOMS=Kitchen,Living room,Hall,Study
PETTRACKER_PIR_PINS=17,27,22,23
PETTRACKER_CAMERA=usb
```

Then restart. `service.env` is the better place for a room list containing a
space, because systemd parses it a line at a time and leaves the value alone.

Two whitespace traps in the unit itself, both of which produce a room list that
is quietly shorter than the one you wrote:

* `ExecStart` uses `${VAR}`, not `$VAR`. The latter is split on whitespace, so
  `Kitchen,Living room,Hall` would arrive as three separate arguments.
* `Environment=` takes a space-separated list of assignments, so the value has
  to be quoted: `Environment="PETTRACKER_ROOMS=Kitchen,Living room,Hall"`.
  Unquoted, it sets `Kitchen,Living` and discards the rest — which the service
  then reports as "2 room(s) but 3 pin(s)", an error about the pins that is
  really about the rooms.

**A USB camera can lose a race with boot.** Snapshots are enabled at startup
only if a camera answered, so if `/health` reports `"camera": false` after a
reboot but true after `systemctl restart pettracker`, the webcam had not
enumerated yet — add `ExecStartPre=/bin/sleep 10` to the unit.

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
| `motion` | `room`, `snapshot` | A PIR fired, debounced to one event per room per 30 s. `snapshot` is the file name to fetch from `/snapshot`, and is absent when there is no camera |
| `weight` | `grams` | Raw bowl weight, logged once a minute for diagnostics |

**Events carry no pet id.** A load cell cannot tell which cat ate; the app
attributes readings to whichever pet is active. That is why
`PetDataRepository.recordFeeding(petId, ...)` takes the id on the app side.

### `GET /snapshot?name=<file>`

The JPEG taken when a motion event fired, named by that event's `snapshot`
field. `image/jpeg`, cacheable forever — a snapshot never changes once written.

404 for a name that has been pruned **and** for a name of the wrong shape. Those
are deliberately the same answer: a file name arriving in a URL is untrusted
input, and distinguishing "invalid" from "missing" tells whoever asked that the
difference is worth exploring. Names are generated to one strict pattern and
checked against that same pattern on the way back out.

Snapshots are deleted on the same schedule as the events that name them, so an
`/events` reply never points at a picture that has already gone — but a phone
that has been offline for longer than the retention window can still find a
`snapshot` field with nothing behind it. Treat a 404 here as "no picture", not
as an error.

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

**RPi.GPIO does not work on a Pi 5.** It imports fine and then fails at
`setup()` with "Cannot determine SOC peripheral base address", because the Pi 5
put GPIO behind a new chip. `sensors/gpio.py` tries gpiozero, then RPi.GPIO,
then lgpio, and logs which one claimed the pin — so on a Pi 5 the answer is
gpiozero or lgpio, and both ship with Raspberry Pi OS. This is the first thing
to check when a correctly wired sensor reads nothing.

**PIR sensors need ~60 s to settle** after power-up and fire constantly until
they do. That warm-up is handled: `GpioMotionSensor` reports nothing for the
first `--pir-warmup` seconds and discards what it sees rather than queueing it.
The warm-up belongs to the *sensor* powering up, not to the process, so a
service restart on a rig that has been powered for hours can pass
`--pir-warmup 0` and lose nothing.

They also have two physical pots — sensitivity and hold time. Set hold short and
let the software debounce, or the two fight each other: the debounce in
`Monitor` already collapses a hold into one event per room per 30 s.

**Rooms and pins are positional.** `--rooms "Kitchen,Hall"` with
`--pir-pins "17,27"` puts the kitchen on GPIO 17. Mismatched lengths and
repeated pins are refused at startup rather than silently dropping a room.

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

1. **A camera → `/stream`.** Highest value, lowest risk, and the app already
   consumes it, so you get a real end-to-end result first. Pick which one with
   `--camera`:

   * **`--camera usb`** works today. Needs `sudo apt install python3-opencv`
     (apt, not pip — pip builds OpenCV from source and takes hours on a Pi). Use
     `--camera-device N` if the webcam is not `/dev/video0`.
   * **`--camera csi`** is the ribbon camera, and `PiCamera.read_jpeg` is still
     a stub. Filling it in with picamera2 is the remaining work there.
   * **`--camera none`** for a rig that has neither.
2. **HX711 + load cell.** Calibrate, then watch `kind: "weight"` events and tune
   the detector constants.
3. **Servo** — external supply first.
4. **PIR ×3.** See below; this one has a tool.
5. **GPS**, last.

### The PIR sensors

`/health` answers "did a library manage to claim the pin". That is not the same
question as "is this sensor pointing at the room its name says", and only the
second one matters once the app starts drawing charts from it. A PIR whose OUT
wire is on the wrong GPIO is indistinguishable, in the event log, from a cat
that never goes in the hall.

So wire one, then watch it:

```bash
python3 run.py --check-motion --rooms "Kitchen,Hall" --pir-pins "17,27" --pir-warmup 0
```

```
  room             pin  backend    state  fired   last
  Kitchen           17  gpiozero     ●        3    1s ago
  Hall              27  none         ·        0    unavailable
```

Wave at one sensor. Exactly one line should light up, and it should be the one
whose room name matches where you are standing. If the wrong name lights up, two
pins are swapped. If nothing does, work through the backend column first (`none`
means no library could claim the pin), then the wiring, then the sensitivity
pot. Ctrl-C prints a summary naming the likely cause for each sensor that never
fired.

It runs against the fakes too — `python3 run.py --fake --check-motion` — which
is a fair way to see what a working rig looks like before you have one.

Once the rooms are right, start the service normally and confirm the other half:
motion should appear in `/events` as `{"kind": "motion", "room": "Kitchen"}`,
debounced to one event per room per 30 s no matter how long the sensor is held.

### Motion snapshots

With a working camera, every motion event also saves a JPEG — the difference
between "the hall sensor fired at 03:00" and knowing which animal it was. The
event carries the file name; `/snapshot?name=…` serves it.

They are taken on the same debounce as the event, so a cat that settles in one
room produces one photo every 30 s rather than one every poll. Worst case is
still around 2,800 files per room per day, which is why they expire with their
events — and why `--no-snapshots` exists.

The camera is read by the monitor thread and by anyone watching `/stream`, so
`UsbCamera` serialises reads. Without that the two interleave and the driver can
hand back a half-decoded frame.

## Tests

```bash
python3 -m unittest discover -s tests
```

87 tests, no hardware and no network required. They cover the meal detector
against realistic weight traces, the event store and its cursor (including a
clock that jumps backwards), the PIR path (warm-up, pin configuration, a sensor
with no backend, and the debounce that turns a held sensor into one event), and
snapshot naming and retention (including the path-traversal defence), and the
HTTP contract driven against a real server
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
  bringup.py            --check-motion, the live sensor view
  snapshots.py          the JPEGs saved when something moves
  sensors/
    base.py             what the service needs from hardware
    gpio.py             claiming an input pin on any Pi generation
    fake.py             simulated implementations
    real.py             Raspberry Pi implementations
tests/
```

`sensors/base.py` is the seam. The service only ever talks to those interfaces,
which is what lets the whole thing be finished and tested before the hardware
exists — and what will let each fake be swapped for a real driver one at a time.
