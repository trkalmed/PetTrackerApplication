# Pet Tracker — Punch List

State: P0-P3 done. Sensor data layer built. Detector upgraded. Green theme applied.
The two halves are joined: the app polls the Pi, and motion sightings become rooms.
On real hardware: three PIRs, a USB camera, motion snapshots, and the live
detector running against the Pi stream. Open: the load cell, the servo, the CSI
camera stub, GPS, #11 cloud sync (held), #20, #26. 2026-08-28.

---

## P0 — ✅ ALL FIXED (2026-08-23)

Build now goes green: `:app:assembleDebug` → **BUILD SUCCESSFUL**, `app-debug.apk` produced
(135 MB — unminified, see #22/#26). Changes are **staged but not committed**.

### 1. ✅ `R.id.trackingToolbar` does not exist → build failed
`LiveObjectDetectionActivity.java:63`. `activity_live_object_detection.xml` was redesigned —
the opaque toolbar became a floating back pill `@+id/btnBack` (a `MaterialCardView`) plus a
`@+id/chipLive` badge — but the Java was never updated. Was the only compile error.

Fixed: bound `R.id.btnBack` instead, dropped the now-unused `MaterialToolbar` import.

```java
findViewById(R.id.btnBack).setOnClickListener(
        v -> getOnBackPressedDispatcher().onBackPressed());
```

### 2. ✅ Git index would have deleted 28 live files on the next commit
The index held **32 staged deletions, 28 of which still existed on disk as untracked files** —
`values/colors.xml`, `themes.xml`, `styles.xml`, `strings.xml`, `arrays.xml`,
`menu/menu_bottom_nav.xml`, both `color/*.xml`, and 22 drawables. Committing would have
produced a tree that could not build from a fresh clone.

Fixed with `git add -A`. Index and working tree now match exactly — verified:
37/37 drawables, 10/10 layouts, 18/18 Java files, all 5 `values/*.xml` tracked, and no stray
`res/layout/{color,drawable,values,menu}/` entries left.

The 4 remaining staged deletions are correct: `values/ids.xml`, `values/refs.xml`,
`values-night/themes.xml`, `menu/menu_main_menu.xml`.

Note: git rendered some of the moved drawables as renames (`R`) rather than add+delete —
that is just rename detection pairing similar SVG paths across the directory move. Content on
disk is what ships; harmless.

### 3. ✅ `local.properties` had a broken SDK path
Was `sdk.dir=...\Android\Sdkandroid` (typo — that directory does not exist), so Gradle could
not even configure. Now `sdk.dir=C\:/Users/Tareq Al-Medanat/AppData/Local/Android/Sdk`.
Gitignored, so local-only. Installed: platforms 31/35/36, build-tools 35.0.1.

---

## P1 — ✅ #4–#9 FIXED · #10 backend ready, awaiting hardware

Compiles and installs; app launches clean. See "Verification status" at the bottom for what
is and is not confirmed by hand.

### 4. ✅ The Food tab opened a screen that immediately closed itself
`MainMenuActivity` routed `nav_food` to `Petmenu` behind a stale
`// TODO Build a dedicated FeedingActivity` — stale because `FeedingActivity` already existed
and was already in the manifest. `Petmenu` got no `pet_id`, so it toasted "No pet selected"
and finished. Tapping Food did nothing.

Fixed: `nav_food` → `FeedingActivity`, TODO removed.

### 5. ✅ `FeedingActivity` was unreachable, and would have crashed once reached
Reachable now via #4 and via the pet detail screen's Feeding button (which was a
"coming soon" toast). Two further defects fixed while making it reachable:

- `load()` dereferenced `getCurrentUser().getUid()` with no null check — a guaranteed NPE
  for anyone signed out with the tab open. Now guarded, and the uid is read on the main
  thread before the worker starts rather than inside it.
- `nav_home` called bare `finish()`, which only lands on the dashboard when launched from
  it. Now an explicit Intent with `CLEAR_TOP | SINGLE_TOP`.

### 6. ✅ `Petmenu` was unreachable — nobody ever passed `pet_id`
`"pet_id"` was only ever read, never written. No pet-list screen, no tap target.

Fixed: avatar + name on the dashboard are now one tap target (`@id/rowPetHeader`) opening
`Petmenu` with the pet's id. `MainMenuActivity` holds the displayed pet in a `currentPet`
field; with no pets the same tap opens `AddPetActivity`, matching the "Tap + to add one" copy.

### 7. ✅ The Pi camera stream was unreachable from the UI
`LiveObjectDetectionActivity` picks its mode from the `stream_url` extra. Only `Petmenu` set
it, and `Petmenu` was unreachable (#6), so both Live tabs silently used the phone's camera.

Fixed: the choice now lives in one place — `PiConnectionManager.streamIntent(context)` —
which attaches the stream URL when a Pi is paired. Both nav handlers and `Petmenu` use it,
so the rule cannot drift apart again. `Petmenu`'s Tracking button keeps its prompt-for-IP
behaviour, since that button is explicitly about the Pi rather than a generic live view.

### 8. ✅ `Petmenu` wrote the wrong labels into the redesigned summary card
Java still set "Steps today" / "Distance today" over two TextViews the redesign had
repurposed for grams and room minutes — metrics no hardware produces and that the dashboard
redesign deliberately dropped.

Fixed: both rows read from `DashboardStats` and state why they are empty ("no feeder paired",
"no sensors reporting") rather than showing a bare "—". Ids renamed
`stepsText`/`distanceText` → `tvFoodToday`/`tvRoomsToday` so they describe what they hold.

### 9. ✅ Account and Settings had no back button
Both called `getSupportActionBar()` under a `NoActionBar` theme with no Toolbar in either
layout, so the null-guard silently swallowed the title and the up-arrow, and the
`onSupportNavigateUp()` override was dead code. System back was the only way out.

Fixed: added the `@id/btnBack` pill both screens' own design language already uses (it is the
same control as the Live screen's), wired to the back dispatcher; removed the dead
`getSupportActionBar()` blocks and `onSupportNavigateUp()` overrides. No Toolbar was added —
both screens already carry their title in a header card, so a toolbar would have duplicated it.

### ✅ Also fixed in passing: bottom nav lost sync with the screen
Not in the original list, but making the tabs actually navigate (#4, #7) exposed it: the
selected tab was only set in `onCreate`, so returning from Live left "Live" highlighted while
you were on Home. Both `MainMenuActivity` and `FeedingActivity` now re-assert their own tab in
`onResume`.
### 10. Dashboard data — ⚙️ BACKEND READY (2026-08-24) · awaiting Pi hardware

`DashboardStats.forPetToday(context, pet)` now reads real stored readings instead of
returning an empty object. The local data layer is built and tested; what is missing is the
hardware, not the app.

**Done — the backend the Pi work plugs into:**
- Room v3 with two new tables, `feeding_events` and `detection_events`, both keyed on the
  pet and indexed on `(petId, timestampMillis)`, both cascading on pet delete.
- A real `MIGRATION_2_3` — the first migration written against the schema baseline P2 set
  up. Existing pets survive it.
- `PetDataRepository` — **the integration seam**. Whatever transport ends up carrying the
  data (HTTP poll, socket, broker), it only has to call `recordFeeding(...)` or
  `recordDetection(...)`. Nothing above this class knows where a reading came from, so the
  transport can be written and swapped without touching a single screen.
- `DayRange` — "today" as a local-day window, so a meal at 23:30 belongs to the day the
  owner just had rather than to tomorrow in UTC. Handles daylight-saving days correctly.
- **Detections are live already.** The detector was computing sightings on every frame and
  throwing them away; `DetectionRecorder` now stores them, filtered to COCO's animal classes
  (a sofa is not a pet sighting) and throttled to one row per label per 30 s (at ~15 fps a
  row per frame would be tens of thousands of inserts an hour saying nothing extra).

**Feeder / load cell — ready to receive, nothing sending yet.**
`FeedingEvent` records what was *measured* rather than assuming how: `TYPE_EATEN` for a drop
in load-cell weight, `TYPE_DISPENSED` for what the feeder put in. Both are stored; only
EATEN drives the dashboard, since they are not the same number. Negative grams and unknown
types are rejected at the door rather than poisoning totals downstream.

When the Pi is ready, the whole integration is one call:

```java
new PetDataRepository(context).recordMealEaten(petId, grams, "feeder-01");
```

**Still genuinely open — no hardware, no data model yet:**
- **Motion sensors** → `rooms`, `currentRoom`. Deliberately not modelled: guessing at a
  presence/event shape before the sensors exist would mean rewriting it later.
- **GPS** → `lastGpsFixAgo`. No location permission, no provider, and a design question the
  schema cannot answer: the *phone's* location is not the *pet's*. This needs a collar or
  tag reporting in, which is a hardware decision.

Both stay empty on purpose, so those cards keep showing setup prompts rather than inventing
numbers.

---

## P2 — ✅ #12–#19 FIXED (2026-08-24) · #11 deferred · #20 not fixable as written

Verified by driving the app on the emulator, not just compiling — see "Verification status".

### 11. Pet data is local-only and silently device-bound — DEFERRED (your call)
Room DB `pets_db` keyed on the Firebase `uid`, nothing syncs. Same account on a new device
shows zero pets with no explanation. Deferred deliberately alongside #10: it needs Firestore
enabled in the console, security rules, and an offline/conflict story — and the hardware data
from #10 will need somewhere to live too, so the two are worth designing together rather than
bolting Firestore on twice.

### 12. ✅ `fallbackToDestructiveMigration()` wiped user data on any schema change
There was no exported schema, so no baseline existed to write a v1→v2 migration against.

Fixed: `exportSchema = true` with `room.schemaLocation`, so `app/schemas/…/2.json` is now
committed as the baseline. The blanket fallback became `fallbackToDestructiveMigrationFrom(1)`
— version 1 predates schema export and can only be wiped, but every version after this must
ship a real `Migration` or Room refuses to build. That is the point.

### 13. ✅ No way to delete or edit a pet
`PetDao.delete()` was declared and never called; there was no update query at all.

Fixed: added `@Update update()` and `deleteById(id, ownerId)` — delete is by id **and** owner
so a stale `Pet` object cannot remove someone else's row. Both actions are reachable twice
over: the per-row overflow in the pet list, and an Edit/Delete menu on the detail screen's
toolbar (hidden until a pet has actually loaded). Both routes confirm first.
`AddPetActivity` gained an edit mode via a `pet_id` extra — it prefills the form, keeps the
saved photo unless a new one is picked, preserves `lastSeen`, and updates rather than inserts.

### 14. ✅ Only the first pet was ever shown
`MainMenuActivity` and `FeedingActivity` both did `pets.get(0)`.

Fixed: the chosen pet is stored in `Prefs.KEY_ACTIVE_PET_ID` and the dashboard reads it.
The stored id goes stale when that pet is deleted or the account changes, so both the
dashboard and the list fall back to the first pet and rewrite the preference rather than
showing an empty dashboard next to a non-empty list.

### 15. ✅ Full-size bitmap decode on the main thread
`ImageView.setImageURI()` decodes synchronously on the UI thread. Replaced with Glide in
`MainMenuActivity` and `Petmenu` (as `PetsAdapter` already did). Both now also reset the
image when a pet has no photo — previously a stale photo could linger.

### 16. ✅ Dead code: `PetsAdapter` + `item_pet.xml`
Both were unreferenced — the pet-list screen that was never wired up. Now live behind the new
`PetListActivity`. The adapter also gained the species chip it never bound, a "Tracking"
marker for the active pet, and `DiffUtil` in place of `notifyDataSetChanged()`.
The row's chevron became an overflow button: tapping a row now *selects* the pet rather than
navigating, so a right-arrow promised the wrong thing. `ic_menu_24` was a hamburger (drawer),
so a proper `ic_more_vert_24` was added in the same Lucide style.

### 17. ✅ Orphaned `res/xml/pref_general.xml`
Never inflated — `SettingsActivity` is a hand-built card screen, not a `PreferenceFragment`.
Deleted, along with the `androidx.preference` dependency it was the only consumer of.

### 18. ✅ Two independent SharedPreferences files
`PiConnectionManager` wrote to `pet_tracker_prefs`, `SettingsActivity` to `app_settings`.
Consolidated behind a new `Prefs` class holding the file name and every key. Values in the
retired file are carried over once on first read, so the change is invisible on upgrade.

### 19. ✅ Unhandled activity-lifecycle races in the DB threads
Every raw `new Thread` + `runOnUiThread` could touch a destroyed activity. All four load paths
now read the Firebase uid on the main thread *before* starting the worker (which also removed
the `getCurrentUser()` null-deref in `FeedingActivity`) and guard the UI callback with
`isFinishing() || isDestroyed()`.

### 20. Cleartext HTTP enabled app-wide — NOT FIXABLE AS I ORIGINALLY WROTE IT
My earlier advice here was wrong and I did not ship it. A `network-security-config` cannot
scope this: `<domain>` accepts hostnames and exact IP literals only — **not CIDR ranges**. The
Pi's address is typed in by the user at runtime, so it cannot be known at build time. A config
with `192.168.0.0/16` entries under a `cleartextTrafficPermitted="false"` base-config would
never match, and would break the Pi stream while looking secure.

`android:usesCleartextTraffic="true"` therefore stays. The realistic mitigations, if this
matters later: pin the exact IP into a generated config once the user saves it, move the Pi
to HTTPS with a self-signed cert plus a `<trust-anchors>` entry, or accept it — everything
else in the app (Firebase, ML Kit) is HTTPS regardless.

---
## P3 — ✅ #21–#25, #27–#32 FIXED (2026-08-24) · #26 partly, applicationId left alone

**Debug APK for the emulator went from 60 MB to 22 MB; the release APK, now shrunk, is
35 MB for all four ABIs against 131 MB unminified.**

### 21. ✅ Unused dependencies removed
Dropped `com.google.mlkit:object-detection`, `image-labeling`, `face-detection`,
`androidx.media3:media3-exoplayer` and `media3-ui` — no imports anywhere. Object detection
is TFLite's job here, not ML Kit's; only subject segmentation is actually used.

`androidx.cardview` was **kept**: contrary to this list's original claim, `pet_menu.xml`
uses `androidx.cardview.widget.CardView` directly. `recyclerview` and `glide` were also
kept — no longer dead, they back the pet list added in P2.

The manifest's ML Kit meta-data over-declared
`object_detection_custom,subject_segment,face,model3`; now just `subject_segment`, so Play
Services stops downloading models the app never loads.

### 22. ✅ Unused 4.5 MB asset deleted
`assets/mobile_object_localizer.tflite` was referenced by nothing — only
`coco_ssd_mobilenet_v1.tflite` is loaded.

### 23. ✅ CameraX version skew fixed
`camera-view` was pinned to 1.2.3 against 1.4.0 siblings. All four now on 1.4.0.

### 24. ✅ Version catalog no longer contradicts the build
`libs.versions.toml` declared a parallel, unresolved set of versions disagreeing with
`app/build.gradle.kts` (ML Kit 17.0.2 vs 17.0.1, espresso 3.7.0 vs 3.5.1, ...). Trimmed to
the one thing it actually provides: the AGP plugin alias.

### 25. ✅ Kotlin plugin removed
`kotlin("android") version "2.0.21" apply false` in a pure-Java project.

### 26. Partly fixed — **`applicationId` deliberately left alone**
- ✅ **Shrinking enabled.** `isMinifyEnabled` and `isShrinkResources` are on, with keep
  rules in `proguard-rules.pro` for everything that resolves reflectively or via JNI:
  TFLite task-vision, ML Kit, Room's generated DAO and the `Pet` entity, Glide, Firebase,
  and `GraphicOverlay` (inflated by class name from XML). Line numbers are kept so release
  crash reports stay readable.
- ❌ **`applicationId` is still `com.example.pettrackerapplication`** — changing it is
  **your call, not a mechanical fix**. `app/google-services.json` pins
  `package_name: com.example.pettrackerapplication`; renaming the app without registering
  the new package in the Firebase console breaks sign-in at runtime. Do both together.
- `versionCode 1 / versionName "1.0"` left as-is — correct for something unreleased.
- `google-services.json` stays committed. It is client config rather than a secret, but it
  does publish the project id; a deliberate decision either way.

**The release build has only been verified at build time.** R8 succeeded and the mapping
file confirms `ObjectDetector`, `SubjectSegmentation`, `GraphicOverlay`, `Pet` and
`RoomDatabase` all survived — but the release APK is unsigned, so it could not be installed
and smoke-tested. Do that before shipping: a wrong keep rule fails at runtime, not build time.

### 27. ✅ Every user-facing string extracted
Was ~150 hardcoded strings across layouts and Java against a 23-entry `strings.xml`; the
Settings screen offered a Language row that could never work. Now **157 string resources
and one plurals set**, with **zero hardcoded user-facing text left in any layout**.

Distinctions the extraction was careful about:
- Text a screen sets at runtime became `tools:text`, not a `@string` — design-time only, so
  a placeholder can never leak into the running app.
- Interpolated strings became **format strings** (`Now tracking %1$s`, `%1$d g of %2$d g`,
  `since %1$s`) rather than concatenation, so word order stays translatable.
- Meal counts use a **plurals** resource instead of the hand-rolled `"1 meal" : n + " meals"`.
- `Log.*` was left alone throughout — developer-facing, must not be translated.

**This introduced a regression, caught by re-running the app and now fixed.** Moving
runtime-set fields to `tools:text` removed the layout defaults that were the *only* thing
filling the dashboard's device-status row and glance cards in the no-pets state — they
rendered blank. `showNoPets()` now calls `render(new DashboardStats())` so those fields go
through the same code path as everywhere else, and `FeedingActivity` sets its subtitle on
the empty branch too. Both re-verified on the emulator.

### 28. ✅ Placeholder copy no longer reachable at runtime
`"Kitchen"` was hardcoded over every stream; the live screen now says which feed it is
("Pi camera" / "Phone camera") and keeps `Kitchen` as `tools:text` only. The `"Marvel"`
placeholders in `pet_menu.xml`, `activity_main_menu.xml` and `item_pet.xml` are all
design-time now.

### 29. ✅ Deprecated APIs replaced
- `Intent.ACTION_PICK` → `ActivityResultContracts.PickVisualMedia`. This **removed
  `READ_MEDIA_IMAGES` and `READ_EXTERNAL_STORAGE` from the manifest entirely**, along with
  the whole runtime-permission path — the system picker runs out of process and grants
  access to just the chosen image.
- `setTargetResolution()` → `ResolutionSelector` with an explicit fallback rule.
- `onRequestPermissionsResult()` → `registerForActivityResult`.
- `getResources().getFont()` → `ResourcesCompat.getFont()`.

**Latent bug fixed alongside:** a photo that failed segmentation had its *picker* URI stored
in the database. That grant is scoped to the task, so those photos would silently stop
loading later. Picked images are now copied into app storage, as segmented ones already were.

### 30. ✅ Manifest warning gone
`tools:replace="android:value"` claimed a conflict that did not exist and warned on every
build. Removed; builds are now warning-free.

### 31. ✅ Unused drawables deleted
`bg_pill_outline`, `circle_background`, `ic_bell_24`, `ic_language_24`, `ic_menu_24`,
`ic_star_24`, `ic_storage_24`, `photo_placeholder_bg`. A re-scan reports zero unused
drawables.

### 32. ✅ Tests added — 13, all passing
Replacing the two untouched Android Studio templates:
- **`MjpegInputStreamTest`** (7) — the byte-marker logic behind the Pi stream, where an
  off-by-one yields corrupt frames rather than an exception. Covers marker retention,
  multipart preamble skipping, consecutive frames, end-of-stream, a connection cut
  mid-frame, no frame at all, and the 64 KB buffer-growth path.
- **`DashboardStatsTest`** (6) — pins the "not reporting" vs "reported zero" distinction the
  whole empty-state design rests on, including a guard that `forPetToday` has not quietly
  started inventing numbers. When #10 lands, that test should be *replaced*, not deleted.

---
## Theme — ✅ APPLIED (2026-08-26)

The green theme patch from `Mobile app design feedbackcolortheme.zip`
(`android-theme-patch/`), applied in full plus the layout work the README's
section 3 calls for.

**Note on the README:** its "no layout XML has to change" line is scoped to the
*recolor only*. Section 3 is titled "the only layout edits" — the green header
panel is `bg_header_gradient` on a container, not a colour token, so it needs
layout changes. Section 7 of that README is stale: all four "still-open code
fixes" it lists were already done here.

### Applied
- **Recolor** — forest green / deep teal, 35 tokens, every existing name kept.
  `values-night/` gives 33 of them a dark value. `Theme.Material3.DayNight`.
- **Typography** — Bricolage Grotesque (display) + Manrope (body).
- **Gradients** — page wash on Login / Register / Add pet; primary button style;
  `progress_ramp` on the feeding bar and the volume seekbar.
- **Header panels** — dashboard, pet detail, Account and Settings all carry the
  full-width gradient panel with rounded bottom corners. Account and Settings
  dropped their header cards entirely; pet detail keeps its photo with the
  panel behind it.
- **RampRing** — custom view for the food ring. `CircularProgressIndicator`
  paints one colour; the design sweeps the four ramp hues, so this draws an arc
  with a `SweepGradient`. Reads `ramp_1..ramp_4`, so the night ramp applies
  free.
- **Room hues + per-tab nav accent** — `NavTint` rebuilds the state list per
  selection, because a selector knows *something* is checked, not *which*.
- **Theme picker** — Light / Dark / AMOLED, with an Appearance card in Settings.
  `ThemeManager` persists, `ThemedActivity` applies the AMOLED overlay before
  inflation, `PetTrackerApp` applies the stored theme before any activity
  starts (otherwise cold start flashes light-then-dark).

### Fixed along the way
- The build was broken on arrival: fonts had been added under their download
  names, and Android resource filenames take only lowercase, digits and
  underscore.
- The patch's font XMLs expect four static TTFs that do not exist; both
  families were rewritten against the two **variable** fonts using
  `fontVariationSettings` (needs API 26; minSdk is 26).
- Three labels rendered blank at rest — `btnLogin`, `btnRegister`,
  `tvAddPetTitle`. The P3 string extraction had moved `android:text` to
  `tools:text` for any view something calls `setText` on, but those calls are
  *conditional*. The other 31 were audited and are set unconditionally.
- A pet with no photo showed a giant paw: `centerCrop` on a 24dp fallback
  vector in a 250dp header.

### Not done
The **meal chips** under the food ring and the **room bar with its legend** are
the only gaps versus the mockup, and both are data rather than styling — they
render as soon as the feeder and motion sensors report.

---

## Pi ↔ app — ✅ JOINED (2026-08-28)

The gap the last handoff called "the remaining software gap between the two
halves". The app now polls `GET /events`, and PIR sightings became the room
chart the dashboard has always drawn but never had data for.

### App side — the poller

`PiClient` → `PiEventSync` → `PetDataRepository`, with `PiSync` running it for a
screen. Six new classes, split so the judgement is testable without a network
and the transport is testable without a database.

Four decisions worth knowing:

- **Re-reading is free.** Every stored reading now carries `remoteId`, the id it
  had on the Pi, under a unique index on (petId, source, remoteId). Room's insert
  is `OnConflictStrategy.IGNORE`, so a replay is a no-op. This is load-bearing:
  without it, "recover from a rewound cursor" and "double today's meals" would be
  the same operation.
- **The cursor is checked in both directions.** It is a row id in one particular
  Pi's database, so it is reset when the saved host changes *and* when
  `/health.latest_cursor` is lower than it — which means that Pi's log was pruned
  or rebuilt. Without the second check a phone silently stops receiving anything
  and looks exactly like a quiet house.
- **Foreground only, deliberately.** Background polling needs a foreground
  service and its permanent notification, or WorkManager and its 15-minute floor.
  Neither buys anything: the Pi buffers, so nothing is lost by not listening.
  When push notifications ("Biscuit has not eaten today") are wanted, that is
  when background sync earns its keep.
- **A sync ends.** 20 pages per run, 500 events a page. A month of unsynced Pi
  does not stall the screen that asked.

**Bug found and fixed while wiring it:** `FeedingActivity` rendered `pets.get(0)`
while the poller filed readings against the *active* pet — different pets as soon
as an owner has two. Both screens now go through `Prefs.chooseActive`.

### Motion → rooms

Room v4: a `room_events` table, and `remoteId` added to `feeding_events`.
`MIGRATION_3_4` is real — existing meals survive the added column, and the
migration test asserts it against the exported `4.json`.

The interesting part is not storage but interpretation, and it lives in
`PetDataRepository.readRooms` where it can be changed without the history being
wrong. A PIR reports *movement*, never presence: it fires as the cat crosses the
kitchen and says nothing while the cat sleeps in it. So each sighting speaks for
the time until the next one anywhere, capped at 15 minutes — well past the Pi's
30 s debounce, short enough that going quiet reads as "we no longer know".

That cap is why the dashboard now distinguishes three states rather than two:
no sensors paired, sensors quiet today, and "no motion since 09:12" in place of
claiming the pet is still where it was last seen.

### Pi side — the PIR sensors

`GpioMotionSensor` had a body but not a working one:

- **RPi.GPIO cannot drive a Pi 5.** It imports fine and fails at `setup()`, so
  every PIR would have reported unavailable with no clue why. `sensors/gpio.py`
  now tries gpiozero, then RPi.GPIO, then lgpio, and names the winner in the log.
- **The 60 s warm-up was documented but not implemented.** A settling HC-SR501
  fires more or less constantly; all of it would have been stored as motion.
  Discarded now, not queued — and `--pir-warmup 0` exists because the warm-up
  belongs to the sensor powering up, not to the process restarting.
- **Pins were not configurable.** `--pir-pins` now pairs with `--rooms`, and
  mismatched lengths or repeated pins are refused at startup rather than
  silently dropping a room, as `zip()` did.
- **`run.py --check-motion`** is the bring-up tool: every sensor's state live,
  one line each, plus a summary naming the likely cause for anything that never
  fired. `/health` only says whether a library claimed the pin; this says whether
  the sensor labelled "Kitchen" is the one that fires when you wave at the
  kitchen.

## Verification status

**Driven on the emulator (API 35), signed in, crash buffer empty at every point:**

| Flow | Result |
|---|---|
| Add pet → dashboard | saves, dashboard shows the new pet |
| Dashboard header → pet list (#6, #14) | opens `PetListActivity`, row + species chip + "Tracking" marker render |
| Row overflow → Details / Edit / Delete (#13) | all three present and working |
| Edit → save (#13) | title reads "Edit &lt;name&gt;", form prefills, button reads "Save changes", **updates in place — one row, not a duplicate** |
| Tap a different pet (#14) | returns to dashboard, which now shows that pet; selection persists |
| Delete with confirm (#13) | confirmation dialog, row disappears |
| Delete the **active** pet (#14) | list and dashboard both fall back to the empty state, no ghost, no crash |
| Food tab (#4) | opens `FeedingActivity` — not the old "No pet selected" toast |
| Pet detail rows (#8) | "Food today: no feeder paired" / "Rooms visited today: no sensors reporting" |
| Account back button (#9) | returns to the dashboard |
| Settings back button (#9) | present and working |
| Dashboard / Food / Settings copy after the string extraction (#27) | every label renders, apostrophes escape correctly ("TODAY'S MEALS"), no blank fields |

**On real hardware (2026-08-28):**

| Check | Result |
|---|---|
| Three HC-SR501 PIRs on GPIO 17 / 27 / 22 | all three fire, `--check-motion` counts one trigger per wave |
| GPIO backend on the Pi | **gpiozero** claimed every pin; RPi.GPIO was never needed |
| Room-to-pin mapping | each sensor lights up the room it is standing in |
| `Monitor` debounce, live | one event per room per 30 s across a real walk-about |
| Events survive a service restart | the SQLite buffer replayed everything after a `pkill` |
| App polling the real `pi/run.py` | events collected, room chart drawn, Settings shows "synced HH:mm" |
| "Currently in" against stale motion | correctly showed "no motion since 15:23" rather than claiming a room |
| USB webcam via `--camera usb` | `/health` reports it, `/stream` serves video |
| Motion snapshots | a JPEG per motion event, served from `/snapshot?name=…` |
| **#7, the Pi stream, in the app** | live view connects and the TFLite detector runs against the Pi feed |
| Motion snapshots in the app | the frame the camera grabbed appears on the room card, captioned |
| "Camera live" from `/health` | correct while nobody is on the Live screen, which the old detector-based rule never was |
| Running as a systemd service | survives a reboot and a closed terminal, camera and sensors up with nobody logged in |
| The HX711 | **dead board.** Reversed VCC/GND killed it; the driver, the calibration tool and the timing probe are written and tested, waiting on a replacement |

That last row is the whole dwell rule working in the field: an hour-old sighting
fills the room bar but does not claim the pet is still there.

**Automated:**
- **108 app tests, all passing** on an API 35 emulator (2026-08-28): 52 unit
  (`DayRangeTest`, `MjpegInputStreamTest`, `DashboardStatsTest`, `BoxMapperTest`,
  `PiEventTest`, `PiClientTest`) and 56 instrumented (`MigrationTest`,
  `PetDataRepositoryTest`, `PiEventSyncTest`, `RoomOccupancyTest`,
  `PiSyncIntegrationTest`).
- **67 Pi tests, all passing**, no hardware and no network: `python3 -m unittest
  discover -s tests` in `pi/`.
- `PiClientTest` drives the client against a real socket serving the documented
  JSON, including a 500, an HTML page (someone typing their router's address),
  and a connection cut mid-reply.
- `PiSyncIntegrationTest` runs the whole chain on the device — saved address to
  URL, HTTP over a socket, cursor through SharedPreferences, work off the main
  thread and the answer back on it — because the seams are where this fails and
  none of the unit tests cross them.
- `RoomOccupancyTest` pins the dwell rule, which is a judgement rather than a
  fact: what a sighting is worth, when a visit starts, and that silence makes the
  pet's room unknown rather than unchanged.
- `MigrationTest` drives `MIGRATION_2_3` through real SQLite and validates the result
  against the exported schema JSON, so a typo in the CREATE statements fails there rather
  than on a phone after the pets are already gone. It also asserts existing pets survive.
- `PetDataRepositoryTest` covers the Pi integration seam end to end against a real database:
  grams summed per local day, yesterday excluded, DISPENSED not counted as EATEN, negative
  and unknown readings rejected, camera liveness window, cascade delete, and that rooms/GPS
  stay empty.
- Every `findViewById` id resolves against the layout its activity inflates — 10 activities.
- Every `ConstraintLayout` constraint target resolves within its own layout.
- Zero unused drawables; zero hardcoded user-facing strings in layouts.
- Builds are warning-free.

**Still not verified:**
- **The release build.** R8 succeeds and the mapping file shows the reflective classes were
  kept, but the release APK is unsigned so it could not be installed. A wrong keep rule
  fails at runtime, not build time — smoke-test a signed release before shipping.
- **#15**, that Glide actually removed the main-thread stall — correct by construction, but
  no frame profiling was done.

**Emulator note:** a full debug APK carries native libs for four ABIs and will
fail to install on a stock AVD with `INSTALL_FAILED_INSUFFICIENT_STORAGE`. Pass
the ABI to `connectedAndroidTest` as well as to `assembleDebug`:

```bash
./gradlew :app:connectedDebugAndroidTest -Pandroid.injected.build.abi=x86_64   -Pandroid.testInstrumentationRunnerArguments.class=com.example.pettrackerapplication.PiSyncIntegrationTest
```

`connectedAndroidTest` does not accept `--tests`; the runner-argument above is
how to select a class. To build the APK emulator-only:

```bash
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=x86_64
```

Output lands in `app/build/intermediates/apk/debug/` and is marked test-only, so install it
with `adb install -r -t`.

---
### Tools worth knowing about

**`LayoutRenderTest`** (androidTest) renders any layout straight to PNG, light
or dark, **with no sign-in**. Every interesting screen sits behind Firebase
auth, which makes the theme impossible to check on a device without an account.
It inflates inside `SettingsActivity` (the one screen with no auth guard, whose
`LayoutInflater` carries a properly themed activity context — a
`ContextThemeWrapper` over the application context is *not* enough,
`CircularProgressIndicator` refuses to inflate from one) and fills in
representative values, because `tools:text` is design-time only.

Gradle's `connectedAndroidTest` uninstalls the app afterwards, taking the PNGs
with it. To keep them:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -Pandroid.injected.build.abi=x86_64
adb install -r -t app/build/intermediates/apk/debug/app-debug.apk
adb install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.example.pettrackerapplication.LayoutRenderTest \
  com.example.pettrackerapplication.test/androidx.test.runner.AndroidJUnitRunner
adb pull /sdcard/Android/data/com.example.pettrackerapplication/files/dashboard_light.png
```

**Two static checks** that have each caught a real bug, worth re-running after
any layout edit: every `findViewById` id resolves against a layout the file
actually inflates, and every `ConstraintLayout` target resolves within its own
layout. The first is what would have caught the original P0 crash.

**The Pi service** runs with no hardware: `python3 pi/run.py --fake`, then put
the printed IP into the app's Pi Connection dialog.

---

## Next session — 2026-08-30

The rig moves to **the hall, next to the kitchen**, which is where the feeder
and the load cell will be wired. Signal should be fine there: a router in the
living room, and an amplifier in the bedroom whose door onto the hall stays
open. Motion sensor placements to be decided on the day.

### 1. The load cell, with the replacement HX711

The last amplifier died from reversed VCC/GND — no protection on these boards,
and the failure is silent: the module looks fine, the cell is unharmed, and the
data line simply never drives again. **Check the polarity twice before applying
power.** VCC to 3.3V.

```bash
sudo systemctl stop pettracker
python3 run.py --check-scale          # clock speed, then moving counts
python3 run.py --calibrate-scale      # known weight -> factor
sudo systemctl start pettracker
```

`--check-scale` now leads with how long this Pi holds the clock high (must be
under 60 µs; lgpio gave 3.7), tells the three silences apart in words, and tries
the pins the other way round if nothing reads.

Put the factor in `~/pettracker/service.env` as `PETTRACKER_SCALE_FACTOR` and
**send me the number** — its magnitude says whether the meal detector's
thresholds suit this cell: 5 g minimum change, 4 s to settle, 30 s before the
baseline follows drift.

### 2. The paddle wheel

Servo proven working on GPIO 18 via lgpio. Still no separate 5V supply, so keep
nothing attached to the horn until there is one — a USB charger or power bank
with a cut cable is enough, ground tied to a Pi ground.

```bash
python3 run.py --check-servo                       # park and dump angles
python3 run.py --feeder paddle --paddle-park 30 --paddle-dump 150 --paddle-grams 4.2
```

`--paddle-grams` is measured by cycling ten times into a bowl and dividing.

**A gap to close first:** the systemd unit knows nothing about `--feeder` or the
paddle settings, so the service would run the gate design regardless of what is
passed on the command line. Needs `PETTRACKER_FEEDER`, `PETTRACKER_PADDLE_PARK`,
`PETTRACKER_PADDLE_DUMP` and `PETTRACKER_PADDLE_GRAMS` adding to
`pettracker.service` and its ExecStart. Ten minutes, and worth doing before
anything is measured.

### 3. Once the sensors are placed

Room names flow straight through to the app's chart, so they want to be the real
ones. In `service.env`:

```
PETTRACKER_ROOMS=<real names>
PETTRACKER_PIR_PINS=<pins in the same order>
```

Then `sudo systemctl restart pettracker`, and check the Wi-Fi signal where it
ends up — `iw dev wlan0 link | grep -i signal`. Past -75 dBm the MJPEG stream
starts to stutter; the events and snapshots are small enough not to care.

### 4. If the GNSS antenna arrives

`--check-gps` on the balcony. The module and wiring are already proven; it needs
an active 1575.42 MHz antenna, SMA or IPEX. Watch the green PPS LED — it blinks
once a second on a fix.

## What is left

1. **The load cell (HX711).** The last empty card on the dashboard, and the
   app's headline feature: the food ring, the meal chips and the whole Food tab
   have nothing to draw. `HX711Scale.read_grams` and `tare` are stubs, and the
   calibration factor can only be found with a known weight on the real bowl.
2. **The servo**, on its own 5 V supply. `ServoFeeder.dispense` is a stub, and
   `POST /feed` has no button in the app to call it.
3. **The CSI camera.** `PiCamera.read_jpeg` is a stub. The USB path works, so
   this only matters for a second camera or a neater physical build.
4. **GPS** → `lastGpsFixAgo`. Needs a collar or tag; the Pi's own location is not
   the pet's. Still deliberately empty.
4. **#11 — cloud sync. Held for you to evaluate**, not rejected. Worth knowing
   when you come back to it: the local layer was built so this stays a clean
   addition. Readings are append-only rows with their own timestamps, an explicit
   source, and now a stable remote id — which is exactly the shape that syncs and
   de-duplicates well. The counterweights are Firestore's free-tier quota against
   detection volume, rules to write, and an offline/conflict story.
5. **#26 — `applicationId`**, renamed together with registering the new package
   in Firebase.
6. **#20 — cleartext HTTP** is not fixable as originally written; see the P2 note.
   Now slightly more relevant: the poller talks plain HTTP to the Pi as well as
   the stream does.
7. **Smoke-test a signed release build**, since shrinking is on and only
   build-time verified. `PiClient`, `PiEvent` and `RoomEvent` are ordinary
   classes with no reflection, so no new keep rules were needed — but that is an
   argument, not a test.
8. **`POST /feed` has no button.** The Pi will dispense on request and nothing in
   the app asks it to. A "Feed now" control on the Food tab is the obvious next
   feature, and the client method is ~15 lines.

### Housekeeping worth doing eventually
`feeding_events` and `detection_events` grow without bound. `deleteOlderThan(...)` exists on
both DAOs but nothing calls it — wire it to a retention window once there is real data volume
to reason about.
