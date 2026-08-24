# Pet Tracker — Punch List

State: P0, P1 (#4–#9) and P2 (#12–#19) fixed on 2026-08-24. Branch `master`.
Open: #10 (real data), #11 (cloud sync, deferred), #20 (not fixable as written), and P3.
See "Verification status" near the end for what was driven on a device versus only compiled.

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

## P1 — ✅ #4–#9 FIXED (2026-08-24) · #10 still open

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

### 10. The entire dashboard is permanently in the "no data" state — STILL OPEN
`DashboardStats.forPetToday(pet)` returns `new DashboardStats()` — every field null/empty
(`DashboardStats.java:60`). Deliberately left alone: this is feature work needing hardware
that does not exist yet, and inventing numbers would be worse than the honest empty state.
Its own TODO is the real backlog:

- **Feeder** → `gramsEaten`, `meals` — no endpoint exists yet.
- **Motion sensors** → `rooms`, `currentRoom` — no table, no transport.
- **Pi camera** → `cameraOnline`, `detectionsToday`, `lastDetectionTime` —
  `PiConnectionManager` only stores an IP; nothing pings the Pi or persists detections.
- **GPS** → `lastGpsFixAgo` — no location permission in the manifest, no provider, nothing.

Until this lands, the dashboard, the Food tab and the pet detail summary all render setup
prompts. That is now the *only* thing standing between the app and a working flow — every
screen is reachable, they are just empty.


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

## P3 — Build hygiene, size, polish

### 21. Unused dependencies (verified by import grep — none are referenced)
- `com.google.mlkit:object-detection` — detection is TFLite/COCO, not ML Kit
- `com.google.mlkit:image-labeling`
- `com.google.mlkit:face-detection`
- `androidx.media3:media3-exoplayer` + `media3-ui`
- `androidx.preference` (see #17)
- `recyclerview` + `glide` — only used by dead `PetsAdapter` (#16)

The manifest's ML Kit meta-data also over-declares:
`object_detection_custom,subject_segment,face,model3` — only `subject_segment` is used
(`AddPetActivity`). Trimming these forces smaller model downloads at install.

### 22. Unused 4.5 MB asset
`assets/mobile_object_localizer.tflite` is never referenced — only
`coco_ssd_mobilenet_v1.tflite` is loaded (`CocoObjectDetector.java:16`). Deleting it halves
the assets payload.

### 23. CameraX version skew
`camera-view` is pinned to **1.2.3** while `camera-core`, `camera-camera2` and
`camera-lifecycle` are on **1.4.0**. Align them all on 1.4.0.

### 24. The version catalog is unused and contradicts `build.gradle.kts`
`gradle/libs.versions.toml` declares `material`, `activity`, `constraintlayout`,
`object-detection` (17.0.2), `litert-support-api`, `ext-junit` (1.3.0), `espresso-core` (3.7.0)
— and `app/build.gradle.kts` hardcodes *different* versions for the same libraries
(e.g. ML Kit object-detection 17.0.1, espresso 3.5.1, ext-junit 1.1.5). Only
`libs.plugins.android.application` is actually used. Either move everything into the catalog
or strip it down to the plugin.

### 25. Kotlin plugin applied with zero Kotlin source
`build.gradle.kts:4` — `kotlin("android") version "2.0.21" apply false`. Pure Java project.
Remove it.

### 26. Not release-ready
- `applicationId = "com.example.pettrackerapplication"` — placeholder namespace
- `versionCode 1 / versionName "1.0"` — never bumped
- `isMinifyEnabled = false` and `proguard-rules.pro` untouched → no shrinking, no
  obfuscation, full ML Kit + TFLite symbols in the APK
- `app/google-services.json` is committed to git. Not a hard secret (it's client config), but
  it does pin `project_id: pettrackerapplicatio-65e32a98` publicly. Decide deliberately.

### 27. Every user-facing string is hardcoded in layouts and Java
`strings.xml` has ~25 entries, but the layouts hardcode nearly all their copy and
`MainMenuActivity` / `FeedingActivity` / `Petmenu` build display strings inline
("Camera offline", "no feeder", "since ", " g"). The manifest sets `supportsRtl="true"` and
the Settings screen offers a **Language** row — neither can work. Also blocks plurals
(`FeedingActivity.java:88` does `"1 meal"` / `"n meals"` by hand).

### 28. Leftover placeholder copy visible at runtime
- `activity_live_object_detection.xml:113` — camera name hardcoded to `"Kitchen"`,
  never set from code, shown over every stream
- `activity_main_menu.xml:78`, `pet_menu.xml:75`, `item_pet.xml:50` — `"Marvel"` as the pet
  name; only the first two get overwritten, and `item_pet` is dead anyway
- `activity_main_menu.xml:363` — `"Right now"`

Design-time text belongs in `tools:text`, not `android:text`, so it can never leak.

### 29. Deprecated APIs
- `AddPetActivity.java:156` — `Intent.ACTION_PICK`. Migrating to
  `ActivityResultContracts.PickVisualMedia` lets you **drop `READ_MEDIA_IMAGES` and
  `READ_EXTERNAL_STORAGE` from the manifest entirely** and removes the whole
  `permissionLauncher` path.
- `LiveObjectDetectionActivity.java:197` — `setTargetResolution()` → `ResolutionSelector`
- `LiveObjectDetectionActivity.java:246` — `onRequestPermissionsResult()` →
  `registerForActivityResult`, as `AddPetActivity` already does
- `getResources().getFont()` (`MainMenuActivity:283`, `FeedingActivity:114`) →
  `ResourcesCompat.getFont()`

### 30. Manifest warning, live in the build output
```
AndroidManifest.xml:27 Warning: meta-data#com.google.mlkit.vision.DEPENDENCIES@android:value
was tagged at AndroidManifest.xml:27 to replace other declarations but no other declaration present
```
`tools:replace="android:value"` is no longer needed — nothing conflicts. Drop it.

### 31. Unused drawables
`bg_pill_outline`, `circle_background`, `ic_bell_24`, `ic_language_24`, `ic_menu_24`,
`ic_star_24`, `ic_storage_24`, `photo_placeholder_bg` — referenced from nothing.
(`ic_bell_24` hints at a notifications feature that was never built.)

### 32. No tests
`ExampleUnitTest` and `ExampleInstrumentedTest` are the untouched Android Studio templates.
Nothing covers the DAO, `DashboardStats`, `MjpegInputStream` frame parsing, or the auth flow.
`MjpegInputStream.readJpegFrame()` in particular is fiddly byte-marker logic that is trivial
to unit-test and easy to get subtly wrong.

---## Verification status

**Driven on the emulator (API 35), signed in, no crashes in the buffer at any point:**

| Flow | Result |
|---|---|
| Add pet → dashboard | saves, dashboard shows the new pet |
| Dashboard header → pet list (#6, #14) | opens `PetListActivity`, row + species chip + "Tracking" marker render |
| Row overflow → Details / Edit / Delete (#13) | all three present and working |
| Edit → save (#13) | title reads "Edit <name>", form prefills, button reads "Save changes", **updates in place — one row, not a duplicate** |
| Tap a different pet (#14) | returns to dashboard, which now shows that pet; selection persists |
| Delete with confirm (#13) | confirmation dialog, row disappears |
| Delete the **active** pet (#14) | list and dashboard both fall back to the empty state, no ghost, no crash |
| Food tab (#4) | opens `FeedingActivity` — not the old "No pet selected" toast |
| Pet detail rows (#8) | "Food today: no feeder paired" / "Rooms visited today: no sensors reporting" |
| Account back button (#9) | returns to the dashboard |
| Settings back button (#9) | present and working |

**Static checks, re-run after every layout change:**
- Every `findViewById` id resolves against the layout its activity inflates — 10 activities.
- Every `ConstraintLayout` constraint target resolves within its own layout.

**Still not verified:**
- **#7, the Pi stream.** Needs an actual Raspberry Pi serving MJPEG on the LAN. The
  intent-building path is shared and exercised, but nothing has connected to a real stream.
- **#15**, that Glide actually removed the main-thread stall — correct by construction, but no
  frame profiling was done.

**Build note for the emulator:** the full debug APK is ~131 MB (97 MB of it native libs for
four ABIs) and will not install — `INSTALL_FAILED_INSUFFICIENT_STORAGE`. Build emulator-only
and it drops to 60 MB:

```bash
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=x86_64
```

Output lands in `app/build/intermediates/apk/debug/` and is marked test-only, so install it
with `adb install -r -t`. This is what #22 and #26 are really about.

---

## Suggested order for the next session

P0, P1 (#4–#9) and P2 (#12–#19) are done. What is left:

1. **#10 — real data.** The single thing between this and a working app. Every screen is
   reachable and every empty state is honest; they are just empty. Feeder, motion sensors,
   detection persistence and GPS all need a backend that does not exist yet.
2. **#11 — cloud sync.** Deferred to be designed together with #10, since the hardware data
   needs somewhere to live too.
3. **P3 cleanup (#21–#26).** Roughly an hour, cuts ~5 MB and a dozen unused dependencies, and
   would make the APK installable without the ABI workaround above.
4. **#20** is not fixable as written — see the note in the P2 section.
