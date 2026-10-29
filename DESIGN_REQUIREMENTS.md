# Forest Point Collector (`shp-cap`) — Consolidated Design Requirements

Consolidated 2026-09-28 from the whole engagement. This is the authoritative
record of **what was asked for and why**, as distinct from `README.md`, which is
a chronological build/dev log.

Project: `~/projects/ShpPointCap`
Package: `org.shppointcap.app` · Modules: `:app`, `:gcap-core`
Current builds (2026-09-28):
- **`shp-capq11.apk`** — standard, Android 8+ (`minSdk 26` / `target 34`)
- **`shp-capbv9800.apk`** — Android 9 / API 28, Blackview BV9800 (`minSdk 28` / `target 28`)

173 tests green, both release-signed. `assembleRelease` now builds both
flavours: `assembleStandardRelease` and `assembleBv9800Release`.

**q10 and earlier are defective on every device** — see §11.

---

## 1. Purpose and origin

Standalone Android point collector for forestry fieldwork, forked from
`BAF_cap` (`GCapAndroid`) on 2026-09-22. Only the `GPS_MAP` shapefile stack was
kept; all forestry sampling code (`.dat`, ODS/XLSX, species math, QA) was
stripped out.

Target device: **Blackview BV 5200 Pro**, Android, 3 GB RAM.
Field reality: ~23 layers / 37 MB per project, one 21 MB `Delineation.shp`,
gloves worn, often outdoors in bright light.

---

## 2. Non-negotiable requirements

These are the user's stated requirements, not implementation choices. Changing
any of them needs the user's agreement.

### 2.1 Single data folder, user-owned

- The app works **only** in one shared folder the user picks: `Documents/SHP_CAP`
  on the phone's external storage, via SAF (`DocumentsContract` tree URI).
- **The app must never create folders in user storage.** The user copies
  `SHP_CAP` from a desktop; the app reads and writes inside it.
- All historical fallbacks to `Download/ForestPoints` and similar were
  **removed** — one folder, no confusion.
- `SAF_CAP` / `BAF_CAP` legacy handling is gone. `mirshp.json` is the only
  project file the app writes, in the folder root.

### 2.2 The 6×4 function grid

The main screen is a fixed **6 rows × 4 columns = 24 button** grid, ordered
column-major from the original spec. This layout is fixed; do not reorder.

| | Col 1 | Col 2 | Col 3 | Col 4 |
|---|---|---|---|---|
| **1** | Start_MAP | Layer settings | Select feature | Start Editing |
| **2** | Close_MAP | Add layer | Unselect feature | Stop Editing |
| **3** | Settings | Remove layer | Add_Point | Editable layer |
| **4** | EXIT | Go to… | Delete_Point | Undo last edit |
| **5** | Pan | Record track | Stop recording track | Save Edits |
| **6** | Center at your location | Zoom_IN | Zoom_OUT | MOB |

UI is **programmatic** — no XML layouts for the main screen.

### 2.3 Layer roles (strict)

- **`XGV_MP.shp`** — the editable layer. Add_Point, Delete_Point, Undo,
  Save Edits all operate on it. **Must be UTM Zone 10N** (editing requires it).
- **`POINTS.shp`** — **MOB-only**. Never the editable layer when `XGV_MP` exists.
  Must be **WGS 84** so the downstream dashboard receives degrees.
- **`track.shp`** — recorded GPS tracks, WGS 84.

### 2.4 Map behaviour

- North-up, UTM working zone, zoom **1:500 – 1:400000**.
- 17 map overlays, from `point_collector_ODS.ods` rows 7–18 (Mapsheet,
  Satellites, Precision, Date, Time, UTM Zone, Easting, Northing, Elevation,
  Scale, Latitude, Longitude, Go-to, Bearing, Distance, Project).
- Live GPS marker with accuracy; "Center at your location".
- `Pan` mode translates the map, north stays up.

### 2.5 Landscape support

Required, not optional — the phone rotates in the field. **Landscape puts the
button grid in a left column and the map on the right** (grid ≈ 40 % of width).
Rotating must **not** destroy and recreate the activity.

---

## 3. Data conventions (settled)

- **All layer stems lowercase**, one stem shared by `.shp .shx .dbf .prj .cpg`.
  Every shapefile the app creates uses a lowercase name: MOB → `points.shp`,
  track → `track.shp`, generator → `rivers_north.shp` from `RIVERS_North`,
  Add-layer import → `rivers.shp` from `Rivers.shp`.
- **Delete `.qix` and `.qmd`? — No, keep both.** `.qmd` (QGIS metadata) and
  `.shp.xml` (ESRI metadata) carry authored descriptions and are **not
  regenerable** — deleting loses the content. `.apl` is **ArcPad's** layer file
  and the user transfers files to a **Trimble Nomad 900 running ArcPad**, so
  `.apl` is load-bearing and must be kept.
- **The app must never overwrite an existing `.apl`/`.shp.xml`/`.qmd`.**
  Verified by sentinel test: after Save Edits and MOB, an ArcPad-authored `.apl`
  is byte-identical. Sidecars are written only when the layer is first created.
- `.qix`/`.qmd` produced by QGIS on the desktop are the user's business; the
  app ignores both.
- When a POINTS layer is created by the app it gets `.prj` + `.cpg` only (no
  metadata stubs). The layer **generator** still writes the full set including
  the 12-byte `.apl` placeholder — user chose to keep `.apl` on principle.

### 3.1 CRS rules

| Situation | Behaviour |
|---|---|
| `POINTS.shp` absent | Created as **WGS 84 degrees** + `GEOGCS` `.prj` |
| `POINTS.shp` exists | Follows **its own** `.prj`; `.prj` never rewritten |
| `track.shp` absent | Created as **WGS 84 degrees** + `GEOGCS` `.prj` |
| `track.shp` exists | Follows its own `.prj`; never rewritten |
| Editable `XGV_MP` | UTM 10N required; rewritten only if `.prj` says otherwise |
| Unrecognised CRS | Layer **skipped** with toast "Skipped (not UTM Zone 10N)" |

Supported: **UTM 10N**, **WGS 84 (degrees)**, **BC Albers** (reprojected
through WGS 84 to the working zone), plus Z-variants (`POINTZ`/`POLYLINEZ`/
`POLYGONZ`).

**Hard rule:** the app follows whatever `.prj` says. It never relabels an
existing file's CRS. A wrong `.prj` is a *data* bug, fixed on the desktop.

> **QGIS warning:** re-exporting a CRS must use **Reproject**, never
> "Assign/Set CRS". Assign relabels without moving coordinates, producing a file
> whose `.prj` and coordinates disagree — points then plot near (0,0).

---

## 4. Android storage: case-insensitive

`/storage/emulated/0` (emulated storage) is **case-insensitive** — verified:

```
write AAA -> CaseTest.txt ; write BBB -> casetest.txt  =>  ONE file reading BBB
```

Consequences, all load-bearing:

- `POINTS.prj` and `points.prj` are **the same file**. A case-variant never
  coexists, so the app's sidecar write **silently overwrites** the desktop
  copy's `.prj`. This is how the user's WGS 84 datum label was replaced with
  NAD_1983.
- `mv Foo.shp foo.shp` is a **no-op** that keeps the original case. Renaming on
  the phone requires a two-step rename via a temp name. The user lowercases on
  the **desktop** and copies.
- Lowercase-everywhere is the defence. It is now enforced in code.

---

## 5. Field findings and the bugs behind them

Each of these was found by the user testing on the real phone.

| Symptom | Root cause | Fix |
|---|---|---|
| Verify layers 45 s | `SafMapDir.listFiles()` ignored its own cache and re-queried the SAF provider on **every** call; a save calls it once per file (7 listings/save) | Serve from the cached `folderChildren` → **Verify 5 s, saves ~130 ms** |
| MOB / Save Edits 25–30 s | Same, plus 4 aux files rewritten every save | Skip aux when `.prj` already correct (case-insensitive lookup — the case mismatch made the first attempt never match) |
| Panning left "multiple instances" of lines | Cache bitmap was never cleared before re-render, so new shapes drew over old ones | Clear the cache bitmap — **only** on the cache bitmap; clearing the real canvas erases the whole map (this mistake was made and caught) |
| Map "jumped out like it was never started" | **No `android:configChanges`** → any rotation destroyed/recreated the activity, discarding `mapStarted`, layers, track buffer, undo stack | `configChanges` on all 3 activities + manual re-flow in `onConfigurationChanged` |
| Track recording killed the map after ~20 s | SAF writes ran **on the UI thread**; 5–6 operations ≈ 20 s on a slow phone → ANR / low-memory kill | `runWrite()`: writes on a worker thread, "Saving…" banner, `writeBusy` re-entrancy guard |
| Rivers layer invisible | Folder listing is cached; a file copied in mid-session isn't seen until restart | Close_MAP → Start_MAP. Confirmed working — **not** a layer limit, not a CRS problem |
| Unbounded memory in the parser | `IntArray(numParts + 1)` sized from a file-controlled count; primitive readers had no bounds check | Counts validated against the record's declared length; part offsets clamped; all readers bounds-safe |

### 5.1 Notes on two of my own misdiagnoses

- The "jumped out" symptom was **blamed on OOM**. It was rotation. The bitmap
  byte budget (40 MB, LRU eviction) is still correct defensive work, but it was
  not the cause.
- The bitmap cache bug I introduced (clearing the main canvas → black map) was
  caught by an emulator screenshot, not by reasoning.

### 5.2 Field-confirmed results (q8, user's phone)

- Verify layers: **5 s** (was 45 s)
- Start_MAP load: **5 s** (was 60–80 s)
- Panning and zooming: snappy
- All 24 functions work in **both** orientations
- Landscape proportions "very good"
- MOB captured points correctly in WGS 84 into a fresh `POINTS.shp`

---

## 6. Performance design

- **Dirty-layer render cache** (QField-style): only layers whose shapes, style
  or viewport changed re-render; the rest blit a cached bitmap. Layers with
  < 30 shapes skip the cache to bound memory.
- **40 MB global bitmap budget** with LRU eviction; recycle on layer removal
  and on Close_MAP. Without a cap, a dozen full-screen ARGB bitmaps (~5 MB each
  on a 1080-wide canvas) OOM-kill a 3 GB phone.
- **Draw-time simplification**: vertices dropped when closer than 1 dp
  (matches the user's `.qgs` `simplify=1`).
- **Label gating**: labels only drawn for on-screen features.
- **GPS outlier rejection** (QField `QfTracker` pattern): fixes beyond
  `trackMaxDistanceM` (default 500 m) are discarded.
- Load order: navigation layers first (Ground_Call), then small working layers,
  then the editable/visible crew layers, then the bulk. Invisible (OFF) layers
  skip header/`.prj` reads entirely — with most of 21 layers off, that is the
  bulk of Start_MAP I/O.
- "Loading layers… N/M" banner, because a 60 s silent load looks like a hang.

---

## 7. Security posture (audited 2026-09-28)

**Clean:**
- **No `INTERNET` permission and no network code** — the app is architecturally
  incapable of exfiltrating field data.
- Only two permissions: `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`.
  No storage permissions (SAF only).
- `SettingsActivity` / `LayerSettingsActivity` are `exported="false"`.
  `MapActivity` is exported only as launcher and **reads no intent extras**.
- Release build is not debuggable.
- **No GPS coordinates are logged.**

**Fixed:** unbounded, file-controlled allocation in `readShp`; out-of-bounds
primitive reads; non-finite (`NaN`/`Inf`) coordinates poisoning the map centre
(they defeat every bbox comparison, so `minOf()` over the layer bbox yields a
`NaN` centre and the map renders nothing). Guarded by a 7-test fuzz suite:
truncation at every offset, 3 000 random mutations, short records, `contentLen`
overflow, lying counts, non-finite coordinates, heap-growth assertion.

**Accepted, not fixed (low severity):**
- `allowBackup="true"` — `adb backup` can pull app-private files (SAF URI,
  crash log). Shapefiles in `SHP_CAP` are unaffected. One-line fix.
- Startup log prints the SAF folder URI and some layer filenames. logcat access
  already implies ADB, and a content URI alone grants nothing.

---

## 8. Crash diagnostics

A crash handler in `CollectorApp` writes the stack trace to
`crash.log` in the app's external files dir and records a one-line summary,
shown as a dialog on the next launch. Field crashes are not reproducible on the
emulator, so this is the only way to diagnose them.

---

## 9. Open items

1. **User to re-copy `SHP_CAP` from the desktop** (lowercase stems, WGS 84
   `points.prj`) so the NAD_1983 relabel is discarded. Only matters *before* the
   first MOB — the `.prj` present at that moment decides the CRS for every
   appended point.
2. Verify the first MOB (layer creation) is faster than the 20 s originally
   reported; subsequent MOBs are ~130 ms.
3. Optional hardening: `allowBackup="false"`, drop the SAF URI from the log.
4. `MULTIPOINT` (types 8/18) still parses empty. Unused by this project.
5. Canvas pixels are not square metres, so polygon fill looks slightly tall.

---

## 10. Build / release

```bash
ANDROID_SDK_ROOT=~/Android/Sdk ./gradlew :app:assembleDebug :app:testDebugUnitTest :gcap-core:test
ANDROID_SDK_ROOT=~/Android/Sdk ./gradlew :app:assembleRelease
# then sign + copy:
cp app/build/outputs/apk/release/app-release.apk ~/Forest_Point_Collector/shp-capqN.apk
~/Android/Sdk/build-tools/*/apksigner verify <apk>
```

Release key: `shp-cap-release.keystore` (all q-vN builds share it, so installs
are upgrades, not reinstalls). Emulator AVD `gcap_test`, launched with
`-gpu swiftshader_indirect`.

Version ladder: q1 → q11. **q2 is defective** (wrote a degrees `.prj` over
metre data) — never install it. **q10 and earlier carry the Android 9
`LocationListener` crash** — use q11 or later.

---

## 11. Android 9 crash (found by testing on a real API 28 image)

`requestLocationUpdates` was given a Kotlin **SAM lambda**:

```kotlin
LocationListener { loc -> onFix(loc) }   // only implements onLocationChanged
```

`android.location.LocationListener` also declares abstract `onProviderEnabled`,
`onProviderDisabled` and (pre-API 29) `onStatusChanged`. When the platform calls
one, the process dies:

```
java.lang.AbstractMethodError: abstract method
  "void android.location.LocationListener.onProviderDisabled(java.lang.String)"
```

This is a **field crash, not an edge case**: it fires whenever the user toggles
GPS, or when power saving disables the provider. Verified on an API 28 emulator
with an identical procedure:

| Build | Result |
|---|---|
| `shp-capq10.apk` (unfixed) | `AbstractMethodError` → process dead |
| `shp-capbv9800.apk` (fixed) | no crash, survived 3 off/on toggles |

**This affected the Android 10+ build too** — it is a platform-interface
problem, not a version problem. Both q11 and the bv9800 build fix it by
implementing every method explicitly, and `onProviderDisabled` now clears the
stale marker instead of leaving a frozen fix on the map.

Test AVD: `bv9800` (`system-images;android-28;google_apis;x86_64`), launched on
port 5556. Reproduce with:
`adb -s emulator-5556 shell settings put secure location_mode 0` (then `3`).
