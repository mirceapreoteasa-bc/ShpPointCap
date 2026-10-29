# Forest Point Collector (`shp_point_cap`)

Standalone Android point-collector forked from BAF_cap (`GCapAndroid`) on 2026-09-22.
It keeps **only** the GPS_MAP stack; all forestry sampling code (.dat, ODS/XLSX,
species math, QA) was stripped.

## What was extracted (verbatim, battle-tested)

| File | Origin | Notes |
|---|---|---|
| `app/.../map/MapCanvasView.kt` | BAF_cap unchanged (package renamed) | North-oriented UTM canvas; `MapLayer` already carries `color`, `labelField`, `labelColor` — the hooks for the planned styling settings |
| `app/.../map/SafMapDir.kt` (+`FileMapDir`) | BAF_cap + new `createFile()` | SAF or plain-file access to `GPS_MAP`; `createFile` added so the first save works when `POINTS.*` is absent |
| `app/.../map/Utm.kt` | extracted from `GpsActivity` | WGS84→UTM math, zone label, `fmt()` — pure JVM, no Android deps |
| `app/.../GCapStorage.kt` | BAF_cap (root → `Download/ForestPoints`) | Internal + SAF backends, incl. the directory-listing cache perf fix |
| `gcap-core` (whole module) | BAF_cap as-is | Only `ShpFormat`, `ShpAuxiliaryExporter`, `GCapStorage` are used; forestry files kept for now, prune later |
| `MapActivity` map engine | ported from `GpsActivity` map half | 6×4 (24-button) function grid, background viewport loading (`loadSeq` guard), tap add/select/delete, zoom 1:500–1:400000, live-GPS follow + marker, 17 map overlays |

## What is new / simplified for v1

- `CollectorApp` replaces `GCapApp` (no sample DB; data folder + SAF-tree prefs only).
- `MapActivity` is the single screen (see *Main page* below): **Start_MAP** (loads
  every `.shp` in `GPS_MAP`, point-vs-line auto-detected from the `.shp` header),
  editable green `POINTS` layer (`Name` DBF field, auto names `PT001…`), **MOB**,
  **Save Edits** → `POINTS.shp/.shx/.dbf` + `.prj/.cpg/.shp.xml/.apl`.
- Package `org.shppointcap.app`, app name "Forest Point Collector".
- No layouts needed (UI is programmatic); no desktop module.

## Polygon layers: supported (2026-09-25)

- Reader accepts `POINTZ`/`POLYLINEZ`/`POLYGONZ` (QGIS Z exports) plus plain
  `POLYGON`; XY section layout is identical, trailing Z/M arrays are skipped.
  (Your 21 MB `Delineation.shp` is `POLYLINEZ` — it previously parsed to zero
  shapes and now loads.)
- Canvas draws polygons filled (translucent layer color) + solid border, one
  label per shape at the bbox centre. Auto-detection maps types 1/11 → POINT,
  3/13 → LINE, 5/15 → POLYGON (picked-file loader included).
- Proven: `ShpReaderTypesTest` (3/3 JVM) + live emulator screenshot of a
  synthetic `TEST_POLY.shp` rendering filled at the GPS fix.
- Known limitation: `MULTIPOINT(8/18)` shapes still parse empty; canvas pixels
  are not square metres (fill looks taller than wide — pre-existing for all
  layers, not polygon-specific).

## Layer styling engine — done, unit-tested (2026-09-25, `LayerStyle` + canvas symbols)

- `project.json` (converted from `layer_settings.ods`, 20 rows) drives layers:
  data-folder override first, assets fallback. Same file the future settings
  UI will read/write.
- Fuzzy match: exact stems, then prefix either way in sheet order
  ("roads"→`roads4.shp`/`roads.shp`, "Air_Calls.."→both files, "fire"→both
  fire files); leftovers go last, visible, neutral gray. Draw order = reversed
  sheet order (XGV_MP on top, prov_parks bottom).
- Symbols: points circle/triangle/square/diamond; lines simple/dash/dot/
  double-road casing; polygons simple-fill (50% alpha)/4 hatches/dot/no-fill
  via cached `BitmapShader` tiles; sizes dp (points radius, lines stroke min
  1px), labels in sp; `"<color> border"` syntax for border-only styles.
- Editable candidates = `editable:Y` rows (XGV_MP/track/POINTS); active
  defaults to XGV_MP (Add_Point target), POINTS is MOB-only. Editable
  read/write converts through WGS84 for geographic layers (zone 10).
- JVM-tested: `LayerStyleTest` 8/8 (matching, ordering, aliases, colors,
  project parse). Canvas proof on emulator pending stable folder.
- PROVEN on emulator with the real 22-file set: styled lines (Delineation
  gray default, Access brown, Transect violet), pink `results` fills,
  yellow `fire` hatching, `Call_Num`/`DISTURBA_1` dbf labels with halo,
  WGS84 `track.shp` reprojecting to the right place, OFF layers hidden
  (prov_parks/lakes/grid/Flight_Path/rivers/landings), UTM-guard skip path,
  and the data-folder `project.json` override chain (flipped `results` OFF
  live, no rebuild). Progressive per-layer loading keeps small layers instant
  behind the 21 MB Delineation parse.

## Phase 1 — done and proven on-device (2026-09-25)

- **Bbox-skipping reader**: out-of-view records skip vertex parsing (record
  indices still advance, DBF alignment preserved). The 21 MB `Delineation.shp`
  now loads in seconds instead of choking the phone.
- **Single-UTM policy**: layers classified from their `.prj` (`UTM_METERS` /
  `WGS84_DEGREES` / `OTHER`); non-UTM layers skip with a toast instead of
  drawing garbage. `contour.shp` + Albers `fire.shp` stay off-device per plan.
- **WGS84 round-trip** for `track.shp` (+ any future geographic layer):
  forward-project to UTM 10 on load (`Utm.wgs84ToUtm`, sub-metre vs pyproj),
  inverse (`Utm.utmToLatLon`) ready for track recording + GPX later.
- **`Call_Num` default labels** on `Ground_Call`.
- **Proof**: emulator screenshots — real `fire2` polygon filled/bordered,
  `Delineation`/`Ground_Call` listed, synthetic WGS84 tracks drawn at exactly
  the right place and size around the live fix. JVM tests: `UtmCrsTest` (3/3:
  pyproj anchor, BC round-trips, prj classification) + `ShpReaderTypesTest`
  (3/3: POINTZ/POLYGON/POLYGONZ/POLYLINEZ).

## Layer settings page — done, proven on-device (2026-09-25)

- `LayerSettingsActivity` (4th button row, "Layer settings"): every `.shp`
  in GPS_MAP in display order, each with ON/OFF, Editable, symbol/color
  pickers (choices constrained by geometry kind), size/label/label-size
  fields, and Move Up/Down. Save writes data-folder `project.json`; the map
  picks it up on the next Start_MAP (fresh read, no restart needed).
- Proven: page renders all rows from the real 22-file folder; a
  data-folder override flips map content live (results fill on/off).
- Debugging paid off twice: an Android `org.json.optString()` quirk returns
  the literal string `"null"` for explicit JSON nulls (showed as Label=null)
  — parser hardened + generator emits `""`; and blank numerics stay blank
  (no phantom 2.0/7.0 defaults written back).
- Suite: 14 app tests (incl. JSON round-trip + null-quirk regression).

## General Settings page — done, proven on-device (2026-09-25)

- `SettingsActivity` mirrors `settings_1.ods` row order: layers folder
  (+Change picker), working CRS (Auto-first-layer / WGS84-auto-zone /
  UTM 7–11N), autosave seconds (blank=off, default 40), track interval +
  min distance (default 5 m), zoom min/max spinners + default scale (fixed
  1:500–1:400000 list, typos fixed), go-to layer/column, Add-Point / MOB /
  track layer pickers (populated from the actual folder).
- Persists into data-folder `project.json` beside layers (layer rows
  preserved verbatim); map applies on next Start_MAP: default scale, zoom
  clamps, working zone for conversions, silent autosave loop (edits only).
- Track interval default: **10 s** (crew decision 2026-09-25); blank falls
  back to 10 in the recorder.
- Proven: set Default Scale 1:4000 on-device → map restarts at exactly
  1:4000 (200×160 m readout). Suite: 17 app tests (incl. blank-off
  round-trip, zone-resolution precedence incl. the "WGS84 contains 84" trap).
- Implemented on the main page instead: MOB button, Go-to picker, track
  recorder (interval+distance+layer from settings), editable-layer switcher.

## MOB button + Name/15 rule — done, proven on-device (2026-09-26)

- Bar button `Capture_GPS` replaced by **MOB**: captures the live fix into the
  fixed `POINTS.shp` with auto `DDHHMM` name (rename-before-save dialog).
  Active-editable == POINTS feeds the edit buffer; otherwise (normally, with
  XGV_MP active) appends straight to the file (never clobbers another
  layer's unsaved edits). If `POINTS.shp` didn't exist at Start_MAP, MOB
  creates it and adds the layer to the map so the point shows immediately.
- `POINTS.shp` attribute table is now **`Name`, text, 15 chars**
  (`motel_2027`, `parking10june` fit); other editable layers keep their
  existing first field (`XGV_MP` keeps `GCALL_ID/10`), brand-new files get
  `NAME/15`. Proven live: renamed MOB point saved at exact fix coords with
  `NAME/15` dbf.
- Suite: 18 app tests (incl. `mobAutoName` DDHHMM format).

## Project files — second column + Start_MAP chooser (2026-09-26)

Spec: `settings_1.ods` column 2 — *Save Map Settings* / *Save Map As* /
*Restore default settings*; and `SHP_CAP` may hold several maps, so Start_MAP
must let the user pick which project file to open.

- **Model**: a project = `<name>.json` at the data-folder root
  (`Download/ForestPoints/*.json`), holding `layers` + `settings`.
  `CollectorApp` owns the active name (`spc_active_project` pref, default
  `mirshp`), `writeProject`/`listProjects`/`sanitizeProjectName`.
  `LayerStyle.docWithLayers` / `docWithSettings` merge each half into the
  existing doc so saving one never wipes the other. Missing file → assets
  `mirshp.json` (renamed from `project.json`) in every reader
  (`MapActivity`/`SettingsActivity`/`LayerSettingsActivity`).
- **Settings page, section "Project file"** (rendered above the setting rows):
  current map name, **Save Map Settings** (writes form into the active file,
  page stays open), name field + **Save Map As** (new file, layers carried
  over, becomes active), **Restore default settings** (confirmation dialog →
  shipped defaults written into the active file, layer rows kept verbatim).
- **Start_MAP chooser**: with ≥2 `*.json` at the root, an "Open map" dialog
  lists them with the active one preselected; with 0–1 it starts directly.
  Selection sets the active project before `startMap()`.
- Proven on emulator: `Save Map As` → `testmap.json` created; chooser listed
  `crew2`/`testmap` and opening `crew2` loaded its 1:5000 default scale;
  `Restore default settings` reset `crew2.json` settings to shipped defaults
  (defaultScale 10000) while keeping all 20 layer rows, including blank
  (`""`) sizes.
- JVM: `LayerStyleTest` covers both merges, name sanitising and
  blank/null round-trips (19 app tests total with `UtmCrsTest`).

## Main page per `point_collector_ODS.ods` — done, proven on-device (2026-09-26)

Spec: the three layout sheets (`settings_1.ods`, `layer_settings.ods`,
`point_collector_ODS.ods`) plus their in-cell comments.

- **Title row** = app name only (the action bar); **function grid** = 6 rows ×
  4 columns, ~30% of the screen (row-major from the sheet's column lists):
  `Start_MAP / Layer settings / Select feature / Start Editing`,
  `Close_MAP / Add layer / Unselect feature / Stop Editing`,
  `Settings / Remove layer / Add_Point / Editable layer`,
  `EXIT / Go to… / Delete_Point / Undo last edit`,
  `Pan / Record track / Stop recording track / Save Edits`,
  `Center at your location / Zoom_IN / Zoom_OUT / MOB`.
  `Available layers` and `Data folder…` were dropped (folder lives in
  Settings → Layers folder; the list in Layer settings). Map takes the
  remaining ~66% (`weight` layout, no pixel maths).
- **17 overlays** drawn over the canvas: left column Mapsheet (NTS port
  `NtsSheets`, BAF_cap copy), Satellites (`GnssStatus.Callback`), Precision,
  Date, Time (1 s ticker), UTM Zone, Easting, Northing, Elevation, Scale,
  Latitude, Longitude; right column Project name, north arrow (graphic),
  `Go to:…`, Bearing + Distance from the fix to the Go-to point.
- **Pan**: `Pan` arms drag-panning (north stays up, no zoom/orientation
  change, GPS fixes stop re-centring); it ends on map close,
  `Center at your location`, or a `Go to…` pick — a Go-to pick pins the
  centre on the target (bearing/distance stay usable while you walk to it).
- **Go to…**: dialog listing the settings layer/column values, centres the
  map on the picked shape and marks it with a blue ring + label (cleared by
  Close_MAP / Center at your location). Layer-dependent commands refuse
  while the map is still loading ("Map still loading layers…").
- **Record track / Stop**: fixes appended every *interval* s subject to the
  *min distance* gate, written as a `track.shp` POLYLINE (one part, built by
  `ShpFormat.buildPolylineShp/Shx` + `Name`/15 dbf, appended to any existing
  parts) plus a `track_<DDHHMM>.gpx` (WGS84). Files < 2 points are dropped.
- **Editable layer**: pick any loaded point layer as the Add/Delete target at
  runtime; **Undo last edit** pops the add/delete buffer (undo of a delete
  re-adds the point).
- **EXIT** saves open edits (prompt) and closes the app.
- **Add layer** imports for good (was session-only): the picker accepts a
  multi-select, and every `.shp` is copied into the Layers folder together
  with its `.shx/.dbf/.prj/.cpg` — sidecars come from the selection first,
  else from beside the picked file when the Layers-folder tree grant covers
  it (`buildDocumentUriUsingTree`; a plain document URI is not covered). A
  name collision asks *Replace*, the layer joins the running map, and it
  reappears on the next `Start_MAP` and in Layer settings. Toasts stay inside
  the ~2-line budget (`… copied to Layers folder (4 files)` / `… missing shx,
  dbf, prj: labels/CRS limited`). `Remove layer` only stops the layer drawing —
  files are never touched (see Planned next steps).
- **Settings** now renders two columns (A = the 12 setting rows incl. Layers
  folder, B = the project commands) under a `SETTINGS` title; **Layer
  settings** renders `Layers Settings` + the attribute header row
  (`Layer ON/OFF Editable Symbol Symbol Color Symbol Size Label Label Size`).
- Proven on emulator: 24 labels exactly as specced; overlays/North arrow;
  Mapsheet `092K096 → 092K086` when centred elsewhere; drag-pan moves
  easting/longitude only (north up) and survives a distant GPS fix;
  `Center at your location` snaps back to the fix; Go-to picks `Call_Num` →
  centre moves + `Bearing 204° / Distance 15.74 km` (matches the great-circle
  distance); add → undo → save leaves `POINTS.shp` at 2 records; track write
   produced a 1-part/2-point `track.shp` (type 3) + 2-point GPX; `EXIT`
   returns to the launcher.
- **Layers folder** (proven this session): a user-made
  `Documents/SHP_CAP` picked via Settings → Layers folder works for
  read/write/edit — layers listed, `Save Edits` rewrote
  `…/SHP_CAP/GPS_MAP/POINTS.shp` (156→184 B). `SafMapDir` accepts either
  `<folder>/GPS_MAP/*.shp` **or** the `.shp` files directly in the picked
  folder (flat layout), and creates `GPS_MAP` on the first write. Changing
  the folder is picked up live (toast *“Layers folder changed — Close_MAP
  then Start_MAP to load it”*), and a folder with no shapefiles starts the
  map with an explanatory toast. **A fresh install with no folder picked
  shows an empty map** — Android 11+ blocks the plain-`File` fallback into
  `Download/`, so the SAF pick is required (and `pm clear` / reinstall
  revokes the old grant: the folder must be re-picked).
- Suite: 73 JVM tests (app + `gcap-core`, incl. `ShpPolylineTest`,
  `NtsSheetsTest` port, and `LayerImportTest` for Add-layer grouping).
- **Slow-phone performance (v3)**: `defaultLayers` read every `.shp` in full
  for its 100-byte header — now `readHeader()`. `Start_MAP` builds on a
  background thread ("loading layers…" toast, no ANR). Shape loading capped
  at 4 threads; SAF folder listings cached. The 1-second overlay ticker no
  longer re-reads the folder (working zone cached). `onDraw` culls shapes
  outside the viewport, reuses one `Path`, and hoists all per-frame Paint /
  effect allocations to fields.

## Planned next steps (as requested)

1. ~~More layers — persist picked layers into `GPS_MAP`~~ — **done**: `Add
   layer` copies `.shp` + sidecars into the Layers folder (see the main-page
  notes), proven on emulator (single pick with sidecar follow-up,
  4-file multi-select, collision prompt, reload after `Close_MAP`).

2. **Remove layer** now means what the ON/OFF box means in Layer settings:
   the layer stops drawing and `visible:false` is written to the project
   model (row created if the file has none yet), so Layer settings shows it
   unticked and it stays hidden after `Close_MAP`/`Start_MAP` until ticked ON
   there + Save. Files are never deleted; the editable layer is never offered
   and the prompt is refused mid-edit. Proven on emulator: row unticked,
   absent from Remove layer across a map restart, restored by ON + Save.
   (Note: the ON/OFF checkbox label is static "ON" text — the check mark is
   the state.)
- **Spec-row duplication fixed (v4)**: `LayerStyle.match` handed one file to
  every same-named spec row, so a duplicated `project.json` row multiplied
  the Layer settings list on each Save (40+ `track.shp` rows seen on device).
  Each file is now claimed by the first matching row only — the list is one
  row per file and a Save self-heals the file. `persistLayerOff` updates by
  row name instead of object identity. Regression test
  `duplicateSpecRowsDoNotDuplicateFiles`.

3. Next candidate: **Heal problem layers** (Settings command, requested):
   scan the Layers folder + project file for missing sidecars (`.shx`/`.dbf`/
   `.prj`), unrecognized CRS, duplicate spec rows, unreadable files — report
   per layer and offer one-tap fixes (drop duplicate rows, etc.). Not started.

## Build

```bash
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

SDK path is in `local.properties` (`~/Android/Sdk`).
