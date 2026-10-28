package org.gcap.core

/**
 * BC MFLNRO/GCap per-species typical site-index ranges (metres at age 50 --
 * the DLL's "Site Index" = dominant height at 50 yr, breast-height basis).
 *
 * Used to pre-fill and clamp the user-supplied estimated site index when the
 * app ages a tree (the age-to-breast-height table needs an SI per species).
 */
object SiteIdxRanges {

    /** Default (suggested) site index per BC species name (upper case). */
    private val DEFAULT_SI = mapOf(
        "FDC" to 27.5, "CW" to 23.5, "HW" to 20.0,
        "ACT" to 32.5, "DR" to 27.5, "YC" to 16.0,
        "BA" to 20.0, "HM" to 16.5, "PLC" to 12.5,
        "PLI" to 21.0, "BL" to 15.0, "BG" to 30.0,
        "SS" to 27.5, "SX" to 22.5, "MB" to 20.0, "PA" to 11.0,
        // common non-SIU species (not in the DLL's breast-height list) use a
        // conservative default so the dialog still opens with a sane value.
        "FDC2" to 27.5, "FL" to 24.0, "VA" to 15.0, "YC" to 16.0,
    )

    /** Inclusive [min, max] site index per species. */
    private val RANGE = mapOf(
        "FDC" to (15.0 to 40.0), "CW" to (12.0 to 35.0), "HW" to (10.0 to 30.0),
        "ACT" to (20.0 to 45.0), "DR" to (18.0 to 37.0), "YC" to (7.0 to 25.0),
        "BA" to (10.0 to 30.0), "HM" to (8.0 to 25.0), "PLC" to (5.0 to 20.0),
        "PLI" to (12.0 to 30.0), "BL" to (5.0 to 25.0), "BG" to (15.0 to 45.0),
        "SS" to (15.0 to 40.0), "SX" to (10.0 to 35.0), "MB" to (10.0 to 30.0),
        "PA" to (4.0 to 18.0),
    )

    /** Suggested estimated site index for [species] (BC code, any case). */
    fun suggest(species: String): Double? {
        val k = species.trim().uppercase()
        return DEFAULT_SI[k] ?: RANGE[k]?.let { (lo, hi) -> (lo + hi) / 2.0 }
    }

    /** Clamp [si] into the species's allowed range; null for unknown species. */
    fun clamp(species: String, si: Double): Double? {
        val k = species.trim().uppercase()
        val (lo, hi) = RANGE[k] ?: return null
        return si.coerceIn(lo, hi)
    }
}
