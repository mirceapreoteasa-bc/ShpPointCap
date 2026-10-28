package org.gcap.core

/**
 * Field-entry validation rules for the GCap field-ready collector.
 *
 * Every check returns an issue with a level; `RED` means the value is outside
 * the normal range and should be rendered in red, `ERROR` means the value is
 * invalid and must block saving that cell, `INFO`/`WARN` are advisory.
 *
 * Range rules (from the field spec):
 *  - species: 1-3 letter BC code, red if not on the accepted list
 *  - DBH: 7.4 .. 500.9 cm (diameter at 1.3 m)
 *  - CCls: d, c, h, i (and s); i (and s) rendered red
 *  - TCls: blank (alive) or 4 (dead); any other value is treated as alive
 *          but flagged red with a heads-up warning
 *  - Hgt: 1.5 .. 90.9 m; red when < 3.0 or > 55.0
 *  - Dist (SDist): 5.0 .. 99.9 m
 *  - VC: integer 1..5
 *  - CC: 15..95 in steps of 5; red when < 20 or > 70
 *  - Snags: 0..3000
 *  - Comments: <= 350 chars
 *  - Ocular comments: <= 100 chars and no commas
 *  - Trees per point: <= 40
 */
object FieldChecks {

    enum class Level { OK, RED, ERROR }

    data class Issue(val level: Level, val message: String)

    const val DBH_MIN = 7.4
    const val DBH_MAX = 500.9
    const val HGT_MIN = 1.5
    const val HGT_MAX = 90.9

    /** Acceptable BAF window: integers 3..30 are standard prism sizes; any
     *  value outside this range is flagged red (BC manual allows 3-decimal
     *  entry, so in-range decimals like 6.25 pass too). */
    const val BAF_MIN = 3.0
    const val BAF_MAX = 30.0

    // ---- User-configurable accepted ranges/lists ----------------------------
    // Hooks filled in by the Android app's Settings page at startup and after
    // every save. Null/empty leave the built-in rules above fully
    // authoritative, so gcap-core unit tests run unchanged.

    /** User DBH min..max (single accepted range). Null = built-in. */
    @Volatile
    var dbhUserRange: ClosedRange<Double>? = null

    /** User height min..max (single accepted range). Null = built-in. */
    @Volatile
    var hgtUserRange: ClosedRange<Double>? = null

    /** User age min..max (single accepted range). Null = built-in. */
    @Volatile
    var ageUserRange: IntRange? = null

    /** User BAF min..max (single accepted range). Null = built-in. */
    @Volatile
    var bafUserRange: ClosedRange<Double>? = null

    /** Extra user-accepted species codes, merged over species_130. */
    @Volatile
    var extraSpecies: Set<String> = emptySet()

    /** User-accepted NV (non-vegetated) codes. Empty = accept all. */
    @Volatile
    var nvAccept: Set<String> = emptySet()

    /** User-accepted HE (herb) codes. Empty = accept all. */
    @Volatile
    var heAccept: Set<String> = emptySet()

    /** User-accepted SE (surface expression) codes. Empty = accept all. */
    @Volatile
    var seAccept: Set<String> = emptySet()

    /** User-accepted SPM (site position meso) codes. Empty = accept all. */
    @Volatile
    var spmAccept: Set<String> = emptySet()

    /** Case-insensitive membership in the user's NV accepted list. */
    fun nvAccepts(code: String): Boolean = code.trim().uppercase() in nvAccept || code.trim().uppercase() in nvCodes

    /** Case-insensitive membership in the user's HE accepted list. */
    fun heAccepts(code: String): Boolean = code.trim().uppercase() in heAccept || code.trim().uppercase() in heCodes

    /** Case-insensitive membership in the user's SE accepted list. */
    fun seAccepts(code: String): Boolean = code.trim().uppercase() in seAccept || code.trim().uppercase() in seCodes

    /** Case-insensitive membership in the user's SPM accepted list. */
    fun spmAccepts(code: String): Boolean = code.trim().uppercase() in spmAccept || code.trim().uppercase() in spmCodes

    private const val MAX_TREES_PER_POINT = 40
    private const val COMMENTS_MAX = 350
    private const val OCULAR_MAX = 100

    private const val SP_DICT_RESOURCE = "org/gcap/species_130.txt"
    private const val NV_DICT_RESOURCE = "org/gcap/nv_codes.txt"
    private const val HE_DICT_RESOURCE = "org/gcap/he_codes.txt"
    private const val SE_DICT_RESOURCE = "org/gcap/se_codes.txt"
    private const val SPM_DICT_RESOURCE = "org/gcap/spm_codes.txt"

    /** Known BC species codes (parsed from species_130.txt), upper-cased. */
    val speciesCodes: Set<String> by lazy {
        val bytes = FieldChecks::class.java.classLoader
            ?.getResourceAsStream(SP_DICT_RESOURCE)
            ?.readBytes()
            ?: run {
                // Fallback: empty set means "accept anything" (validation still
                // flags unknown 1-3 letter codes as red-warnings).
                return@lazy emptySet()
            }
        val text = bytes.toString(Charsets.ISO_8859_1)
        text.lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim().uppercase() else null
            }
            .filter { it.isNotEmpty() && it.length <= 4 }
            .toSet()
    }

    /** Known BC non-vegetated NV codes (parsed from nv_codes.txt), upper-cased;
     *  authoritative default — user list unions over it (never removes). */
    val nvCodes: Set<String> by lazy { loadCodes("NV", NV_DICT_RESOURCE) }

    /** Known BC herb-cover HE codes (parsed from he_codes.txt), upper-cased. */
    val heCodes: Set<String> by lazy { loadCodes("HE", HE_DICT_RESOURCE) }

    /** Known BC surface-expression SE codes (parsed from se_codes.txt). */
    val seCodes: Set<String> by lazy { loadCodes("SE", SE_DICT_RESOURCE) }

    /** Known BC site-position-meso SPM codes (parsed from spm_codes.txt). */
    val spmCodes: Set<String> by lazy { loadCodes("SPM", SPM_DICT_RESOURCE) }

    /** Parses a `CODE : description` line resource into an upper-cased code set. */
    private fun loadCodes(tag: String, res: String): Set<String> {
        val bytes = FieldChecks::class.java.classLoader
            ?.getResourceAsStream(res)
            ?.readBytes()
            ?: return emptySet()
        return bytes.toString(Charsets.ISO_8859_1)
            .lineSequence()
            .mapNotNull { line ->
                val idx = line.indexOf(':')
                if (idx > 0) line.substring(0, idx).trim().uppercase() else null
            }
            .filter { it.isNotEmpty() && it.length <= 4 }
            .toSet()
    }

    /** True when `code` is a known BC species code (case-insensitive). */
    fun isKnownSpecies(code: String): Boolean =
        speciesCodes.contains(code.trim().uppercase())

    /** True when `code` looks like a plausible 1-3 letter species code. */
    fun hasSpeciesShape(code: String): Boolean =
        code.isNotEmpty() && code.length <= 3 && code.all { it in 'A'..'Z' || it in 'a'..'z' }

    // ---- Tree-level checks -------------------------------------------------

    fun species(code: String): Issue =
        when {
            !hasSpeciesShape(code) -> Issue(Level.ERROR, "Species must be 1-3 letters")
            !isKnownSpecies(code) -> Issue(Level.RED, "Species '$code' not on the list")
            else -> Issue(Level.OK, "")
        }

    fun dbh(value: Double?): Issue {
        if (value == null) return Issue(Level.RED, "Missing DBH")
        return when {
            value < DBH_MIN || value > DBH_MAX ->
                Issue(Level.ERROR, "DBH must be $DBH_MIN..$DBH_MAX cm")
            else -> Issue(Level.OK, "")
        }
    }

    fun dbhText(raw: String): Issue {
        val v = raw.trim().toDoubleOrNull() ?: return Issue(Level.ERROR, "DBH not a number")
        return dbh(v)
    }

    /** Acceptable crown-class codes: d dominant, c codominant, h intermediate, i/s suppressed. */
    val CROWN_CLASS_LETTERS = setOf("d", "c", "h", "i", "s")

    fun crownClass(c: String): Issue {
        val l = c.trim().lowercase()
        return when (l) {
            "" -> Issue(Level.OK, "")
            in CROWN_CLASS_LETTERS ->
                if (l in setOf("i", "s")) Issue(Level.RED, "Crown class $l (Category II)")
                else Issue(Level.OK, "")
            else -> Issue(Level.ERROR, "Crown class must be d, c, h, i or s")
        }
    }

    fun treeClass(t: String): Issue {
        val v = t.trim()
        return when {
            v.isEmpty() || v == "4" -> Issue(Level.OK, "")
            else -> Issue(Level.RED, "Unusual character in column TCls")
        }
    }

    fun height(value: Double?): Issue {
        if (value == null) return Issue(Level.OK, "")
        return when {
            value < HGT_MIN || value > HGT_MAX ->
                Issue(Level.ERROR, "Hgt must be $HGT_MIN..$HGT_MAX m")
            value < 3.0 || value > 55.0 -> Issue(Level.RED, "Unusual height $value m")
            else -> Issue(Level.OK, "")
        }
    }

    fun dist(value: Double?): Issue {
        if (value == null) return Issue(Level.OK, "")
        return if (value < 5.0 || value > 99.9)
            Issue(Level.ERROR, "Dist must be 5.0..99.9 m")
        else Issue(Level.OK, "")
    }

    fun ageCore(value: Int?): Issue {
        if (value == null) return Issue(Level.OK, "")
        return if (value < 1 || value > 900)
            Issue(Level.ERROR, "Age count out of range (1..900)")
        else Issue(Level.OK, "")
    }

    fun slope(value: Double?): Issue {
        if (value == null) return Issue(Level.OK, "")
        return if (value < -90.0 || value > 90.0)
            Issue(Level.ERROR, "Slope angle must be -90..90")
        else Issue(Level.OK, "")
    }

    /** Aggregated tree checks; empty list = tree is fine. */
    fun tree(t: Tree): List<Issue> =
        buildList {
            if (!t.number.toString().matches(Regex("\\d{1,2}")))
                add(Issue(Level.RED, "Tree number out of page range"))
            add(species(t.species))
            add(dbh(t.dbhCm))
            add(crownClass(t.crownClass))
            add(treeClass(t.tcls))
            add(height(t.heightM))
            add(dist(t.distM))
            add(ageCore(t.ageCore))
            add(slope(t.slopeDeg))
        }.filter { it.level != Level.OK }

    // ---- Point / sample-level checks --------------------------------------

    fun vc(value: Int?): Issue =
        if (value == null || value in 1..5) Issue(Level.OK, "")
        else Issue(Level.ERROR, "VC must be a whole number 1..5")

    fun cc(value: Double?): Issue {
        if (value == null) return Issue(Level.OK, "")
        val valid = value >= 15 && value <= 95 && (value % 5.0).let { it < 1e-9 || it > 5 - 1e-9 }
        if (!valid) return Issue(Level.ERROR, "CC must be 15..95 in steps of 5")
        return if (value < 20 || value > 70)
            Issue(Level.RED, "Unusual crown closure $value")
        else Issue(Level.OK, "")
    }

    fun snagsCount(value: Int?): Issue =
        if (value == null || value in 0..3000) Issue(Level.OK, "")
        else Issue(Level.ERROR, "Snags must be 0..3000")

    fun treesPerPoint(count: Int): Issue =
        if (count <= MAX_TREES_PER_POINT) Issue(Level.OK, "")
        else Issue(Level.RED, "More than $MAX_TREES_PER_POINT trees in a point")

    /** BAF-empty reminder (user-required special warning). */
    fun baf(gc: GroundCall): Issue =
        if (gc.capture.hasPrism || gc.capture.hasFixedRadius) Issue(Level.OK, "")
        else Issue(Level.RED, "BAF is empty - set the basal area factor (or radius)")

    fun comments(text: String): Issue =
        if (text.length <= COMMENTS_MAX) Issue(Level.OK, "")
        else Issue(Level.ERROR, "Comments up to $COMMENTS_MAX characters")

    fun ocular(text: String): Issue =
        when {
            text.length > OCULAR_MAX -> Issue(Level.ERROR, "Ocular comments up to $OCULAR_MAX characters")
            text.contains(',') -> Issue(Level.ERROR, "No commas allowed in ocular comments")
            else -> Issue(Level.OK, "")
        }

    // ---- Ocular ocular cover-% columns (NV%/HE%/SH%/BR%: Settings B9..E9) ----
    // Four 2-digit integer percent fields, each accepted 0..80. Mirrors the
    // numeric vc/cc seam (built-in accepted *range*, not a code dict) so the
    // solar accepted-value pane can render NV%/HE%/SH%/BR% as 0..80 with red
    // accent for a dropped value.

    private const val OCULAR_PCT_MIN = 0
    private const val OCULAR_PCT_MAX = 80

    fun nvPercent(value: Int?): Issue = coverPercent("NV", value)

    fun hePercent(value: Int?): Issue = coverPercent("HE", value)

    fun shPercent(value: Int?): Issue = coverPercent("SH", value)

    fun brPercent(value: Int?): Issue = coverPercent("BR", value)

    private fun coverPercent(label: String, value: Int?): Issue =
        if (value == null || value in OCULAR_PCT_MIN..OCULAR_PCT_MAX)
            Issue(Level.OK, "")
        else Issue(Level.ERROR, "$label% whole number 0..80")

    /** Sum of the four ocular cover-% columns (NV%+HE%+SH%+BR%, Settings
     *  B9+E9). The whole-sample aggregate must NOT exceed 85 — a true cover
     *  pane can't claim more than 85% total; bleed-dropping any single column
     *  is already the per-field 0..80 red, this RED flags the SUM that forces
     *  85 to be exceeded. Mirrors the snagsCount/aggregate seam family. */

    // ---- Ocular species family (Settings B16..I16 codes + B17..I17 pcts) ----
    /** Ocular species codes are accepted from the built-in species dict by
     *  {@link #ocularSpeciesUnion} below; the pct slot checks each 0..100
     *  and the ordering flag is pct[i] < pct[i+1] (descending, largest first).
     *  These ride the same "$ocular" seam family so a red arrives where the
     *  nvPercent/hePercent/shPercent/brPercent reds already land. */

    /** ORDERING: each pct[i] must be >= pct[i+1] (descending cover, largest
     *  species first). Empty/null slots OK'd. Mirrors the ocularCoverSum
     *  (sum over the 8 pcts is checked by container UI, not here). */
    fun ocularSpeciesOrdering(pcts: List<Int?>): Issue {
        if (pcts.size != 8) return Issue(Level.ERROR, "Ocular species need 8 pct slots")
        for (i in 0 until pcts.size - 1) {
            val a = pcts[i] ?: continue
            val b = pcts[i + 1] ?: continue
            if (a < b) {
                return Issue(Level.ERROR, "Ocular species pct must descend (slot ${i + 1} is smaller than slot ${i + 2})")
            }
        }
        return Issue(Level.OK, "")
    }

    /** RANGE: each pct slot is a whole number 0..100. */
    fun ocularSpeciesPct(value: Int?): Issue =
        if (value == null || value in 0..100) Issue(Level.OK, "")
        else Issue(Level.ERROR, "Ocular species pct whole number 0..100")

    /** CODES: union with the built-in species dict (same seam as speciesAccept). */
    @Volatile
    var ocularSpeciesExtra: Set<String> = emptySet()

    fun ocularSpeciesKnown(code: String): Boolean =
        code.trim().uppercase() in speciesCodes || code.trim().uppercase() in ocularSpeciesExtra

    private const val OCULAR_SUM_MAX = 85

    fun ocularCoverSum(nvPct: Int?, hePct: Int?, shPct: Int?, brPct: Int?): Issue {
        val sum = listOfNotNull(nvPct, hePct, shPct, brPct).sum()
        return if (sum > OCULAR_SUM_MAX)
            Issue(Level.ERROR, "Coverage sum > 85")
        else Issue(Level.OK, "")
    }

    /** Usual sample names: a 3-4 digit number, optionally followed by "-" and/or
     *  one lowercase letter (e.g. 0508, 0508-, 0508b, 0508-a). */
    val SAMPLE_NAME_STD = Regex("^[0-9]{3,4}-?[a-z]?$")

    /** RED advisory when a save-as name is not the ordinary field format. */
    fun sampleName(name: String): Issue =
        if (SAMPLE_NAME_STD.matches(name)) Issue(Level.OK, "")
        else Issue(Level.RED, "Unusual name")

    const val RADIUS_MIN = 1.00
    const val RADIUS_MAX = 200.00

    fun radiusApproved(radius: Double?): Issue =
        if (radius == null || radius in RADIUS_MIN..RADIUS_MAX) Issue(Level.OK, "")
        else Issue(Level.RED, "RADIUS $radius outside the accepted range $RADIUS_MIN..$RADIUS_MAX")
    fun bafApproved(baf: Double?): Issue =
        if (baf == null || baf in BAF_MIN..BAF_MAX) Issue(Level.OK, "")
        else Issue(Level.RED, "BAF $baf outside the accepted range $BAF_MIN..$BAF_MAX")

    /** All sample-level issues. */
    fun sample(gc: GroundCall): List<Issue> =
        buildList {
            add(baf(gc))
            if (gc.capture.baf != null) add(bafApproved(gc.capture.baf))
            if (gc.capture.radiusM != null) add(radiusApproved(gc.capture.radiusM))
            for ((pt, n) in gc.points()) add(treesPerPoint(n.size))
            add(comments(gc.comment))
            add(ocular(gc.capture.ocularComments))
        }.filter { it.level != Level.OK }
}