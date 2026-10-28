package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class DatRoundTripTest {

    private val samplesDir = System.getProperty("samples.dir")
        ?: "${System.getProperty("user.home")}/GroundCallsCapture511/Groundcalls"

    private fun sampleFiles(): List<File> {
        val dir = File(samplesDir)
        assertTrue(dir.isDirectory, "samples dir not found: $samplesDir")
        return dir.listFiles { f -> f.name.endsWith(".dat") }!!.sortedBy { it.name }
    }

    @Test
    fun parsesAllSamplesWithExpectedPointCounts() {
        for (f in sampleFiles()) {
            val gc = DatParser.parseFile(f.absolutePath)
            assertEquals(3, gc.nPoints, "${f.name}: expected 3 points")
            val lines = f.readBytes().toString(Charsets.ISO_8859_1).split("\r\n")
            // header per-point count cells L96/99/102 = "1-based" parse counts
            for (i in 0 until gc.nPoints) {
                val headerCount = lines[96 + i * 3].toIntOrNull() ?: 0
                val parsedCount = gc.points()[i + 1]?.size ?: 0
                assertEquals(headerCount, parsedCount,
                    "${f.name}: header L${96 + i * 3} count != parsed point ${i + 1} count")
            }
        }
    }

    @Test
    fun roundTripPreservesTreesAndHeader() {
        for (f in sampleFiles()) {
            val gc = DatParser.parseFile(f.absolutePath)
            val rendered = DatWriter.render(gc)
            val gc2 = DatParser.parseText(rendered)

            assertEquals(gc.sampleId, gc2.sampleId, "${f.name}: sample id")
            assertEquals(gc.entryDate, gc2.entryDate, "${f.name}: entry date")
            assertEquals(gc.comment, gc2.comment, "${f.name}: comment")
            assertEquals(gc.compositionRaw, gc2.compositionRaw, "${f.name}: composition")
            assertEquals(gc.nPoints, gc2.nPoints, "${f.name}: n points")

            val p1 = gc.points()
            val p2 = gc2.points()
            assertEquals(p1.keys, p2.keys, "${f.name}: point ids")
            for (p in p1.keys) {
                val a = p1[p]!!
                val b = p2[p]!!
                assertEquals(a.size, b.size, "${f.name}: point $p tree count")
                for (i in a.indices) {
                    assertEquals(a[i].number, b[i].number, "${f.name}: tree number")
                    assertEquals(a[i].species, b[i].species, "${f.name}: species")
                    assertEquals(a[i].dbhCm, b[i].dbhCm, "${f.name}: dbh")
                    assertEquals(a[i].crownClass, b[i].crownClass, "${f.name}: crown")
                    assertEquals(a[i].measured, b[i].measured, "${f.name}: measured")
                }
            }
        }
    }

    @Test
    fun deletedPointsClearStaleHeaderCountsAndRecords() {
        val f = sampleFiles().first { it.name == "054.dat" }
        val gc = DatParser.parseFile(f.absolutePath)
        // delete point 3, then point 2 (mimicking the app's deletePoint())
        val once = gc.trees.filter { it.point != 3 }
            .map { if (it.point < 3) it else it.copy(point = it.point - 1) }
        val twice = once.filter { it.point != 2 }
            .map { if (it.point < 2) it else it.copy(point = it.point - 1) }
        val rendered = DatWriter.render(gc.copy(trees = twice))
        val lines = rendered.split("\r\n")
        // header L96/99/102 must be honest: 8 present trees, stale cells cleared
        assertEquals("8", lines[96])
        assertEquals("", lines[99])
        assertEquals("", lines[102])
        val reparsed = DatParser.parseText(rendered)
        assertEquals(1, reparsed.nPoints)
        assertEquals(8, reparsed.trees.size)
        assertTrue(reparsed.trees.all { it.point == 1 })
        // record zone = 149 header lines + 8 records*16 - 1 (no final flag line)
        assertEquals(277, lines.size)
    }

    @Test
    fun coordinatesRoundTrip() {
        val f = sampleFiles().first { it.name == "304.dat" }
        val gc = DatParser.parseFile(f.absolutePath)
        assertNotNull(gc.easting)
        assertEquals(363510.275, gc.easting!!, 1e-6)
        assertEquals(5653662.79, gc.northing!!, 1e-6)
        assertEquals(31.5, gc.elevation!!, 1e-6)
        val gc2 = DatParser.parseText(DatWriter.render(gc))
        assertEquals(gc.easting, gc2.easting)
        assertEquals(gc.northing, gc2.northing)
        assertEquals(gc.elevation, gc2.elevation)
    }
}