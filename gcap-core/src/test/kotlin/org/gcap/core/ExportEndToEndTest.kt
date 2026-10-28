package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File

/** End-to-end export check: drives the real exporter entry points with
 *  realistic samples and writes the bytes to /tmp/e2e for external
 *  validation (LibreOffice for XLSX, header diff vs SHP_TEMPLATE for DBF). */
class ExportEndToEndTest {

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

    private fun sample(id: String, easting: Double, northing: Double) = GroundCall(
        sampleId = id,
        mapSheet = "092N015",
        sampleType = "mp",
        entryDate = "6/5/26",
        ecology = linkedMapOf("line8" to "R", "line9" to "C", "smr" to "2", "snr" to "B"),
        easting = easting,
        northing = northing,
        capture = CaptureMeta(
            baf = 7.0,
            utmZone = "10N",
            utmPoints = listOf(UtmPoint(id = "P1", easting = easting, northing = northing, zone = "10N")),
        ),
        trees = listOf(
            Tree(number = 1, point = 1, species = "PLC", dbhCm = 38.0, measured = true),
            Tree(number = 2, point = 1, species = "FDC", dbhCm = 25.5, measured = true),
        ),
    )

    @Test
    fun `end to end xlsx and shp export to tmp e2e`() {
        val dir = File("/tmp/e2e").apply { mkdirs() }
        val samples = listOf(
            sample("054", 512345.0, 5454321.0),
            sample("055", 512400.0, 5454400.0),
        )

        // --- XLSX via the same path the app uses (OdsExporter.write) ---
        val st = MemStorage()
        assertTrue(OdsExporter.write(samples[0], st))
        val xlsx = st.files["XGV_ODS_XLSX/054.xlsx"]!!
        assertTrue(xlsx.size > 2000, "xlsx suspiciously small: ${xlsx.size}")
        File(dir, "e2e_054.xlsx").writeBytes(xlsx)

        // --- SHP via the real AllXgvShpExporter entry point ---
        val st2 = MemStorage()
        val (fileName, count) = AllXgvShpExporter.generate(st2, samples, "TST")
        assertEquals(2, count)
        for (n in listOf(fileName, fileName.replace(".shp", ".shx"), fileName.replace(".shp", ".dbf"))) {
            val b = st2.files["GPS_MAP/$n"]
            assertNotNull(b, "missing $n")
            File(dir, "e2e_$n").writeBytes(b!!)
        }
        println("E2E OK: xlsx=${xlsx.size}B shpCount=$count files=${st2.files.keys}")
    }
}
