package org.gcap.core

import kotlin.math.PI

/**
 * One tree record. The raw 10 measurement slots are kept for byte fidelity;
 * the typed fields below are derived from / merged into the canonical slot
 * offsets (see DatParser) so untouched data round-trips exactly.
 *
 * Canonical slot map (offsets within the 16-line record):
 *   0 = #, 1 = species, 2 = DBH
 *   3 = TCls (blank/4)           4..6 = spare
 *   7 = Hgt (m)                  8 = Dist (slope distance, m)
 *   9 = Age1.3 core count       10 = AgeCor (years to breast height)
 *  11 = Slope (vertical angle)  12 = spare
 *  13 = CCls                    14 = blank   15 = measured flag
 *
 * `hgtTopDeg/hgtBotDeg/hgtSDistM/hgtCorM` are the clinometer input panel
 * used to compute the stored height (they are UI inputs, not persisted in the
 * `.dat`).
 */
data class Tree(
    val number: Int,
    val species: String = "",
    val dbhCm: Double? = null,
    /** Original DBH text from the source file, preserved for byte fidelity. */
    val dbhRaw: String? = null,
    val crownClass: String = "",
    val measured: Boolean = false,
    val slots: List<String> = emptyList(),
    /** Measurement point id (1..n); tree numbers restart at 1 per point. */
    val point: Int = 1,
    /** Tree class: blank (live) or "4" (dead). Dead trees do not count in
     *  composition, basal area or density, but count as dead trees. */
    val tcls: String = "",
    /** Tree layer (usually "1"). */
    val layer: String = "",
    /** Tree height (m), slot 7. */
    val heightM: Double? = null,
    /** Measured slope distance to the tree (m), slot 8. */
    val distM: Double? = null,
    /** Vertical angle of the measured distance (degrees), slot 11. */
    val slopeDeg: Double? = null,
    /** Age ring/core count at 1.3 m (integer), slot 9. */
    val ageCore: Int? = null,
    /** Years to grow from germination to breast height, slot 10. */
    val ageCor: Double? = null,
    /** Clinometer angle to tree top (degrees). UI input, not persisted. */
    val hgtTopDeg: Double? = null,
    /** Clinometer angle to tree base (degrees). UI input, not persisted. */
    val hgtBotDeg: Double? = null,
    /** Slope distance used for the height calculation (m). UI input. */
    val hgtSDistM: Double? = null,
    /** Correction (m) when the base angle was taken above the true base. UI input. */
    val hgtCorM: Double? = null,
) {
    val basalAreaM2: Double
        get() {
            val d = dbhCm ?: return 0.0
            val r = d / 200.0
            return PI * r * r
        }

    /** Live tree (not TCls=4). */
    val isLive: Boolean get() = tcls != "4"

    val isDead: Boolean get() = tcls == "4"

    /** Auxiliary OUT tree: numbered 91..99 (recorded for age/height but never
     *  counted in the alive tally). */
    val isOutNumber: Boolean get() = number in 91..99

    /** Total age = core count at 1.3 m + years to breast height. */
    val totalAge: Double?
        get() {
            val c = ageCore ?: return null
            return c + (ageCor ?: 0.0)
        }

    /** Corrected age as shown/recorded in the grid and ODS (core + YTBH), half-up. */
    val shownAge: Int?
        get() = totalAge?.let { Math.round(it).toInt() } ?: ageCore

    /** Crown class upper-cased for comparison/validation. */
    val crownClassUpper: String get() = crownClass.uppercase()
}

/**
 * Extended capture fields that GCap stores outside the confirmed `.dat` slots
 * (BAF, VC, CC, snags, etc.). These are round-tripped by the app through a
 * sidecar `<id>.gc.json` next to the `.dat`; they are NOT written into the
 * `.dat` body so the file stays readable by every existing office tool.
 */
/**
 * One UTM point captured from the device GPS fix (WGS84 → UTM easting/
 * northing in the capture's zone). P1..P3 and Tie_Point each produce one
 * instance; the chosen (or last-eligible) instance's x,y pair is what the
 * `.dat` lines 142/143 pen — Tie_Point is only ever extra sidecar/export data.
 */
data class UtmPoint(
    /** "P1" / "P2" / "P3" / "Tie_Point". */
    val id: String = "",
    /** UTM easting / X (m) at the fix. */
    val easting: Double? = null,
    /** UTM northing / Y (m) at the fix. */
    val northing: Double? = null,
    /** UTM zone (e.g. "10N") at the fix. */
    val zone: String = "",
    /** Elevation (m) at the fix. */
    val elevM: Double? = null,
    /** WGS84 latitude that produced this UTM pair. */
    val latDeg: Double? = null,
    /** WGS84 longitude that produced this UTM pair. */
    val lonDeg: Double? = null,
    /** Source: "Field" or "Intended". */
    val coordSource: String = "",
    /** When the point was captured (wall-clock ms). */
    val capturedAtMs: Long = 0L,
)

data class CaptureMeta(
    /** Basal area factor (metric prism). Any value in 3..30 is accepted;
     *  values outside are flagged (see FieldChecks.bafApproved). */
    val baf: Double? = null,
    /** Fixed-radius plot radius (m) when used instead of a prism. */
    val radiusM: Double? = null,
    /** Conflict choice when both BAF and RADIUS are entered ("BAF" or "RADIUS"). */
    val conflictChoice: String = "",
    /** Per-point standing snag counts (point id -> count, 0..3000). */
    val snags: Map<Int, Int> = emptyMap(),
    /** Per-point dead stem counts (TCls=4). */
    val deadStems: Map<Int, Int> = emptyMap(),
    /** Ocular comments (Gap, 100 chars, no commas). */
    val ocularComments: String = "",
    /** Ocular species 8-slot composition (Settings B16..I16 code row): each
     *  entry is the 1-letter ocular cover-grade for slot i (S1..S8, largest
     *  cover first), union with the built-in species dict. Empty string =
     *  slot unset. */
    val ocularSpeciesCodes: List<String> = emptyList(),
    /** Ocular species 8-slot cover-% (Settings B17..I17 pct row): each pct is
     *  a whole number 0..100; the FAMILY must be ordered by pct descending
     *  (ocularSpeciesOrdering) — any pct[i] < pct[i+1] is RED (mirrors the
     *  ocularCoverSum seam). */
    val ocularSpeciesPcts: List<Int> = emptyList(),
    /** Ocular species codes (8 slots, largest first). Kept empty until the
     *  Comments page fills the 8-code row — union with the built-in species
     *  dict is FieldChecks' job, never removal. */
    val ocularSpecies: List<String> = emptyList(),
    /** Ocular species cover percents (8 slots, aligned to [ocularSpecies],
     *  descending — pct[i] >= pct[i+1]; each 0..100). Empty until filled. */
    val ocularSpeciesPct: List<Int> = emptyList(),
    /** Call type: XGV-3 / XGV-1 / XGO. */
    val callType: String = "",
    /** Coast or Interior. */
    val location: String = "",
    /** UTM zone letter/number (e.g. "10N"). */
    val utmZone: String = "",
    /** Coordinate source: Field or Intended. */
    val coordSource: String = "",
    /** Polygon id (Summary B2). */
    val poly: String = "",
    /** Camera/photo file id (Summary B6). */
    val photo: String = "",
    /** Report the 3rd most-prominent species as the 2nd ("use third as
     *  second"): swaps slots 2 and 3 of the summary species composition. */

    /** One captured UTM point (P1..P3 or Tie_Point). Ride only the sidecar /
     *  exports; the chosen point's x,y is the only one that is ever pen-eligible
     *  for the .dat lines 142/143 (easting/northing). */
    var utmPoints: List<UtmPoint> = emptyList(),
    /** Index into [utmPoints] chosen by the user (Use_P… row). When null the
     *  LAST collected eligible point is used. Tie_Point is never eligible. */
    var utmChosenIndex: Int? = null,
    /** The Tie_Point (extra data only — never written into the .dat x,y). */
    var utmTiePoint: UtmPoint? = null,
    val use3rdAsSecond: Boolean = false,
) {
    val hasPrism: Boolean get() = baf != null && baf!! > 0.0
    val hasFixedRadius: Boolean get() = radiusM != null && radiusM!! > 0.0
}

/** Parsed contents of one .dat file. */
data class GroundCall(
    val version: String = "",
    val sampleId: String = "",
    val mapSheet: String = "",
    val sampleType: String = "",
    val entryDate: String = "",
    val ecology: Map<String, Any?> = emptyMap(),
    val comment: String = "",
    val compositionRaw: String = "",
    val composition: Map<String, Double> = emptyMap(),
    val easting: Double? = null,
    val northing: Double? = null,
    val elevation: Double? = null,
    val trees: List<Tree> = emptyList(),
    /** Extended capture fields (kept outside the .dat body, see CaptureMeta). */
    val capture: CaptureMeta = CaptureMeta(),
    /** Raw header lines 0..148 from the source file. When present the writer
     *  inherits the file's own header (edits preserve every header value);
     *  otherwise the canonical reference template is used (new samples). */
    val headerLines: List<String>? = null,
) {
    /** Group trees by point id (1..n in file order). */
    fun points(): Map<Int, List<Tree>> = trees.groupBy { it.point }

    val nPoints: Int
        get() = trees.maxOfOrNull { it.point } ?: 0

    val liveTrees: List<Tree> get() = trees.filter { it.isLive }

    val deadTrees: List<Tree> get() = trees.filter { it.isDead }

    /** Stems/ha averaged across points (each point is an independent viewpoint). */
    fun liveStemsPerHa(plotAreaM2: Double? = null): Double {
        if (plotAreaM2 == null || plotAreaM2 <= 0.0 || liveTrees.isEmpty()) return 0.0
        val perPt = points().values
            .map { it.count { t -> t.isLive } * 10000.0 / plotAreaM2 }
        return perPt.sum() / perPt.size
    }

    /** BA/ha (live trees) averaged across points for fixed-radius plots. */
    fun liveBasalAreaHa(plotAreaM2: Double? = null): Double {
        if (plotAreaM2 == null || plotAreaM2 <= 0.0 || liveTrees.isEmpty()) return 0.0
        val perPt = points().values
            .map { it.filter { t -> t.isLive }.sumOf { t -> t.basalAreaM2 } * 10000.0 / plotAreaM2 }
        return perPt.sum() / perPt.size
    }
}