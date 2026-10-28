package org.gcap.core

import kotlin.io.path.Path
import kotlin.io.path.readBytes

/**
 * GCap ground call .dat parser (v5.1.1 schema). Reads the CRLF text `.dat`
 * files produced by the Windows Mobile GCap.vb app. See gcap_dat/schema.md (the
 * Python reference project) for the full blue-print.
 */
object DatParser {

    const val SEP = "\r\n"
    const val TREE_RECORD_LINES = 16

    // Tree record layout (fixed 16 lines), offsets within the record:
    //   0   : tree number (restarts at 1 for every point)
    //   1   : species code
    //   2   : DBH (cm)
    //   3   : TCls (blank/4; legacy files 054/308 stored CCls here)
    //   4-6 : spare
    //   7   : Hgt (m)
    //   8   : Dist (measured slope distance, m)
    //   9   : Age1.3 core count
    //   10  : AgeCor (years to breast height)
    //   11  : Slope (vertical angle, deg)
    //   12  : spare
    //   13  : CCls crown class letter
    //   14  : blank
    //   15  : measured flag ('1')
    // Records are CONTIGUOUS over the whole tree zone (no separators). The
    // zone starts right after header line 148, which is a literal '1' in every
    // observed file. A point boundary is detected by the tree number returning
    // to 1 after a number > 1.
    // Canonical slot index (offset - 3) used by typed fields.
    private const val IDX_TCLS = 0       // offset 3
    private const val IDX_HGT = 4        // offset 7
    private const val IDX_DIST = 5       // offset 8
    private const val IDX_AGE = 6        // offset 9
    private const val IDX_AGECOR = 7     // offset 10
    private const val IDX_SLOPE = 8      // offset 11
    private const val TREE_ZONE_START = 149

    private val intRe = Regex("-?\\d+")
    private val floatRe = Regex("-?\\d+(?:\\.\\d+)?")
    private val speciesRe = Regex("[A-Za-z]{1,4}")

    private fun isInt(s: String) = intRe.matches(s)
    private fun isFloat(s: String) = floatRe.matches(s)
    private fun isSpeciesCode(s: String) = speciesRe.matches(s)

    private fun parseComposition(raw: String): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        for (tok in raw.split(Regex("\\s+"))) {
            val m = Regex("([A-Za-z]+)(\\d+(?:\\.\\d+)?)").find(tok)
            if (m != null) out[m.groupValues[1]] = m.groupValues[2].toDouble()
        }
        return out
    }

    private fun readyTreeRecord(block: List<String>): Tree {
        val num = block[0].toIntOrNull() ?: 0
        val species = block.getOrElse(1) { "" }
        val dbhRaw = block.getOrNull(2)?.takeIf { it.isNotEmpty() }
        val dbh = dbhRaw?.toDoubleOrNull()
        val slots = block.subList(3, minOf(13, block.size))
        val crown = block.getOrNull(13) ?: ""
        val measured = (block.size > 15 && block[15] == "1")

        fun slot(i: Int): String? = slots.getOrNull(i)?.takeIf { it.isNotEmpty() }

        val tcls = slot(IDX_TCLS)?.takeIf { it == "4" } ?: ""
        val height = slot(IDX_HGT)?.toDoubleOrNull()
        val dist = slot(IDX_DIST)?.toDoubleOrNull()
        val ageCore = slot(IDX_AGE)?.toIntOrNull()
        val ageCor = slot(IDX_AGECOR)?.toDoubleOrNull()
        val slope = slot(IDX_SLOPE)?.toDoubleOrNull()

        return Tree(
            number = num,
            species = species,
            dbhCm = dbh,
            dbhRaw = dbhRaw,
            crownClass = crown,
            measured = measured,
            slots = slots,
            tcls = tcls,
            heightM = height,
            distM = dist,
            ageCore = ageCore,
            ageCor = ageCor,
            slopeDeg = slope,
        )
    }

    private fun looksLikeTreeStart(lines: List<String>, idx: Int): Boolean {
        if (idx + 2 >= lines.size) return false
        if (!isInt(lines[idx])) return false
        if (!isSpeciesCode(lines[idx + 1])) return false
        if (!isFloat(lines[idx + 2])) return false
        return true
    }

    private fun parseTreeZone(lines: List<String>, start: Int): List<Tree> {
        val trees = mutableListOf<Tree>()
        var i = start
        val n = lines.size
        var point = 1
        var prevNum = 0
        while (i + 1 < n && looksLikeTreeStart(lines, i)) {
            val num = if (i < n && intRe.matches(lines[i])) lines[i].toInt() else 0
            if (prevNum > 1 && num == 1) point += 1
            prevNum = num
            val block = (lines.subList(i, minOf(i + TREE_RECORD_LINES, n)) +
                    List(TREE_RECORD_LINES) { "" }).take(TREE_RECORD_LINES)
            trees.add(readyTreeRecord(block).copy(point = point))
            i += TREE_RECORD_LINES
        }
        return trees
    }

    fun parseText(text: String): GroundCall {
        var raw = text
        if (raw.endsWith(SEP)) raw = raw.removeSuffix(SEP)
        val lines = raw.split(SEP)

        fun line(idx: Int): String = lines.getOrElse(idx) { "" }

        val ecology = linkedMapOf<String, Any?>(
            "line8" to line(8),
            "line9" to line(9),
            "smr" to line(10),
            "snr" to line(11),
            "line12" to line(12),
            "landcover" to line(13),
            "slope" to line(14),
            "surface" to line(15),
            "cover_bytes" to (16..19).map { line(it) },
            "line21" to line(21),
            "layer_counts" to listOf(line(51), line(54), line(57)),
            "layer_cover_a" to listOf(line(66), line(69), line(72)),
            "layer_cover_b" to listOf(line(81), line(84), line(87)),
            "layer_cover_c" to listOf(line(96), line(99), line(102)),
        )

        fun floatOf(i: Int): Double? = line(i).toDoubleOrNull()
        val baf = line(23).trim().toDoubleOrNull()?.takeIf { it > 0 }

        val compositionRaw = line(126)
        val pageMax = line(141).toIntOrNull() ?: 10

        return GroundCall(
            version = line(0),
            sampleId = line(2),
            mapSheet = line(4),
            sampleType = line(5),
            entryDate = line(6),
            ecology = ecology,
            comment = line(111),
            compositionRaw = compositionRaw,
            composition = parseComposition(compositionRaw),
            easting = floatOf(142),
            northing = floatOf(143),
            elevation = floatOf(144),
            trees = parseTreeZone(lines, TREE_ZONE_START),
            capture = CaptureMeta(baf = baf),
            headerLines = lines.take(149),
        )
    }

    fun parseFile(path: String): GroundCall =
        parseText(Path(path).readBytes().toString(Charsets.ISO_8859_1))
}