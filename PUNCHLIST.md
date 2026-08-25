# Pet Tracker — Punch List

State: P0-P3 done; #10 backend built and tested, awaiting Pi hardware. 2026-08-24.
Open: Pi hardware (feeder/load cell), motion sensors, GPS, #11 cloud sync (held),
#20, #26. See "Verification status" for what is proven versus assumed.

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

**Automated:**
- **39 tests, all passing.** 21 unit (`DayRangeTest`, `MjpegInputStreamTest`,
  `DashboardStatsTest`) and 18 instrumented on an API 35 emulator (`MigrationTest`,
  `PetDataRepositoryTest`).
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
- **#7, the Pi stream.** Needs a real Raspberry Pi serving MJPEG on the LAN. The
  intent-building path is shared and exercised, but nothing has connected to a live stream.
- **The release build.** R8 succeeds and the mapping file shows the reflective classes were
  kept, but the release APK is unsigned so it could not be installed. A wrong keep rule
  fails at runtime, not build time — smoke-test a signed release before shipping.
- **#15**, that Glide actually removed the main-thread stall — correct by construction, but
  no frame profiling was done.

**Emulator build note:** a full debug APK carries native libs for four ABIs. Build
emulator-only to cut it to 22 MB:

```bash
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=x86_64
```

Output lands in `app/build/intermediates/apk/debug/` and is marked test-only, so install it
with `adb install -r -t`.

---
## What is left

1. **The Pi side.** The service now exists in `pi/` — HTTP contract, event buffering, meal
   detection and MJPEG streaming, all built and tested against simulated sensors. Run
   `python3 pi/run.py --fake` and point the app at it. What is left there is hardware:
   wiring each sensor and filling in the stubs in `pi/pettracker/sensors/real.py`, one at a
   time, watching `/health`.
2. **The app-side poller.** Nothing in the Android app calls `GET /events` yet. That is the
   remaining software gap between the two halves: poll the Pi, walk the cursor, and feed
   each event into `PetDataRepository.recordFeeding(...)`. See `pi/README.md` for the
   contract.
3. **Motion sensors** → `rooms` / `currentRoom`. The Pi emits `motion` events with a room
   name already; the Android side still has no table or aggregation for them.
4. **GPS** → `lastGpsFixAgo`. Needs a collar or tag; the Pi's own location is not the pet's.
5. **#11 — cloud sync. Held for you to evaluate**, not rejected. Worth knowing when you come
   back to it: the local layer was built so this stays a clean addition. Readings are
   append-only rows with their own timestamps and an explicit source field, which is the
   shape that syncs and de-duplicates well. The counterweights are Firestore's free-tier
   read/write quota against per-frame-ish detection volume (the 30 s throttle helps), rules
   to write, and an offline/conflict story. Nothing done so far forecloses either choice.
6. **#26 — `applicationId`**, renamed together with registering the new package in Firebase.
7. **#20 — cleartext HTTP** is not fixable as originally written; see the P2 note.
8. **Smoke-test a signed release build**, since shrinking is on and only build-time verified.

### Housekeeping worth doing eventually
`feeding_events` and `detection_events` grow without bound. `deleteOlderThan(...)` exists on
both DAOs but nothing calls it — wire it to a retention window once there is real data volume
to reason about.
