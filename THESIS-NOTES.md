# Pet Tracker — Thesis Source Material

Everything a written thesis about this project needs, gathered from the code and
the hardware bring-up sessions. **This is source material, not prose.** Hand it
to a writing tool, or write from it directly.

Facts here were read out of the repository and out of real hardware sessions.
Where a number is measured it says so. Where something is untested it says that
too — see §14, which exists to stop the write-up claiming more than was built.

---

## 1. What the system actually is

A two-part pet monitoring system:

- **A Raspberry Pi** in the home, wired to sensors, running a Python service
  that records what the sensors saw into a local database and serves it over
  HTTP on the LAN.
- **An Android application** that polls the Pi, stores readings locally, and
  presents them — food eaten, rooms visited, live camera, feeding on demand.

The design question the whole project answers: **the phone is not always on the
network, so readings cannot be delivered as they happen and forgotten.** The Pi
buffers everything; the app collects what it missed whenever it is opened.

One sentence for the abstract: *an end-to-end pet monitoring system in which a
Raspberry Pi sensor node buffers observations locally and an Android client
reconciles them idempotently, with each screen constrained to state only what a
sensor actually measured.*

---

## 2. Hardware inventory (all verified working)

| Component | Part | Connection | Verified |
|---|---|---|---|
| Motion | 3 × PIR (HC-SR501 class) | GPIO 17, 27, 22 | 3 sensors firing, named per room |
| Load cell | 5 kg bar + HX711 amplifier | DT=GPIO 5, SCK=GPIO 6, VCC 3.3 V | Calibrated, 385.66 counts/g |
| Feeder | SG90 servo, paddle wheel | GPIO 18 signal; LM2596 buck at 5.0 V | Dispenses from the app |
| GPS | u-blox NMEA receiver | module TX → GPIO 15 (RXD), 3.3 V | Fix from 12 satellites |
| Camera A | USB webcam | USB | Streams, snapshots |
| Camera B | CSI ribbon camera | CSI connector | 20/20 frames, 18.4 fps |
| Host | Raspberry Pi, Wi-Fi | — | systemd service, starts at boot |

**Power topology worth describing.** The servo runs from an LM2596 buck
converter, *not* the Pi. Only the ground is shared, so the servo's current never
passes through the Pi while GPIO 18's pulse still has a reference. The stated
reason: an SG90 stalled against jammed kibble draws close to an amp, and a
brown-out during an SD-card write is how a Raspberry Pi loses its filesystem.

The HX711 is powered from **3.3 V rather than 5 V** deliberately: at 5 V its data
line drives 5 V logic into a 3.3 V-only GPIO input, which is out of specification.

**A deployment constraint discovered by building it.** The feeder had to be moved
closer to the Pi. Five metres each way of jumper wire is roughly 2 Ω, which costs
1.4 V at a servo's stall current — enough that the servo buzzed and would not
turn, while measuring a healthy 5 V unloaded. The general point is worth stating
in a design chapter: **the sensors can sit metres from the Pi because they carry
signals; the actuator cannot, because it carries current.** The remedy is to run
the long cable at 12 V and convert beside the servo, so the low-voltage leg stays
short.

---

## 3. Software architecture

Two halves joined by one HTTP contract. Neither knows anything about the other
beyond that contract.

**Pi side** — Python, ~5,300 lines, **standard library only, no dependencies.**
That is a deliberate constraint, not an accident of scope: a sensor node that
must survive an OS reinstall and run unattended is worth more when it cannot
break through a dependency it did not choose to upgrade.

Modules: `server.py` (HTTP), `store.py` (SQLite event buffer), `monitor.py`
(sensor polling loop), `meals.py` (weight → meals), `hx711.py`, `nmea.py`,
`snapshots.py`, `bringup.py` (hardware diagnostics), and `sensors/` split into
`base.py` (interfaces), `real.py` (hardware), `fake.py` (simulation),
`gpio.py` (backend abstraction), `camera.py` (auto-recovery wrapper).

**App side** — Java/Android, ~7,700 lines across 48 classes and 15 layouts.
Room (SQLite) for local storage, Firebase Authentication, Glide for images,
CameraX plus TensorFlow Lite for on-device detection.

**Key architectural seam:** `PetDataRepository`. Every reading enters through it
and every screen reads through it. Nothing above that class knows whether a
reading came from HTTP, a socket, or a broker — so the transport could be
replaced without touching a single screen. Worth naming in a design chapter as
the thing that made the Pi integration a bounded change.

---

## 4. The HTTP contract

The interface between the two halves. Documented in `pi/README.md`.

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Per-sensor availability, uptime, latest cursor, live bowl weight, portion size, cameras by name |
| GET | `/events?since=<id>` | A page of buffered readings after a cursor |
| GET | `/snapshot?name=<file>` | One saved camera frame |
| GET | `/stream?camera=<name>` | MJPEG live video; the primary camera when unnamed |
| POST | `/feed` | `{"grams": n}` — run the feeder |
| POST | `/tare` | Zero the scale |

**Event kinds:** `feeding`, `motion`, `weight`, `location`. The contract states
that *adding* a kind is safe and *renaming* one is not — the app skips unknown
kinds rather than failing, so a newer Pi never breaks an older phone.

**The cursor is a row id, not a timestamp.** This is a design decision worth a
paragraph: a Raspberry Pi has no real-time clock, so its clock jumps when NTP
first syncs after boot, and two readings in the same millisecond are
indistinguishable. A monotonic row id has neither problem.

Pages are capped at 500 events; a single feed request is capped at 500 g.

---

## 5. Data model

**Pi:** one append-only `events` table — id, kind, timestamp_ms, payload (JSON),
source. WAL journal mode, because sensor threads write while the HTTP thread
reads. 30-day retention.

**App:** Room database, **version 7**, five tables — `pets`, `feeding_events`,
`detection_events`, `room_events`, `tracker_fixes`. Four hand-written
migrations (2→3 … 6→7), each validated against an exported schema JSON by an
instrumented test — and one further test that opens an old database the way the
app opens it, because validating a migration proves the SQL and proves nothing
about whether anything runs it. Destructive fallback is permitted **only** from version
1, which predates schema export; every later version ships a real migration so
a schema change cannot silently destroy a user's pets.

**Idempotent sync** is the mechanism worth explaining in detail. Each row keeps
the Pi's own event id as `remoteId`, under a unique index on
`(petId, source, remoteId)`, with `OnConflictStrategy.IGNORE`. A re-delivered
event collides and is dropped rather than counted twice. This is what makes it
safe for the app to restart the cursor from zero when it detects that the Pi's
log was rebuilt — replay becomes a no-op.

---

## 6. Signal processing and interpretation

The parts where raw sensor data becomes something a screen can show. Each is a
judgement, and each is worth defending in writing.

**HX711 load cell.** 24-bit bit-banged protocol, gain 128, MSB first. The
critical constraint: holding the clock line high for more than 60 µs powers the
chip down. Measured on this Pi — `gpiozero` was too slow; `lgpio` achieved
3.7 µs. This drove a backend-selection design where timing-critical devices
request a reversed preference order (lgpio first) while ordinary devices take
whichever backend is available. Readings are median-filtered, with saturation
and all-zero rejection.

**Meal detection** (`meals.py`) turns a weight series into meals: minimum change
5 g, weight must hold steady for 4 s before a change is committed, noise band
2 g, and a much longer 30 s before the baseline follows drift. The 4 s settle is
what stops one meal being recorded as five.

**Motion dwell model.** A PIR reports *movement, never presence* — it fires while
the cat crosses the kitchen and says nothing while the cat sleeps in it. So each
sighting speaks for the time until the next, capped at **15 minutes**. The
reasoning: too short and a nap reads as no time anywhere; too long and the last
sighting before bedtime credits the kitchen with the whole night. 15 minutes is
well past the Pi's 30 s debounce, and short enough that going quiet reads as
"we no longer know", which is the truth.

**GPS.** NMEA GGA sentences parsed with XOR checksum validation, ddmm→decimal
degrees conversion, and fix-quality checking. Fixes logged every 5 minutes.

**Object detection.** EfficientDet-Lite2 via TensorFlow Lite Task Vision 0.4.4,
score threshold 0.40, maximum 10 results, filtered to COCO animal classes before
anything is recorded.

**Route drawing.** Fixes are plotted directly rather than on a tiled map — a
maps SDK would mean an API key, a billing account and a network round trip to
display data the app already holds, and would make an offline demonstration
depend on a CDN. Longitude is scaled by cos(latitude) before plotting: a degree
of latitude is the same length everywhere while a degree of longitude shrinks
towards the poles, so plotting one against the other unscaled stretches an
east-west route by about 15% at 32°N. One scale factor serves both axes, so a
route keeps its true proportions instead of being stretched to fill the view.

**Route distance** discards steps under 3 m, because a stationary receiver
wanders by metres as satellite geometry shifts and summing that would have a Pi
on a table accumulate kilometres overnight. The comparison anchor stays at the
last *accepted* point rather than the previous one, so a slow walk sampled
quickly still accumulates instead of being discarded a sub-threshold step at a
time.

**Coverage gaps.** When the receiver loses lock, the next fix is far away and
joining the two draws a confident straight line through ground nothing measured.
Such segments are drawn broken and counted. The threshold is adaptive — four
times the median sampling interval, ten-second floor — because a constant cannot
serve both a 2 s walk and a 300 s fixed installation. Median rather than mean:
the gaps being detected would drag a mean upwards and hide themselves.

---

## 7. The design principle the whole project runs on

**Every screen states only what a sensor actually measured, and distinguishes
"no data" from "zero".** This recurs everywhere and is the strongest single
thread for a discussion chapter.

Concrete instances:

- `null` grams means *no feeder paired*; `0` means *paired, ate nothing*. The
  dashboard renders them differently.
- "No motion sensors reporting" and "sensors reporting, no motion" are separate
  sentences.
- The detections card says **"live view only"**, because that count comes from
  the phone's own detector, which runs only while the Live screen is open. It is
  "what was seen while watching", not the day's total, and saying otherwise
  would claim coverage the system does not have.
- The camera-live flag comes from the Pi's `/health` with a 2-minute freshness
  window — not from whether the phone's detector recently saw an animal, which
  is only ever true while somebody is watching.
- A dispense records **nothing**. The request says what was asked for; only the
  load cell knows what fell, and the meal arrives later, weighed. Recording the
  request would put an unverified number on the dashboard.
- The GPS fix is labelled **"Pi located"**, never as the pet's position, and the
  naming is carried into the schema (`tracker_fixes`, `TrackerFix`,
  `trackerLatitude`) so nothing downstream can quietly repurpose it.
- "Available" means *the sensor can do its job*, not *a handle was opened*.
- The live bowl weight is **never stored**. Everything else on that screen is
  history the Pi derived and the phone kept; this is what the hardware is saying
  this second, read from `/health` and discarded. The meal record is a
  measurement someone can cite; the live number is not.
- Asking a phone for grams was removed once the feeder metered in whole pockets:
  the box invited a precision the hardware does not have. The Pi reports what a
  portion is and the app only asks whether to dispense it.
- A **snapshot only comes from a camera that can see the room**. The ribbon
  camera is in the kitchen and every PIR was photographed with it, so a sighting
  in the bathroom arrived captioned "BedRoom/Bathroom" with a picture of the
  kitchen. The strongest instance of the principle in the project, because it
  inverts the usual cost: an empty card says "the sensor fired and there is no
  picture", while that card said "here is what happened in the bathroom" and
  showed somewhere else. A wrong picture is not a smaller version of a missing
  one — it is worse than nothing, because it is believed.
- The **daily food target belongs to the pet**, not to the app. It was a constant
  300 g for every animal — a plausible day for an average adult cat and
  meaningless for any particular one — so it is now a nullable column, and the
  dialog says to ask a vet because the app has no way to know.
- The **app words its feeder controls from what the Pi reports**. "Feed now" and
  "Open the hopper" are different promises, and the phone cannot tell which is
  true without being told, so /health carries the feeder's design.
- A route's **breaks in coverage are drawn as breaks**. Joining across a lost
  fix with the same stroke as measured ground was the one place the drawing
  claimed more than the data supported.

**Timestamps before 2020-01-01 are discarded.** A Pi with no RTC genuinely
believes it is 1970 between boot and first NTP sync. Those readings cannot be
placed on a day, so they are dropped rather than filed under 1 January 1970 —
or worse, moved to "now", which would invent a meal that never happened.

---

## 8. Testing and verification

**470 automated tests**, in three suites with different purposes:

| Suite | Count | Runs on | Covers |
|---|---|---|---|
| Pi | 293 | Any machine, no hardware | Protocols, parsing, event store, three feeder designs, cameras, HTTP, the systemd unit |
| Android unit (JVM) | 73 | Laptop | Wire format, HTTP client, date windows, MJPEG parsing, route geometry |
| Android instrumented | 104 | A real phone | Room migrations *and their registration*, repository, sync, layouts, theming |

**Fakes as first-class citizens.** `sensors/fake.py` lets the whole service run
with `--fake`, so the app can be developed against a simulated rig and the tests
run anywhere.

**Bring-up diagnostics as a design feature.** `bringup.py` provides
`--check-motion`, `--check-scale`, `--calibrate-scale`, `--check-gps`,
`--check-servo`, `--check-camera`. The justification is worth quoting in the
methodology: `/health` answers "did the library claim the pin", which is not the
same question as "is the sensor wired the right way round and pointing at the
right room". A PIR whose output is on the wrong GPIO is *indistinguishable, in
the event log,* from a cat that never goes in the hall. So the tooling lets a
human watch one sensor and confirm the right room name lights up.

**Configuration under test.** `test_service_unit.py` reads the systemd unit
file, does what systemd does — reads `Environment=` defaults, splits `ExecStart`
into words, expands `${VAR}` per word — and feeds the result to the *real*
argument parser. A unit that passes a flag the program does not have fails on a
laptop in a second, instead of exiting 2 into the journal on the Pi at boot.

---

## 9. Engineering incidents worth writing up

These are the most valuable material in the project for a thesis. Each is a real
fault, diagnosed from evidence, with a generalisable lesson. Do not skip them —
a project narrative with no failures reads as fiction.

**1. The dead amplifier, found by software.** The HX711 returned all zeros. The
investigation ruled out, in order: pin conflicts (added busy-pin detection),
protocol timing (added a clock-speed probe, found gpiozero too slow at the 60 µs
limit and switched to lgpio at 3.7 µs), and wiring order (added an automatic
pin-swap probe). Only after all software explanations were eliminated was the
amplifier declared dead — from a reversed VCC/GND that the user recalled. The
lesson: **the diagnostic tooling built to find the fault outlived the fault**,
and now ships as `--check-scale`.

**2. The wrong antenna, found without a spectrum analyser.** The GPS module
produced constant NMEA output and never a fix, with a satellite count stuck at
zero. Because `--check-gps` distinguishes "the module is silent" from "the module
is talking and knows nothing", the wiring and module were exonerated
immediately, and the diagnosis was a 2.4 GHz Wi-Fi antenna on a 1575.42 MHz
receiver. With the correct active antenna: a fix from **12 satellites**.
The lesson: **instrument the difference between failure modes, not just success
and failure.**

**3. The invisible configuration bug.** The service exited with status 2
complaining "2 room(s) but 3 pin(s)". The cause was in neither rooms nor pins:
systemd splits `Environment=` on whitespace, so an unquoted
`PETTRACKER_ROOMS=Kitchen,Living room,Hall` became two rooms and discarded the
rest. The error message pointed at the wrong setting entirely. Fixed by quoting,
and afterwards protected by the unit-file test in §8. Related: `${VAR}` versus
`$VAR` in `ExecStart` has the same failure, silently.

**4. A syntax error that survived a green test suite.** `run.py` was the one file
no test imported, so an unparseable version was committed and pushed and only
found by running the service by hand. Fixed by `test_sources_compile.py`, which
compiles every source file and runs `run.py --help`. The lesson:
**the file a user actually types is the file most worth protecting**, and
coverage measured over what tests import is blind to entry points.

**5. The emulator was hiding a broken test fixture.** 99 instrumented tests had
been passing on an emulator for weeks. Run against a real phone, **55 of them
failed immediately** with `no such table: pets`. The cause was in the test
fixture, not the app: each suite deleted the database file while a cached Room
singleton still held an open connection to it, and the emulator's handling of a
deleted-but-open file was lenient enough to hide it. The lesson:
**emulated verification and real-device verification are not interchangeable**,
and a test suite can be green because the environment is forgiving.

**6. A subsystem that broke the first time someone attached a monitor.** The USB
camera had worked headless for weeks. It stopped the day a screen was plugged in:
the desktop session's media stack (PipeWire) claims V4L2 devices, and only one
process may hold a camera. Booting to console was not enough — PipeWire is
socket-activated per user session and had to be masked. Immediately behind it sat
a second, independent fault with identical symptoms: with a CSI camera connected,
the ISP pipeline brings up a dozen `/dev/videoN` nodes and takes the low numbers,
so the webcam moved from `video0` to `video1` and "could not open video device 0"
was reported by a camera that was plugged in and working. Two unrelated causes,
one symptom, both invisible from the application. The lesson: **a device being
present, enumerated and powered says nothing about whether this process can have
it.**

**7. A confident wrong diagnosis, and correcting the record.** A servo that
clicked and would not move was diagnosed as having stripped gears, and that
conclusion was written into the code as justification for a constant. It was
wrong: the signal wire was on the wrong header pin (GPIO 18 is *physical pin
12*), and an unowned GPIO floats, so the servo was twitching at electrical noise
— which looks exactly like a stalling motor. The commit correcting it is in the
history. The lesson for a methodology chapter: **an intermittent symptom that
changes with every rewiring is evidence about the wiring, not about the
component**, and a diagnosis should not be recorded as fact in a place that
outlives the conversation that produced it.

**8. A green test suite that was blind to a whole category.** A database
migration was written, schema-validated by a passing test, and never registered
with the database builder. Every existing installation crashed on launch. The
suite stayed green because the migration tests hand each migration *directly* to
the validator — proving the SQL is correct and proving nothing about whether
anything ever runs it — while the repository tests delete the database first and
so are always created fresh at the current version, never migrating at all.
Between them they covered every migration and none of the registration. The fix
was a test that opens an old database the way the application opens it. **This is
the strongest test-design result the project produced**: a suite can be
comprehensive and still blind, when every test takes the same shortcut around
the thing that breaks.

**9. A use-after-free that arrived as a signal, not an exception.** Leaving the
live-detection screen killed the application. The teardown interrupted the
frame-producing thread and freed the TensorFlow Lite detector on the next line
without waiting, while that thread was usually inside inference — and closing a
TFLite detector frees native memory immediately. It surfaced as SIGSEGV in the
JNI library rather than a Java exception, so no amount of exception handling
would have helped. The generalisable point: **interrupting a thread does not stop
native code** — it sets a flag the thread reads between units of work, which is
no help when the unit of work is the problem.

**10. A control nobody could find.** A newly added setting was editable by
tapping a number that gave no indication it was tappable, and the handler was
attached after an early return for the empty state — so on exactly the object
whose setting a user would want to change, it did not exist at all. Worth a
sentence in a usability discussion: a feature is not delivered when the code
path exists, but when someone can find it without being told where it is.

**11. The failure mode of a camera that worked yesterday.** Only one process may
hold a CSI camera. A second copy of the service — including one started by hand
next to the systemd one — takes the camera and produces a fault that looks like
hardware. Handled by `ReopeningCamera`, which retries at a rate limit after
three consecutive failures rather than hammering the device.

---

## 10. Deployment

`systemd` unit, started at boot, `Restart=always` with a 5-second delay chosen so
a genuinely broken configuration appears as a restart loop in the journal rather
than being hidden by an instant retry. Runs as the user whose home holds the
database and who is in the `gpio` and `video` groups.

All tuning lives in `service.env`, which survives a reinstall and needs no root:
room names and pins, camera type, GPS port, scale pins and calibration factor,
feeder kind, servo pin, and the gate or paddle parameters.

**A cross-platform detail worth a footnote:** the installer strips carriage
returns, because the source is edited on Windows and copied with `scp`, which is
a byte-for-byte transfer. Without it, systemd reads `PETTRACKER_CAMERA` as
`usb\r` and the service fails complaining about a camera called `usb` — with the
offending character invisible everywhere you would look for it.

---

## 11. Measured results

Numbers produced by the real system. Use these rather than estimates.

| Measurement | Value | How obtained |
|---|---|---|
| Load cell calibration | **385.66 counts/g** | 614.4 g reference, two independent runs agreeing to 0.65% |
| Load cell zero stability | **−0.04 g** after tare | `/health` with servo and buck sharing ground |
| Load cell headroom | 5 kg ≈ 1.9 M counts | vs HX711's ±8.4 M range — no saturation |
| HX711 clock-high time | **3.7 µs** (lgpio) | Must stay under 60 µs; gpiozero failed this |
| GPS fix | **12 satellites**, 32.00964, 35.76567 | `--check-gps` |
| CSI camera | **20/20 frames, 18.4 fps** | `--check-camera` |
| Motion sensors | 3 of 3 firing, correct rooms | `--check-motion` |
| Route recorded on foot | Yes — over a phone hotspot, on battery | TrackActivity |
| Two cameras concurrently | USB + CSI, switchable in the app | /health, /stream |
| Live bowl weight | 2 s refresh while the screen is open | /health |
| Automated tests | **451** (274 + 73 + 104) | All passing |
| Codebase | 7,663 lines Java, 5,285 Python | 48 classes, 15 layouts |
| Development span | 2026-04-29 → 2026-09-06 | 77 commits |

**A calibration methodology note worth including.** The first calibration used a
phone of assumed weight; the second used a 600 ml water bottle. The second was
then corrected: the bottle's own 14.4 g of plastic had been included in the load
but excluded from the declared mass, making the factor 2.4% high. Measuring the
empty bottle on the newly calibrated scale recovered the error without
recalibrating. The general point: **any error in your knowledge of the reference
divides straight into the factor and then into every measurement forever**, which
is also why a heavier reference is better than a lighter one.

---

## 12. Rejected and deferred alternatives

Good thesis material — a design chapter is stronger for the roads not taken.

**MegaDetector v6 instead of EfficientDet-Lite2.** Investigated and rejected for
this deployment. It is a detector of animals-versus-everything and would need a
separate species classifier behind it; more importantly the Pi's runtime
environment (`onnxruntime` and `tflite_runtime` both unavailable, PEP 668
externally-managed environment) made it a fight rather than a feature. Stated
priority: *demonstrating that the application works matters more than making
everything expandable.*

**A camera in every room.** Deferred, not designed away. Two cameras on separate
buses — USB over the feeder, CSI over the hall — cost nothing in bandwidth,
whereas two USB webcams compete for it. A Pi 4 has one CSI connector, so a second
ribbon camera is not possible on one host.

**Cloud sync (Firestore).** Held for evaluation rather than rejected. The local
layer was deliberately built so this stays a clean addition: append-only rows,
explicit timestamps, explicit source, stable remote ids — the shape that syncs
and de-duplicates well. The counterweights are free-tier quota against detection
volume, security rules, and an offline/conflict story.

**Background polling.** Rejected with reasoning: it would require a foreground
service and its permanent notification, or WorkManager and its 15-minute floor —
real costs in battery and in the notification shade, buying nothing, because the
Pi buffers every reading and hands over whatever was missed the moment the app
is opened. *Nothing is lost by not listening, so nothing listens.* The note
records that push notifications are the point at which background sync would
earn its keep.

**An HTTP client library on the Pi, and on the phone.** Both use the platform's
own. Two GETs against a device on the same LAN do not justify a dependency stack
that must be kept current.

---

## 13. Limitations to state honestly

- **A PIR detects movement, not identity.** The system cannot distinguish
  between two animals, or between an animal and a person, from motion alone.
- **The GPS locates the tracker, not the pet.** Pet position within the home
  comes from motion sensors. Tracking an animal outdoors would need a collar tag.
- **Detections only accumulate while the Live screen is open**, because the
  detector runs on the phone. The UI says so.
- **The feeder has no feedback.** A hobby servo has no position sensor, so
  neither the Pi nor the app knows whether the wheel turned. The load cell is
  the only ground truth for what was actually dispensed.
- **Plain HTTP on the LAN**, no TLS and no authentication. Anyone on the network
  can read the endpoints and operate the feeder. Acceptable for a home LAN
  prototype; it is a real limitation and should be named as one.
- **Single-pet focus.** The data model supports many, but the sync attributes
  readings to one active pet.
- **`PETTRACKER_PADDLE_GRAMS` is still a placeholder** at the time of writing —
  grams-per-pocket must be measured with a full hopper, because the column of
  kibble above the wheel is what fills the pocket.

---

## 14. Do not claim

Guardrails for whoever writes the prose. Everything below is either untrue or
unverified, and a thesis that asserts it can be faulted on the evidence.

- Do **not** say the system identifies *which* pet was seen. It does not.
- Do **not** present the GPS coordinates as the animal's location.
- Do **not** describe the detection count as a daily total.
- Do **not** claim the feeder knows how much it dispensed.
- Do **not** cite dispense requests as consumption; only load-cell-derived meals
  are measurements.
- Do **not** claim battery, power-draw, latency or accuracy figures — none were
  measured. §11 is the complete list of measured values.
- Do **not** describe multi-camera-per-room as implemented. It is deferred.
- Do **not** claim cloud sync exists.
- Do **not** report the instrumented suite as always having passed on hardware;
  the honest and more interesting account is incident §9.5.

---

## 15. Suggested chapter mapping

| Chapter | Draw from |
|---|---|
| Introduction / problem | §1, §7 |
| Background / related work | §6 (detection), §12 (alternatives) |
| Requirements | §1, §7, §13 |
| System architecture | §3, §4, §5 |
| Implementation — sensor node | §2, §3, §6, §10 |
| Implementation — mobile client | §3, §5, §7 |
| Signal processing / interpretation | §6 |
| Testing & verification | §8, §9.4, §9.5 |
| Results | §11 |
| Discussion | §7, §9, §12 |
| Limitations & future work | §12, §13 |
| Appendices | §4 (wire format), §10 (configuration), §2 (pinout) |

---

## 16. Repository landmarks

For a code appendix or for anyone reading along:

- `pi/run.py` — entry point and every command-line flag
- `pi/pettracker/server.py` — the HTTP contract
- `pi/pettracker/store.py` — the event buffer and cursor
- `pi/pettracker/meals.py` — weight series → meals
- `pi/pettracker/hx711.py` — the bit-banged protocol
- `pi/pettracker/bringup.py` — the hardware diagnostics
- `pi/pettracker/sensors/gpio.py` — backend abstraction and selection
- `app/.../PetDataRepository.java` — the integration seam
- `app/.../PiEventSync.java` — idempotent reconciliation
- `app/.../AppDatabase.java` — schema and migrations
- `app/.../PiClient.java` — the client half of the contract
- `PUNCHLIST.md` — the full development history, decision by decision

`PUNCHLIST.md` is worth reading directly for the write-up. It records not just
what was done but why, including the arguments that were had with the code.
