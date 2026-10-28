package org.gcap.core

import kotlin.math.floor

/**
 * Canadian NTS / BCGS mapsheet lookup: UTM -> "092K096" style designation.
 *
 * Layout (southern zone, validated against 29 field samples):
 *  - 1:1,000,000 area: 4° latitude bands from 40°N, 8° longitude groups from
 *    56°W; number = 10 * lonGroup + latIndex, zero-padded to 3 ("092").
 *  - 1:250,000 letter: 1° x 2° cells in serpentine order from the SE corner
 *    (row0: D C B A / row1: E F G H / row2: L K J I / row3: M N O P).
 *  - BCGS 1:20,000 tile: 10 x 10 grid of 0.1° x 0.2° cells numbered row-major
 *    from the SW corner, 001-100 ("096").
 *
 * Returns null outside the supported southern grid (lat 40..84).
 */
object NtsSheets {

    private val LETTERS = mapOf(
        Pair(3, 0) to 'M', Pair(3, 1) to 'N', Pair(3, 2) to 'O', Pair(3, 3) to 'P',
        Pair(2, 0) to 'L', Pair(2, 1) to 'K', Pair(2, 2) to 'J', Pair(2, 3) to 'I',
        Pair(1, 0) to 'E', Pair(1, 1) to 'F', Pair(1, 2) to 'G', Pair(1, 3) to 'H',
        Pair(0, 0) to 'D', Pair(0, 1) to 'C', Pair(0, 2) to 'B', Pair(0, 3) to 'A',
    )

    fun mapsheetFor(easting: Double, northing: Double, zone: Int): String? {
        if (!easting.isFinite() || !northing.isFinite()) return null
        val (lat, lon) = try {
            UtmProj.utmToLatLon(easting, northing, zone)
        } catch (e: Exception) {
            return null
        }
        return mapsheetForLatLon(lat, lon)
    }

    fun mapsheetForLatLon(lat: Double, lon: Double): String? {
        if (!lat.isFinite() || !lon.isFinite() || lat < 40.0 || lat >= 84.0 || lon >= -56.0) return null
        val latIdx = floor((lat - 40.0) / 4.0).toInt()
        val group = floor((-56.0 - lon) / 8.0).toInt() + 1
        if (group < 1) return null
        val area = "%03d".format(10 * group + latIdx)
        val lat0 = 40.0 + 4.0 * latIdx
        val lonWest = -56.0 - 8.0 * group
        val row = floor(lat - lat0).toInt()
        val col = floor((lon - lonWest) / 2.0).toInt()
        if (row !in 0..3 || col !in 0..3) return null
        val letter = LETTERS[Pair(row, col)] ?: return null
        val slat = lat0 + row
        val slon = lonWest + 2.0 * col
        val trow = floor((lat - slat) / 0.1).toInt()
        val tcol = floor((lon - slon) / 0.2).toInt()
        if (trow !in 0..9 || tcol !in 0..9) return null
        val tile = "%03d".format(trow * 10 + tcol + 1)
        return "$area$letter$tile"
    }
}
