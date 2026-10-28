package org.shppointcap.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UtmCrsTest {

    @Test
    fun forwardMatchesPyprojAnchor() {
        // (50.96648N, 124.92504W) <-> (364828.576E, 5647861.527N) zone 10 (pyproj).
        val (e, n) = Utm.wgs84ToUtm(50.96648, -124.92504)
        assertTrue("easting $e", abs(e - 364828.576) < 1.0)
        assertTrue("northing $n", abs(n - 5647861.527) < 1.0)
        assertEquals(10, Utm.meridianZone(-124.92504))
        assertEquals("10N", Utm.zoneLabel(50.96648, -124.92504))
    }

    @Test
    fun roundTripPreservesBcPoints() {
        val pts = listOf(
            Triple(50.96648, -124.92504, 10), // 092K area
            Triple(51.18106, -125.06081, 10), // 092N area
            Triple(49.22033, -122.95177, 10), // valley floor
            Triple(48.42220, -123.36570, 10), // Victoria (zone 10 edge)
        )
        for ((lat, lon, zone) in pts) {
            val (e, n) = Utm.wgs84ToUtm(lat, lon)
            val (rlat, rlon) = Utm.utmToLatLon(e, n, zone)
            assertTrue("lat $lat -> $rlat", abs(rlat - lat) < 1e-6)
            assertTrue("lon $lon -> $rlon", abs(rlon - lon) < 1e-6)
        }
    }

    @Test
    fun mobAutoNameIsDayHourMinute() {
        val now = System.currentTimeMillis()
        val name = Utm.mobAutoName(now)
        assertTrue("DDHHMM, got $name", name.matches(Regex("\\d{6}")))
        val expected = java.text.SimpleDateFormat("ddHHmm", java.util.Locale.US).apply {
            timeZone = java.util.TimeZone.getDefault()
        }.format(java.util.Date(now))
        assertEquals(expected, name)
    }

    @Test
    fun classifyCrsMatchesRealSidecars() {
        val utm10 = """PROJCS["NAD_1983_UTM_Zone_10N",GEOGCS["GCS_North_American_1983",DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"]]"""
        val wgs84 = """GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]"""
        val albers = """PROJCS["NAD_1983_BC_Environment_Albers",GEOGCS["GCS_North_American_1983",DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Albers"]]"""
        assertEquals(Utm.Crs.UTM_METERS, Utm.classifyCrs(utm10))
        assertEquals(Utm.Crs.WGS84_DEGREES, Utm.classifyCrs(wgs84))
        assertEquals(Utm.Crs.ALBERS, Utm.classifyCrs(albers))
        assertEquals(Utm.Crs.UTM_METERS, Utm.classifyCrs(null))
        assertEquals(Utm.Crs.UTM_METERS, Utm.classifyCrs(""))
    }

    @Test
    fun classifyCrsAcceptsUtm10NNameVariants() {
        // Same system, different PROJCS spellings (QGIS/ESRI exports).
        val variants = listOf(
            """PROJCS["NAD83 / UTM zone 10N",GEOGCS["NAD83"],PROJECTION["Transverse_Mercator"]]""",
            """PROJCS["WGS 84 / UTM zone 10N",GEOGCS["WGS 84"],PROJECTION["Transverse_Mercator"]]""",
            """PROJCS["UTM_Zone_10N",GEOGCS["GCS"],PROJECTION["Transverse_Mercator"]]""",
        )
        for (v in variants) assertEquals(v, Utm.Crs.UTM_METERS, Utm.classifyCrs(v))
        // Other zones stay OTHER.
        val zone11 = """PROJCS["NAD83 / UTM zone 11N",GEOGCS["NAD83"],PROJECTION["Transverse_Mercator"]]"""
        assertEquals(Utm.Crs.OTHER, Utm.classifyCrs(zone11))
    }

    @Test
    fun classifyCrsDerivesZoneFromCentralMeridian() {
        // Custom Transverse_Mercator export without any UTM name.
        val zm10 = """PROJCS["Custom_TM",GEOGCS["GCS"],PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",500000.0],PARAMETER["Central_Meridian",-123.0],PARAMETER["Scale_Factor",0.9996],UNIT["Meter",1.0]]"""
        val zm11 = """PROJCS["Custom_TM",GEOGCS["GCS"],PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",500000.0],PARAMETER["Central_Meridian",-117.0],PARAMETER["Scale_Factor",0.9996],UNIT["Meter",1.0]]"""
        assertEquals(Utm.Crs.UTM_METERS, Utm.classifyCrs(zm10))
        assertEquals(Utm.Crs.OTHER, Utm.classifyCrs(zm11))
    }
}
