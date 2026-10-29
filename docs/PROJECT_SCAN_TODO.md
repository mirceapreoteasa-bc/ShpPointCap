# TODO: decide the project's working coordinate system on map start

Written 2026-10-08 while building the same feature in Forest Navigator. This is a
**note, not a work plan** — nothing here has been changed in this app yet.

This app is the reference for the MOB and track-recording behaviour that
Forest Navigator now mirrors, so it is the likeliest place for the CRS handling
to already be right — worth checking before copying anything.

Source: `~/projects/ForestNavigator`, file
`gcap-core/src/main/kotlin/org/gcap/core/nav/ProjectScan.kt`, with tests in
`ProjectScanTest.kt`. Read those before implementing; the logic is portable and
has no Android dependencies.

---

## The problem

A project folder can hold layers in different coordinate systems at once. The
Forest Point Collector / survey folders do exactly this:

| Layer | CRS |
|-------|-----|
| `ground_call.shp`, `xgv_mp.shp`, `roads.shp` | NAD_1983_UTM_Zone_10N, metres |
| `points.shp` (from the point collector app) | WGS84, degrees |
| some layers | no `.prj` at all |

Reading degrees as metres is the dangerous case: nothing throws, the file still
opens in QGIS, and the features are simply in the wrong place.

## What to add

On map start — and again whenever the folder is opened, because layers arrive
later — scan every `.shp` in the folder and:

1. **Decide the project's working zone by majority** among the layers that are
   *switched on*. Do not let the first layer that declares a zone decide it;
   one stray layer would then choose the whole project.
2. **Skip layers that are switched off.** They are not drawn, edited or navigated
   on, so a fault in one is not the user's problem today.
3. **Stay silent when the folder is consistent.** Only speak up about what is
   switched on and does not fit.

Findings worth reporting, and their severity:

| Finding | Severity |
|---------|----------|
| switched on, but no `.prj` | warning |
| switched on, `.prj` cannot be interpreted | warning |
| switched on, different UTM zone from the project | note |
| switched on, degrees in a projected folder | note |

That last one is a **note**, not a warning, for folders where it is deliberate.

## Two traps worth copying the fix for

- **Projected WKT embeds `GEOGCS` and `UNIT["Degree"]`.** A check for the text
  `"Degree"` will call a perfectly good UTM projection geographic. Test for the
  *absence* of `PROJECTION[` instead. This mistake made a correct `.prj` look
  like a wrong one and stopped a real correction happening.
- **When reprojecting a shape for display, recompute its bounding box.** Copying
  the parts while leaving the box in the old system leaves a shape whose parts
  are metres and whose extent is -125 degrees; the viewport filter then discards
  every reprojected feature and the layer silently renders empty.

## Why it is worth doing here too

In Forest Navigator this found a bug that had been hiding for a while: a point
layer read 7 records and kept 0, so MOB points were saved correctly and were
never visible on the map. Nothing errored. The same shape of fault is possible
wherever layers of mixed CRS are opened from a phone.

## The write side, which is easy to forget

Deciding the CRS for *display* is only half of it. Any code that **adds a feature**
must convert back into that layer's own CRS before writing. In Forest Navigator
the MOB and track paths did this and the edit/add-point path did not — so an
edit into a degrees layer would have written metres, roughly 100,000x too far
away, into a file that still opened cleanly. If this app writes points, check
every write path, not just the read path.