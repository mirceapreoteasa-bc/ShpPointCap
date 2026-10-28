package org.gcap.core

/**
 * Per-species site-index metadata for the age-correction dialog.
 *
 * The DLL computes years-to-breast-height (Y2BH) from the tree's species and
 * an estimated site index (projected dominant height at age 50, metres), and
 * its own engine is indexed by a per-species integer code. This object is the
 * app-facing twin of that mapping:
 *
 *  - `indexFor(name)`        : BC species name -> DLL species-code index
 *                              (the row key used by `Y2bhTable` and by the
 *                               real Sindex_Y2BH export),
 *  - `hasCurve(name)`        : true when that species appears in the DLL's
 *                              breast-height table at all (the only ones with
 *                              a curve; unknown/blank maps to false),
 *  - `guidance(name)`        : the DLL-observed default site index plus the
 *                              species' empirically-plausible (min, max)
 *                              clamp range, used to pre-fill and bound the
 *                              age-correction dialog.
 */
object SpeciesSiteIdx {

    private const val MISSING = -1

    /**
     * BC species code -> DLL species-code index (row in Y2bhTable / Sindex).
     * Index numbering is the official Sindex DLL v1.54 (sindex64.dll), verified
     * 2026-10-26 with SiteToolsCmd and the DLL's Sindex_SpecCode / Sindex_SpecName.
     * Codes not listed have no curve in this app. YC is the DLL code "Cy".
     */
    private val NAME_TO_INDEX = mapOf(
        "A" to 0, "AC" to 3, "ACB" to 4, "ACT" to 5, "AD" to 6, "AT" to 8, "AX" to 9, "B" to 10,
        "BA" to 11, "BB" to 12, "BC" to 13, "BG" to 14, "BL" to 16, "BM" to 17, "BP" to 18,
        "CW" to 22, "D" to 26, "DG" to 27, "DM" to 28, "DR" to 29, "EA" to 31, "EB" to 32,
        "EP" to 34, "ES" to 35, "EW" to 36, "EXP" to 37, "FD" to 40, "FDC" to 41, "FDI" to 42,
        "G" to 43, "GP" to 44, "GR" to 45, "H" to 46, "HM" to 47, "HW" to 48, "HXM" to 51,
        "J" to 54, "JR" to 55, "K" to 57, "KC" to 58, "L" to 59, "LA" to 60, "LD" to 61, "LS" to 63,
        "LT" to 64, "LW" to 65, "M" to 66, "MB" to 67, "ME" to 68, "MN" to 69, "MR" to 70,
        "MS" to 71, "MV" to 72, "OA" to 73, "OB" to 74, "OC" to 75, "OD" to 76, "OE" to 77,
        "OF" to 78, "OG" to 79, "OH" to 80, "OI" to 81, "P" to 84, "PA" to 85, "PF" to 86,
        "PJ" to 87, "PL" to 88, "PLC" to 89, "PLI" to 90, "PM" to 91, "PR" to 92, "PS" to 93,
        "PW" to 94, "PXJ" to 95, "PY" to 96, "Q" to 97, "QE" to 98, "QG" to 99, "QW" to 100,
        "R" to 101, "RA" to 102, "S" to 103, "SA" to 104, "SB" to 105, "SE" to 106, "SN" to 108,
        "SS" to 109, "SW" to 110, "SX" to 111, "SXB" to 112, "SXE" to 113, "SXL" to 114,
        "SXS" to 115, "SXW" to 116, "SXX" to 117, "T" to 118, "TW" to 119, "U" to 120, "UA" to 121,
        "UP" to 122, "V" to 123, "VB" to 124, "VP" to 125, "VS" to 126, "VV" to 127, "W" to 128,
        "WA" to 129, "YC" to 25,
    )

    private val DEFAULT_GUIDANCE = mapOf(
        "FDC" to Triple(27.5, 15.0, 40.0),
        "CW" to Triple(23.5, 12.0, 35.0),
        "HW" to Triple(20.0, 10.0, 30.0),
        "ACT" to Triple(32.5, 20.0, 45.0),
        "DR" to Triple(27.5, 18.0, 37.0),
        "YC" to Triple(16.0, 7.0, 25.0),
        "BA" to Triple(20.0, 10.0, 30.0),
        "HM" to Triple(16.5, 8.0, 25.0),
        "PLC" to Triple(12.5, 5.0, 20.0),
        "PLI" to Triple(21.0, 12.0, 30.0),
        "BL" to Triple(15.0, 5.0, 25.0),
        "BG" to Triple(30.0, 15.0, 45.0),
        "SS" to Triple(27.5, 15.0, 40.0),
        "SX" to Triple(22.5, 10.0, 35.0),
        "MB" to Triple(20.0, 10.0, 30.0),
        "PA" to Triple(11.0, 4.0, 20.0),
    )

    private val OTHER_CODE_DEFAULT = 20.0
    private val OTHER_CODE_MIN = 4.0
    private val OTHER_CODE_MAX = 60.0

    fun indexFor(name: String): Int = NAME_TO_INDEX[name.trim().uppercase()] ?: MISSING

    /** Species with breast-height growth curves (the only ones the DLL can age). */
    fun hasCurve(name: String): Boolean = indexFor(name) >= 0

    /** Cyrillic/default guidance (SI, minSI, maxSI) for a species, or null. */
    fun guidance(name: String): Triple<Double, Double, Double>? {
        val k = name.trim().uppercase()
        return DEFAULT_GUIDANCE[k] ?: NAME_TO_INDEX[k]?.let { Triple(OTHER_CODE_DEFAULT, OTHER_CODE_MIN, OTHER_CODE_MAX) }
    }
}
