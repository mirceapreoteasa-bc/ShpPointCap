package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * Regression test for the all_xgv column mappings reported against
 * 22sep2026_all_xgv.ods (Groundcalls8active). Mirrors the parsed values of
 * the real 024.dat: dat lines 14-20, 52 and 142-144 (1-indexed).
 */
class AllXgvMappingTest {

    class MemStorage : GCapStorage {
        val files = mutableMapOf<String, ByteArray>()
        override fun listDatNames(): List<String> = files.keys.map { it.removeSuffix(".dat") }
        override fun readBytes(name: String): ByteArray? = files[name]
        override fun writeBytes(name: String, bytes: ByteArray): Boolean { files[name] = bytes; return true }
        override fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray): Boolean {
            files["$subFolder/$name"] = bytes; return true
        }
        override fun delete(name: String) { files.remove(name) }
        override fun describe(): String = "mem"
    }

    /** GroundCall shaped exactly like DatParser output for 024.dat. */
    private fun sample024(): GroundCall {
        val header = MutableList(149) { "" }
        header[141] = "10" // dat line 142: UTM zone
        return GroundCall(
            sampleId = "024",
            mapSheet = "092K096",
            sampleType = "mp",
            entryDate = "6/2/26",
            ecology = linkedMapOf<String, Any?>(
                "line8" to "N",
                "line9" to "L",
                "smr" to "5",
                "snr" to "D",
                "line12" to "N",
                "landcover" to "",
                "slope" to "0",
                "surface" to "HF",
                "cover_bytes" to listOf("10", "2.5", "35", "0"),
                "line21" to "0",
                "layer_counts" to listOf("2", "2", "2"),
                "layer_cover_a" to listOf("40", "40", "40"),
                "layer_cover_b" to listOf("5", "5", "5"),
                "layer_cover_c" to listOf("5", "4", "5"),
            ),
            comment = "@e to points 1 @se to points 2 3 gps 1",
            compositionRaw = "act 100 dr mb cw ss",
            easting = 364828.576,
            northing = 5647861.527,
            elevation = 61.9,
            trees = listOf(
                Tree(number = 1, point = 1, species = "ACT", dbhCm = 45.5, measured = true),
                Tree(number = 2, point = 1, species = "ACT", dbhCm = 38.1, measured = true),
            ),
            capture = CaptureMeta(baf = 12.0),
            headerLines = header,
        )
    }

    private fun dataRowCells(odsBytes: ByteArray): List<String> {
        var content = ""
        ByteArrayInputStream(odsBytes).use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "content.xml") content = z.readBytes().toString(Charsets.UTF_8)
                    e = z.nextEntry
                }
            }
        }
        val rows = content.split("<table:table-row>")
        assertTrue(rows.size >= 3, "expected header + data rows")
        return Regex("<text:p>(.*?)</text:p>").findAll(rows[2]).map { it.groupValues[1] }.toList()
    }

    @Test
    fun `all_xgv maps dat lines to the right columns`() {
        val st = MemStorage()
        AllXgvSummaryExporter.generate(st, listOf(sample024()))
        val odsKey = st.files.keys.first { it.endsWith(".ods") }
        val cells = dataRowCells(st.files[odsKey]!!)
        assertEquals(39, cells.size, "39 columns expected")

        assertEquals("XGV", cells[0]) // A CALL_TYPE: hardcoded, line 6 is crew
        assertEquals("024", cells[1]) // B CALL_NUM: text, leading zeros kept
        assertEquals("2026", cells[2]) // C CALL_YEAR from 6/2/26
        assertEquals("1", cells[3]) // D LAYER
        assertEquals("2", cells[5]) // F VERT_COMPL from dat line 52
        assertEquals("2.5", cells[25]) // Z SHRUB_HT from dat line 18
        assertEquals("35", cells[26]) // AA SHRUB_CC from dat line 19
        assertEquals("HF", cells[27]) // AB HERB_TYPE from dat line 16
        assertEquals("10", cells[28]) // AC HERB_PCT from dat line 17
        assertEquals("0", cells[29]) // AD BRYOID_PCT from dat line 20
        assertEquals("", cells[30]) // AE NON_VEG_TY from dat line 14 (empty)
        assertEquals("0", cells[31]) // AF NVC_PCT from dat line 15
        assertEquals("L", cells[32]) // AG SP_MESO from dat line 10
        assertEquals("5", cells[33]) // AH SOIL_MOIS from dat line 11
        assertEquals("D", cells[34]) // AI SOIL_NUT from dat line 12
        assertEquals("10", cells[36]) // AK zone from dat line 142
        assertEquals("364828.576", cells[37]) // AL easting from dat line 143
        assertEquals("5647861.527", cells[38]) // AM northing from dat line 144

        // Number formats: integers for pcts/ages/BA/density/snags, 1 decimal for heights.
        for (i in listOf(7, 9, 11, 13, 15, 17)) {
            assertTrue(cells[i].matches(Regex("-?\\d+")), "species pct col $i must be integer, got '${cells[i]}'")
        }
        assertTrue(cells[18].matches(Regex("-?\\d+")), "age col S integer, got '${cells[18]}'")
        assertTrue(cells[19].matches(Regex("-?\\d+\\.\\d")), "height col T one decimal, got '${cells[19]}'")
        assertTrue(cells[20].matches(Regex("-?\\d+")), "age col U integer, got '${cells[20]}'")
        assertTrue(cells[21].matches(Regex("-?\\d+\\.\\d")), "height col V one decimal, got '${cells[21]}'")
        assertTrue(cells[22].matches(Regex("-?\\d+")), "BA col W integer, got '${cells[22]}'")
        assertTrue(cells[23].matches(Regex("-?\\d+")), "density col X integer, got '${cells[23]}'")
        assertTrue(cells[24].matches(Regex("-?\\d+")), "snags col Y integer, got '${cells[24]}'")
    }

    @Test
    fun `new sidecar points override dat coords in export`() {
        val gc = sample024().copy(
            capture = CaptureMeta(
                baf = 12.0,
                utmZone = "10N",
                utmPoints = listOf(UtmPoint(id = "P1", easting = 500000.0, northing = 5400000.0, zone = "10N")),
                utmChosenIndex = 0,
            ),
        )
        val st = MemStorage()
        AllXgvSummaryExporter.generate(st, listOf(gc))
        val odsKey = st.files.keys.first { it.endsWith(".ods") }
        val cells = dataRowCells(st.files[odsKey]!!)
        assertEquals("10", cells[36])
        assertEquals("500000.0", cells[37])
        assertEquals("5400000.0", cells[38])
    }

    @Test
    fun `datwriter persists zone for new files`() {
        val gc = sample024().copy(
            headerLines = null, // new sample: blank template
            easting = 500000.0,
            northing = 5400000.0,
            elevation = 100.0,
            capture = CaptureMeta(
                baf = 12.0,
                utmZone = "10N",
                utmPoints = listOf(UtmPoint(id = "P1", easting = 500000.0, northing = 5400000.0, zone = "10N")),
                utmChosenIndex = 0,
            ),
        )
        val lines = DatWriter.render(gc).split("\r\n")
        assertEquals("10", lines[141], "dat line 142 zone must match old-file layout")
        assertEquals("500000", lines[142], "dat line 143 easting")
        assertEquals("5400000", lines[143], "dat line 144 northing")
    }
}
