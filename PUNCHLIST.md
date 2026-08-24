# Pet Tracker — Punch List

State: P0 and P1 (#4–#9) fixed on 2026-08-24. Branch `master`, last commit `7a72903`.
All fixes are staged, not committed. See "Verification status" near the end for what has
been confirmed by running it versus what still needs a signed-in click-through.

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

## P2 — Correctness, data, and platform

### 11. Pet data is local-only and silently device-bound
Room DB `pets_db` keyed on the Firebase `uid`, but nothing syncs. Same account on a new
device shows zero pets and the user has no way to know why. Firebase is in the project
(auth only) — Firestore would close this.

### 12. `fallbackToDestructiveMigration()` wipes user data on any schema change
`AppDatabase.java:22`. Fine while iterating; must become a real `Migration` before anyone
installs an update over an existing build. `@Database(version = 2)` already implies one
schema change has happened.

### 13. No way to delete or edit a pet
`PetDao.delete()` is declared and never called. There is no update query at all. Once a pet is
saved it is permanent and immutable.

### 14. Only the first pet is ever shown
`MainMenuActivity.java:135` and `FeedingActivity.java:59` both take `pets.get(0)`. Multi-pet
is half-modelled (the DAO returns a list, `AddPetActivity` can add many) but there is no
picker and no list. See #16 — the adapter for exactly this already exists, unused.

### 15. Full-size bitmap decode on the main thread
`MainMenuActivity.java:160` and `Petmenu.java:138` use `ImageView.setImageURI()`, which
decodes synchronously on the UI thread. Glide is already a dependency. Use it (as
`PetsAdapter` correctly does) or you will drop frames on large photos.

### 16. Dead code: `PetsAdapter` + `item_pet.xml`
Neither is referenced anywhere — no `RecyclerView` exists in any layout. This is the pet-list
screen that was never wired up. Either build the list (which also fixes #6 and #14) or delete
both. Note this is the *only* consumer of the `recyclerview` and `glide` dependencies.

### 17. Orphaned `res/xml/pref_general.xml`
`SettingsActivity` is a hand-built `MaterialCardView` screen, not a `PreferenceFragment`.
The prefs XML is never inflated — the comment at `SettingsActivity.java:22` refers to a
screen that no longer exists. Delete it and the `androidx.preference` dependency, or migrate
the Settings screen back to it. Note the two would also disagree on storage:
`SettingsActivity` uses a private `"app_settings"` file, while `PreferenceFragment` uses the
default SharedPreferences.

### 18. Two independent SharedPreferences files
`PiConnectionManager` writes to `"pet_tracker_prefs"`; `SettingsActivity` writes to
`"app_settings"`. Works, but pick one before adding a third.

### 19. Unhandled activity-lifecycle races in the DB threads
`MainMenuActivity.loadActivePet()`, `Petmenu.loadPetFromDb()`, `FeedingActivity.load()` all
use raw `new Thread` + `runOnUiThread` with no cancellation. Rotate or back out while one is
in flight and it touches a destroyed activity. `FeedingActivity.java:56-57` additionally
dereferences `getCurrentUser()` with no null check — logging out with the Food tab open is
an NPE.

### 20. Cleartext HTTP enabled app-wide
`android:usesCleartextTraffic="true"` in the manifest, needed for the Pi MJPEG stream on
port 8080. Scope it to the local network with a `network_security_config.xml` rather than
opening every connection in the app.

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

---
## Verification status

**Confirmed by running it:**
- `:app:assembleDebug` → BUILD SUCCESSFUL, no warnings beyond the pre-existing ML Kit
  manifest one (#30).
- Installs and launches on the `Medium_Phone_API_35` emulator; `LoginActivity` resumes with
  an empty crash buffer.
- Every `findViewById` id resolves against the layout its activity actually inflates — all
  9 activities, 94 bindings. This is the check that would have caught the P0 bug, and is
  worth re-running after any layout edit.
- Every `ConstraintLayout` constraint target resolves within its own layout.

**Not yet confirmed — needs a signed-in session:**
The authenticated flows could not be exercised. The screens are not exported, so `adb shell
am start` is refused (`SecurityException: not exported`), and driving the UI means signing
in to Firebase — which needs the account password, so it is for you to do, not me.

Still to click through once signed in:
1. Food tab → lands on `FeedingActivity` (not a "No pet selected" toast)
2. Dashboard pet header → opens `Petmenu` with the right pet
3. `Petmenu` → Watch live → Pi prompt / stream; Feeding → `FeedingActivity`
4. Back buttons on Account and Settings
5. Bottom nav highlight follows the screen when moving between tabs

**Build note for the emulator:** the full debug APK is 131 MB (97 MB of it native libs for
four ABIs) and will not install — `INSTALL_FAILED_INSUFFICIENT_STORAGE`. Build emulator-only
and it drops to 60 MB:

```bash
./gradlew :app:assembleDebug -Pandroid.injected.build.abi=x86_64
```

Output lands in `app/build/intermediates/apk/debug/` and is marked test-only, so install it
with `adb install -r -t`. This is what #22 and #26 are really about.

---

## Suggested order for the next session

P0 (#1–#3) and P1 (#4–#9) are done. Pick up at:

1. **Click through the five flows above** on a signed-in emulator — the only unverified part
   of the P1 work.
2. Then pick: **real data** (#10 — now the single thing between this and a working app;
   every screen is reachable, they are just empty) or **cleanup**
   (#21–#25, roughly a one-hour sweep that cuts ~5 MB and a dozen dependencies, and would
   also make the APK installable without the ABI workaround).

Everything so far is **staged, not committed**. Commit when you are happy with it.
