package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Validates NtsSheets against the real tablet downloads (Groundcalls8actB2 /
 * Groundcalls8activeC3): (easting, northing zone 10) -> stored mapsheet.
 * Three entries are known-stale (coords re-collected as enclosure test points
 * near 092G while the stored sheet still says 092K096); they assert the
 * recomputed value so the drift is pinned, not hidden.
 */
class NtsSheetsTest {

    private data class Pt(val e: Double, val n: Double, val expected: String)

    private val pts = listOf(
        // ---- B2 ----
        Pt(364828.576, 5647861.527, "092K096"), // 024
        Pt(503512.339, 5451950.756, "092G026"), // 026 STALE (stored 092K096)
        Pt(364410.456, 5652481.133, "092N006"), // 040
        Pt(366873.494, 5645774.193, "092K096"), // 043
        Pt(355963.659, 5671978.035, "092N015"), // 053
        Pt(356138.147, 5671939.985, "092N015"), // 054
        Pt(503513.593, 5451949.478, "092G026"), // 301 STALE (stored 092K096)
        Pt(365621.239, 5646285.295, "092K096"), // 302
        Pt(365974.954, 5649137.753, "092K096"), // 303
        Pt(363510.275, 5653662.79, "092N006"), // 304
        Pt(360480.22, 5637794.981, "092K086"), // 305
        Pt(360905.126, 5637115.534, "092K086"), // 306
        Pt(361271.705, 5636760.693, "092K086"), // 307
        Pt(362244.189, 5634901.154, "092K086"), // 308
        Pt(355919.214, 5671762.398, "092N015"), // 309
        Pt(356080.075, 5671734.521, "092N015"), // 310
        // ---- C3 ----
        Pt(364828.576, 5647861.527, "092K096"), // 024
        Pt(365271.29, 5646932.826, "092K096"), // 026 (re-collected, back in tile)
        Pt(364410.456, 5652481.133, "092N006"), // 040
        Pt(366873.494, 5645774.193, "092K096"), // 043
        Pt(355963.659, 5671978.035, "092N015"), // 053
        Pt(356138.147, 5671939.985, "092N015"), // 054
        Pt(366086.361, 5645589.53, "092K096"), // 301 (re-collected, back in tile)
        Pt(503517.779, 5451954.121, "092G026"), // 302 STALE (stored 092K096)
        Pt(365974.954, 5649137.753, "092K096"), // 303
        Pt(363510.275, 5653662.79, "092N006"), // 304
        Pt(360480.22, 5637794.981, "092K086"), // 305
        Pt(360905.126, 5637115.534, "092K086"), // 306
        Pt(361271.705, 5636760.693, "092K086"), // 307
        Pt(362244.189, 5634901.154, "092K086"), // 308
        Pt(355919.214, 5671762.398, "092N015"), // 309
        Pt(356080.075, 5671734.521, "092N015"), // 310
    )

    @Test
    fun `all tablet points resolve to their stored sheet except known stale`() {
        for ((i, p) in pts.withIndex()) {
            assertEquals(p.expected, NtsSheets.mapsheetFor(p.e, p.n, 10), "point $i (${p.e}, ${p.n})")
        }
    }

    @Test
    fun `reference anchors resolve correctly`() {
        // Vancouver BC ~ (485400, 5458000) zone 10 -> 092G area tiles
        assertEquals("092", NtsSheets.mapsheetFor(485400.0, 5458000.0, 10)!!.take(3))
        // Out-of-grid input yields null instead of garbage.
        assertNull(NtsSheets.mapsheetFor(Double.NaN, 0.0, 10))
        assertNull(NtsSheets.mapsheetForLatLon(30.0, -110.0))
    }
}
