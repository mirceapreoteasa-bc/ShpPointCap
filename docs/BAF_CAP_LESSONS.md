# Applying the `shp-cap` lessons to `BAF_cap` (GCapAndroid)

Written 2026-09-28, after the Forest Point Collector engagement. This is a
**note, not a work plan** — nothing here has been changed in BAF_cap yet.

Source of the lessons: `/home/mircea/projects/ShpPointCap`, which is itself a
fork of BAF_cap, so the affected code is largely the same code. Most of what
follows is therefore applicable directly.

`/home/mircea/projects/GCapAndroid` is the BAF_cap tree.

---

## 1. Read this first: the two bugs that will bite hardest

### 1.1 `LocationListener` implemented as a SAM lambda — crashes on Android 9

**The single most important item.** BAF_cap almost certainly has the same
pattern, since `shp-cap` inherited its GPS code.

```kotlin
// CRASHES on API 28 whenever the platform calls onProviderDisabled
lm.requestLocationUpdates(provider, 1000L, 0f, LocationListener { loc -> onFix(loc) })
```

Kotlin's SAM conversion generates a class implementing only
`onLocationChanged`. `android.location.LocationListener` also declares abstract
`onProviderEnabled`, `onProviderDisabled` and (pre-API 29) `onStatusChanged`.
When the platform calls one of those, the app dies with:

```
java.lang.AbstractMethodError: abstract method
  "void android.location.LocationListener.onProviderDisabled(java.lang.String)"
```

This is not a rare edge case. It fires whenever the user toggles GPS, when
power saving disables the provider, or on screen-off on some devices. **It is a
hard crash in the field, mid-task.**

Proven on an API 28 emulator, identical procedure:

| Build | Result |
|---|---|
| unfixed | `AbstractMethodError` → process dead |
| fixed | no crash, survived repeated toggles |

The fix is to implement every method explicitly:

```kotlin
val listener = object : LocationListener {
    override fun onLocationChanged(loc: Location) { /* ... */ }
    @Deprecated("Required on API < 29")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        // clear the fix, say so, do not leave a stale marker on the map
    }
}
```

**Lesson:** a SAM lambda is fine for interfaces that are stable forever
(`View.OnClickListener`), and a landmine for interfaces that grew methods
(`LocationListener`, `SensorEventListener`). Implement platform callback
interfaces by hand.

### 1.2 Rotation destroys the activity and throws the session away

If the activity has no `android:configChanges` and no `onSaveInstanceState`,
rotating the phone re-creates it and discards in-memory state: map state,
loaded layers, the recorded-track buffer, the undo stack, an in-progress edit.

In `shp-cap` this presented as *"the map jumped out like it was never
started"* — which I initially misdiagnosed as an out-of-memory kill. It was
not. It was a rotation.

```xml
android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density"
```

Because the activity now survives, the view tree must be **re-flowed by hand**
in `onConfigurationChanged` — otherwise a portrait layout stays portrait-shaped
on a landscape screen. The two-orientation layouts (buttons beside the map in
landscape) came out of that work.

---

## 2. SAF directory-listing cache — the biggest performance win by far

This is the change with the largest measured effect, and it is a small diff.

`SafMapDir.listFiles()` must be served from the already-cached folder listing
(`folderChildren`). If it re-queries the document provider on every call, then
**every file operation costs a full directory listing.** A single save writes
`.shp` + `.shx` + `.dbf` + up to 4 sidecars, and if the write helper consults
`listFiles()` once per file, that is 7 full provider round-trips per save.

Measured on a real phone with ~23 layers / 37 MB:

| Operation | Before | After |
|---|---|---|
| Verify layers | 45 s | 5 s |
| MOB save | 25–30 s | ~0.13 s |
| Save Edits | 25 s | ~0.13 s |

Rule of thumb: **a content-provider-backed directory listing is an expensive
call. List once, cache it, and invalidate the cache explicitly** (on create, on
folder switch) rather than implicitly.

---

## 3. Never do file I/O on the UI thread

The write path above took ~20 s of wall clock on a slow phone. Run inline that
is 20 s of blocked main thread, which is long enough for Android to ANR or
low-memory-kill the process. In `shp-cap` this is what made "Record track"
appear to kill the map.

- Move writes to a worker thread; report back with `runOnUiThread`.
- Show a "Saving…" indicator — 20 s of silence reads as a hang.
- Guard re-entrancy: two threads rewriting the same `.shp`/`.dbf` corrupts it.
  The guard must *say* that nothing was written rather than failing silently.
- Note `catch (e: Exception)` does **not** catch `OutOfMemoryError`. Catch it
  separately where large allocations are possible.

---

## 4. Render cache discipline

If BAF_cap uses a per-layer bitmap cache (QField-style), three things are
required or it will fail in the field:

1. **Cap total cached bytes.** A full-screen ARGB_8888 layer is ~5 MB on a
   1080-wide canvas. Ten cached layers on a 3 GB phone is a plausible OOM.
   Use a global budget with LRU eviction and recycle on layer removal.
2. **Clear the cache bitmap before re-rendering.** Otherwise a pan/zoom draws
   the new geometry over the old and features "trail" across the screen.
   **Only** clear the cache bitmap — clearing the main canvas erases the map.
   (I made exactly that mistake; a screenshot caught it.)
3. **Keep overlays dynamic.** Blit cached layers, then draw GPS marker,
   selection, waypoints and readouts on top every frame.

---

## 5. Shapefile parsing hardening

`shp-cap` and BAF_cap share the same `ShpFormat` reader. These files arrive
from a desktop, a USB stick or a file share, so **every count and length in
them is untrusted.** Found by fuzzing; all are now fixed in `shp-cap`:

- **`IntArray(numParts + 1)` sized from a file-controlled count.** A ~200-byte
  record claiming 50M parts requests a 200 MB array. Validate counts against
  the record's own declared length: a record of `contentLen` bytes can hold at
  most `(contentLen - 44) / 4` parts.
- **Unchecked primitive reads** (`readIntLe`, `readDoubleLe`) index the buffer
  with no bounds check, so a short record reads past the end and throws
  `ArrayIndexOutOfBoundsException` out of a background loader. Make the readers
  bounds-safe.
- **Non-finite coordinates.** `NaN` defeats every bbox comparison (all
  comparisons with `NaN` are false) so it is never culled, and `minOf()` over a
  poisoned layer bbox yields a `NaN` map centre — the map then renders nothing
  at all. Drop non-finite values at the reader.
- Guard record walking against `contentLen * 2` integer overflow.
- Keep the existing `catch (Exception)` **and** `catch (OutOfMemoryError)`
  around parsing so one bad layer degrades to a warning instead of a crash.

A 7-test fuzz suite (truncation at every offset, random mutation, lying counts,
overflow, non-finite coords) exists in
`gcap-core/src/test/kotlin/org/gcap/core/ShpParserFuzzTest.kt` and can be copied
across.

---

## 6. Draw-time cost reductions

Cheap, and they only show up on the map-heavy pages:

- **Simplify at draw time**, not at load: drop vertices closer than ~1 dp
  (`simplify=1` in QGIS terms). Detail returns as you zoom. Keeps a 21 MB
  polyline layer usable at 1:10000.
- **Gate labels to the viewport** — only draw labels for on-screen features.
- **Cull by bbox before reading vertices**, so an out-of-view record costs
  nothing.
- **Load smallest-first** in the background loader, so navigation detail
  appears before bulk layers.
- **Skip I/O entirely for invisible layers** — an OFF layer should not even
  read its header.

---

## 7. Field-realism details worth copying

- **Show load progress.** A long silent load looks like a hang. "Loading… 7/23".
- **Reject GPS outliers** (QField `QfTracker` pattern): discard fixes beyond a
  max jump (500 m default) rather than drawing a track straight to Antarctica.
- **Record a crash log.** Field crashes are not reproducible on a desktop. Write
  the stack trace on `Thread.setDefaultUncaughtExceptionHandler` and show a
  one-line summary on the next launch. This saved a guessing cycle once already.
- **Landscape layout.** Buttons in a left column, map on the right, with a
  global byte budget so the canvas is not starved. Buttons sized by weight
  collapsed to ~20 px slivers otherwise — too small to hit with gloves.
- **Use a consistent lower-case stem** per layer, and make the app's own
  generated files follow it.

---

## 8. Storage gotcha that bit us (and will bite again)

`/storage/emulated/0` is **case-insensitive**. Consequences:

- `POINTS.prj` and `points.prj` are **the same file**. A case-variant never
  coexists, so writing one silently **overwrites** the other. In `shp-cap` the
  app overwrote the user's QGIS-authored `.prj`, silently changing the datum
  label from WGS 84 to NAD 1983.
- `mv Foo.shp foo.shp` is a **no-op** that keeps the original case. Changing
  case on-device needs a two-step rename via a temp name.

Rules that follow:

- One lower-case stem per layer, shared by `.shp .shx .dbf .prj .cpg`.
- **Never rewrite a sidecar that already exists and is correct** — that
  preserves the user's datum and their GIS-authored metadata.
- **Never overwrite `.apl` / `.shp.xml` / `.qmd`.** These carry authored
  descriptions and are not regenerable; `.apl` in particular is ArcPad's own
  layer file and is load-bearing if data is transferred to a Trimble Nomad.

---

## 9. Suggested order, if BAF_cap is picked up again

1. **§1.1 the `LocationListener` fix** — highest severity, smallest diff, and
   it is probably already in BAF_cap.
2. **§1.2 `configChanges` + re-flow** — a field crash.
3. **§2 the SAF listing cache** — the largest measured speedup by a wide margin.
4. **§3 move writes off the UI thread** — prevents ANR/kill on slow storage.
5. **§5 parser hardening** — copy the fuzz suite across with the fix.
6. §4, §6, §7, §8 as convenient.

The first three are each small, self-contained, and independently verifiable.
They are worth doing even if nothing else on this list ever happens.

---

## 10. Added 2026-10-08: decide the working CRS on map start

A later note, from building the same feature in Forest Navigator:
[`PROJECT_SCAN_TODO.md`](PROJECT_SCAN_TODO.md) (also copied into
`GCapAndroid/docs/`).

It is a separate concern from the items above. Those are about crashes and
speed; this is about **a layer silently drawing in the wrong place**, or being
saved wrong. Survey folders genuinely mix NAD_1983 UTM metres with WGS84 degrees
— `points.shp` from the point collector app is degrees on purpose — so the app
has to decide the project's working zone deliberately rather than taking it from
whichever layer happens to be read first.

Worth checking in BAF_cap at the same time as §1.1, since both are small and both
concern data correctness rather than polish.
