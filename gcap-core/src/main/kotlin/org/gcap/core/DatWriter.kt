package org.gcap.core

import kotlin.io.path.Path
import kotlin.io.path.writeText

/**
 * Writes ground call data back to GCap v5.1.1 `.dat` text format (CRLF, one
 * value per line). The 148-line header is an exact template (from a reference
 * file) overlaid with the capture fields; tree records are 16 lines each,
 * grouped by point, with the typed fields overlaid onto their canonical slots
 * so untouched data round-trips byte-exactly.
 */
object DatWriter {

    /** Reference header template lines 0..148, loaded from resources. */
    private val headerTemplate: List<String> by lazy {
        val raw = DatWriter::class.java.classLoader
            ?.getResourceAsStream("org/gcap/header_clean.txt")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("missing header_clean.txt resource")
        require(!raw.isEmpty()) { "header template loaded empty" }
        raw.split("\n")
    }

    init {
        require(headerTemplate.size == 149) { "header template must be 149 lines" }
    }

    // Header line indices for the variable capture fields.
    private const val LINE_SAMPLE_ID = 2
    private const val LINE_MAP_SHEET = 4
    private const val LINE_SAMPLE_TYPE = 5
    private const val LINE_ENTRY_DATE = 6
    private const val LINE_BAF = 23
    private const val LINE_COMMENT = 111
    private const val LINE_COMPOSITION = 126
    private const val LINE_EASTING = 142
    private const val LINE_NORTHING = 143
    private const val LINE_ELEVATION = 144
    // Dat line 142 (index 141) carries the UTM zone number ("10" in every
    // observed file). Old files inherit it via headerLines; new files start
    // from a blank template, so it must be written explicitly (see below).
    private const val LINE_UTM_ZONE = 141
    private const val LINE_FIRST_MARKER = 148

    // Per-point header cells: L66/69/72 = cover A %, L81/84/87 = cover B %,
    // L96/99/102 = per-point tree counts (stride 3).
    private val COVER_A = listOf(66, 69, 72)
    private val COVER_B = listOf(81, 84, 87)
    private val COUNTS = listOf(96, 99, 102)

    private fun formatNumber(v: Double): String {
        val l = v.toLong()
        return if (v == l.toDouble()) l.toString() else v.toString()
    }

    private fun formatDbh(dbhCm: Double?): String {
        if (dbhCm == null) return ""
        val l = dbhCm.toLong()
        return if (dbhCm == l.toDouble()) l.toString() else dbhCm.toString()
    }

    /** Render a GroundCall to GCap .dat text. File text ends with CRLF. */
    fun render(gc: GroundCall): String {
        // Inherit the source file's own header (edits) or use the canonical
        // reference template (new samples).
        val h = (gc.headerLines ?: headerTemplate).toMutableList()

        fun set(i: Int, v: String?) {
            if (v != null && v.isNotEmpty()) h[i] = v
        }

        if (gc.sampleId.isNotEmpty()) set(LINE_SAMPLE_ID, gc.sampleId)
        if (gc.mapSheet.isNotEmpty()) set(LINE_MAP_SHEET, gc.mapSheet)
        if (gc.sampleType.isNotEmpty()) set(LINE_SAMPLE_TYPE, gc.sampleType)
        if (gc.entryDate.isNotEmpty()) set(LINE_ENTRY_DATE, gc.entryDate)

        val eco = gc.ecology
        val ecoLines = listOf(8, 9, 10, 11, 12, 13, 14, 15, 21)
        val ecoKeys = listOf(
            "line8", "line9", "smr", "snr", "line12", "landcover",
            "slope", "surface", "line21",
        )
        for ((ln, key) in ecoLines.zip(ecoKeys)) {
            (eco[key] as? String)?.let { set(ln, it) }
        }
        // Cover/by-layer percents live at header lines 16-19 (cover_bytes).
        (eco["cover_bytes"] as? List<*>)?.forEachIndexed { idx, v ->
            set(16 + idx, v?.toString())
        }

        // BAF lives at header line 23 (editor line 24), confirmed by crew.
        if (gc.capture.baf != null) set(LINE_BAF, formatNumber(gc.capture.baf))
        else h[LINE_BAF] = ""

        set(LINE_COMMENT, gc.comment)
        set(LINE_COMPOSITION, gc.compositionRaw)
        // UTM zone digits ("10N" -> "10"): chosen point first, else capture zone.
        // Edits keep the old value via headerLines; new files get it written here
        // so their layout matches the old files (zone/easting/northing lines).
        val zoneDigits = (gc.capture.utmChosenIndex?.let { gc.capture.utmPoints.getOrNull(it) }?.zone
            ?: gc.capture.utmPoints.lastOrNull()?.zone
            ?: gc.capture.utmZone).filter { it.isDigit() }
        if (zoneDigits.isNotEmpty()) set(LINE_UTM_ZONE, zoneDigits)
        gc.easting?.let { set(LINE_EASTING, formatNumber(it)) }
        gc.northing?.let { set(LINE_NORTHING, formatNumber(it)) }
        gc.elevation?.let { set(LINE_ELEVATION, formatNumber(it)) }

        // per-point header cells (stride 3): count for each present point;
        // cells for missing points are blanked so a reduced file (after point
        // deletion) does not carry stale counts for points that no longer exist.
        val pts = gc.points()
        val n = pts.size
        for (i in COUNTS.indices) {
            h[COUNTS[i]] = pts[i + 1]?.size?.toString() ?: ""
        }

        // tree records: contiguous 16-line blocks following header line 148
        // (line 148 always holds the literal '1'). Point groups are marked only
        // by tree numbers restarting at 1; no separator lines exist. GCap
        // quirk: the measured flag is written when a next record closes the
        // tree, so the LAST record of the file has no flag line.
        val out = h.toMutableList()
        out[LINE_FIRST_MARKER] = "1"
        val allTrees = (1..n).flatMap { pts[it] ?: emptyList() }
        for ((idx, t) in allTrees.withIndex()) {
            val isLast = idx == allTrees.size - 1
            val rec = MutableList(16) { "" }
            rec[0] = t.number.toString()
            rec[1] = t.species
            rec[2] = t.dbhRaw ?: formatDbh(t.dbhCm)
            val slots = t.slots.take(10)
            for (i in slots.indices) rec[3 + i] = slots[i]
            // typed overlays preserve the original slot text while the value is
            // unchanged (e.g. "27.0" stays "27.0"), and write canonical text
            // only for genuinely new/changed values.
            overlayTyped(rec, 3, t.tcls.takeIf { it.isNotEmpty() }, { it }, slots)
            overlayTyped(rec, 7, t.heightM, ::formatNumber, slots)
            overlayTyped(rec, 8, t.distM, ::formatNumber, slots)
            overlayTyped(rec, 9, t.ageCore, { it.toString() }, slots)
            overlayTyped(rec, 10, t.ageCor, ::formatNumber, slots)
            overlayTyped(rec, 11, t.slopeDeg, ::formatNumber, slots)
            overlay(rec, 13, t.crownClass)
            rec[14] = ""
            if (!isLast) rec[15] = if (t.measured) "1" else ""
            // GCap quirk: the last record of the file has no measured-flag line.
            out.addAll(rec.take(if (isLast) 15 else 16))
        }
        return out.joinToString("\r\n") + "\r\n"
    }

    private fun overlay(rec: MutableList<String>, offset: Int, value: String?) {
        if (value != null && value.isNotEmpty()) rec[offset] = value
    }

    /** Overlay a typed slot value, preserving the original raw text when the
     *  value is unchanged, and only writing canonical formatting otherwise. */
    private inline fun <T> overlayTyped(
        rec: MutableList<String>,
        offset: Int,
        value: T?,
        format: (T) -> String,
        rawSlots: List<String>,
    ) {
        val raw = rawSlots.getOrNull(offset - 3)
        if (value == null) return
        val canon = format(value)
        if (raw != null && raw.isNotEmpty() && raw != canon && raw.toDoubleOrNull()?.let {
                it == (value as? Number)?.toDouble()
            } == true) {
            rec[offset] = raw        // untouched: keep original formatting
        } else {
            rec[offset] = canon
        }
    }

    fun write(gc: GroundCall, path: String) {
        Path(path).writeText(render(gc), Charsets.ISO_8859_1)
    }
}