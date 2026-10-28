package org.shppointcap.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AlbersTest {

    private val bcAlbersPrj = "PROJCS[\"NAD_1983_BC_Environment_Albers\"," +
        "GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\"," +
        "SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],PRIMEM[\"Greenwich\",0.0]," +
        "UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Albers\"]," +
        "PARAMETER[\"False_Easting\",1000000.0],PARAMETER[\"False_Northing\",0.0]," +
        "PARAMETER[\"Central_Meridian\",-126.0],PARAMETER[\"Standard_Parallel_1\",50.0]," +
        "PARAMETER[\"Standard_Parallel_2\",58.5],PARAMETER[\"Latitude_Of_Origin\",45.0]," +
        "UNIT[\"Meter\",1.0]]"

    // pyproj anchors (EPSG:4326 <-> EPSG:3005).
    private val anchors = listOf(
        Triple(50.96648, -124.92504, Pair(1075433.789, 661923.456)),
        Triple(51.18106, -125.06081, Pair(1065589.867, 685684.639)),
        Triple(50.0, -125.0, Pair(1071693.369, 554290.365)),
    )

    @Test
    fun paramsParseBcAlbers() {
        val p = Albers.params(bcAlbersPrj)
        assertNotNull(p)
        p!!
        assertEquals(45.0, p.lat0Deg, 1e-9)
        assertEquals(-126.0, p.lon0Deg, 1e-9)
        assertEquals(50.0, p.lat1Deg, 1e-9)
        assertEquals(58.5, p.lat2Deg, 1e-9)
        assertEquals(1000000.0, p.fe, 1e-9)
        assertEquals(0.0, p.fn, 1e-9)
    }

    @Test
    fun paramsRejectIncomplete() {
        assertNull(Albers.params("PROJCS[\"X\",PROJECTION[\"Albers\"]]"))
        assertNull(Albers.params(""))
    }

    @Test
    fun forwardMatchesPyproj() {
        val p = Albers.params(bcAlbersPrj)!!
        for ((lat, lon, en) in anchors) {
            val (e, n) = Albers.fromWgs84(lat, lon, p)
            assertTrue("E $e vs ${en.first}", abs(e - en.first) < 0.5)
            assertTrue("N $n vs ${en.second}", abs(n - en.second) < 0.5)
        }
    }

    @Test
    fun inverseMatchesPyproj() {
        val p = Albers.params(bcAlbersPrj)!!
        for ((lat, lon, en) in anchors) {
            val (rlat, rlon) = Albers.toWgs84(en.first, en.second, p)
            assertTrue("lat $rlat vs $lat", abs(rlat - lat) < 1e-6)
            assertTrue("lon $rlon vs $lon", abs(rlon - lon) < 1e-6)
        }
    }

    @Test
    fun classifyCrsSeesAlbers() {
        assertEquals(Utm.Crs.ALBERS, Utm.classifyCrs(bcAlbersPrj))
    }
}
