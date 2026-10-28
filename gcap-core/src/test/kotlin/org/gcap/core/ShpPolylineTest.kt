package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ShpPolylineTest {

    private val lineA = listOf(
        doubleArrayOf(100.0, 200.0),
        doubleArrayOf(110.0, 210.0),
        doubleArrayOf(120.0, 205.0),
    )
    private val lineB = listOf(
        doubleArrayOf(500.0, 600.0),
        doubleArrayOf(510.0, 620.0),
    )

    private fun be(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    @Test
    fun shpRoundTripsMultiPartPolyline() {
        val shp = ShpFormat.buildPolylineShp(listOf(lineA, lineB))
        val info = ShpFormat.shpHeader(shp)
        assertNotNull(info)
        assertEquals(ShpFormat.TYPE_POLYLINE, info!!.shapeType)
        assertEquals(2, info.recordCount)
        assertEquals(100.0, info.xmin, 1e-9)
        assertEquals(200.0, info.ymin, 1e-9)
        assertEquals(510.0, info.xmax, 1e-9)
        assertEquals(620.0, info.ymax, 1e-9)

        val shapes = ShpFormat.readShp(shp, -1e18, -1e18, 1e18, 1e18)
        assertEquals(2, shapes.size)
        assertEquals(lineA.size, shapes[0].parts[0].size)
        assertEquals(100.0, shapes[0].parts[0][0][0], 1e-9)
        assertEquals(205.0, shapes[0].parts[0][2][1], 1e-9)
        assertEquals(510.0, shapes[1].parts[0][1][0], 1e-9)
        assertEquals(620.0, shapes[1].parts[0][1][1], 1e-9)
    }

    @Test
    fun shxOffsetsMatchShpRecordLayout() {
        val parts = listOf(lineA, lineB)
        val shp = ShpFormat.buildPolylineShp(parts)
        val shx = ShpFormat.buildPolylineShx(parts)
        assertEquals(100 + parts.size * 8, shx.size)
        assertEquals(50 + parts.size * 4, be(shx, 24))
        assertEquals(50 + parts.sumOf { 4 + (48 + 16 * it.size) / 2 }, be(shp, 24))

        var expectedOffset = 50
        for (i in parts.indices) {
            val o = 100 + i * 8
            val offset = be(shx, o)
            val len = be(shx, o + 4)
            assertEquals(expectedOffset, offset, "record $i offset")
            assertEquals((48 + 16 * parts[i].size) / 2, len, "record $i length")
            val typeAt = offset * 2 + 8
            assertEquals(
                ShpFormat.TYPE_POLYLINE,
                (shp[typeAt].toInt() and 0xFF) or ((shp[typeAt + 1].toInt() and 0xFF) shl 8),
                "record $i shape type",
            )
            expectedOffset += 4 + len
        }
        assertEquals(be(shp, 24), expectedOffset)
    }

    @Test
    fun emptyAndDegeneratePartsAreDroppedSafely() {
        val empty = ShpFormat.buildPolylineShp(emptyList())
        assertEquals(100, empty.size)
        assertTrue(ShpFormat.readShp(empty, -1e18, -1e18, 1e18, 1e18).isEmpty())

        val single = ShpFormat.buildPolylineShp(listOf(listOf(doubleArrayOf(1.0, 2.0))))
        assertTrue(ShpFormat.readShp(single, -1e18, -1e18, 1e18, 1e18).isEmpty())

        val mixed = ShpFormat.buildPolylineShp(listOf(lineB, listOf(doubleArrayOf(7.0, 8.0))))
        assertEquals(1, ShpFormat.shpHeader(mixed)?.recordCount)
    }

    @Test
    fun trackDbfRowsMatchShapeCount() {
        val dbf = ShpFormat.buildDbf(ByteArray(0), listOf("t1", "t2"), "Name", 15)
        assertEquals(2, ShpFormat.dbfHeader(dbf).numRecords)
        assertEquals(listOf("t1", "t2"), ShpFormat.readDbfRows(dbf).map { it.values[0].trim() })
    }
}
