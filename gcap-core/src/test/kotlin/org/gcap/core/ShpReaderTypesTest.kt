package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The reader must accept Z variants (QGIS exports POLYLINEZ etc. by default)
 * and plain POLYGON — the XY section layout is identical, trailing Z/M
 * arrays are skipped via the record content length.
 */
class ShpReaderTypesTest {

    private fun header(shapeType: Int, fileLenWords: Int): ByteArray {
        val b = ByteBuffer.allocate(100).order(ByteOrder.BIG_ENDIAN)
        b.putInt(9994)
        repeat(5) { b.putInt(0) }
        b.putInt(fileLenWords)
        b.order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(1000)
        b.putInt(shapeType)
        repeat(8) { b.putDouble(0.0) }
        return b.array()
    }

    private fun record(recNum: Int, content: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(8 + content.size).order(ByteOrder.BIG_ENDIAN)
        b.putInt(recNum)
        b.putInt(content.size / 2)
        b.put(content)
        return b.array()
    }

    private fun leDoubles(vararg v: Double): ByteArray {
        val b = ByteBuffer.allocate(v.size * 8).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putDouble(it) }
        return b.array()
    }

    private fun leInts(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { b.putInt(it) }
        return b.array()
    }

    private fun pointContent(type: Int, x: Double, y: Double, withZm: Boolean): ByteArray =
        leInts(type) + leDoubles(x, y) + if (withZm) leDoubles(100.0, 0.0) else ByteArray(0)

    private fun polyContent(type: Int, withZ: Boolean, vararg pts: Double): ByteArray {
        // square ring (x0 y0 ...) closed explicitly
        val n = pts.size / 2
        var out = leInts(type) + leDoubles(0.0, 0.0, 10.0, 10.0) + leInts(1, n) + leInts(0) + leDoubles(*pts)
        if (withZ) {
            out += leDoubles(0.0, 0.0) // z range
            out += leDoubles(*DoubleArray(n) { 5.0 }) // z array
            out += leDoubles(0.0, 0.0) // m range
            out += leDoubles(*DoubleArray(n) { 0.0 }) // m array
        }
        return out
    }

    @Test
    fun `pointz parses like point`() {
        val content = pointContent(ShpFormat.TYPE_POINTZ, 500000.0, 5600000.0, true)
        val bytes = header(ShpFormat.TYPE_POINTZ, 50 + content.size / 2) + record(1, content)
        val shapes = ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
        assertEquals(1, shapes.size)
        assertEquals(500000.0, shapes[0].parts[0][0][0], 1e-9)
        assertEquals(5600000.0, shapes[0].parts[0][0][1], 1e-9)
    }

    @Test
    fun `polygon and polygonz parse ring with bbox`() {
        for (type in listOf(ShpFormat.TYPE_POLYGON, ShpFormat.TYPE_POLYGONZ)) {
            val withZ = type == ShpFormat.TYPE_POLYGONZ
            val content = polyContent(type, withZ, 0.0, 0.0, 0.0, 10.0, 10.0, 10.0, 10.0, 0.0, 0.0, 0.0)
            val bytes = header(type, 50 + content.size / 2) + record(1, content)
            val shapes = ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
            assertEquals(1, shapes.size, "type $type")
            assertEquals(5, shapes[0].parts[0].size, "closed ring keeps 5 vertices")
            assertEquals(0.0, shapes[0].xmin, 1e-9)
            assertEquals(10.0, shapes[0].xmax, 1e-9)
            assertEquals(10.0, shapes[0].ymax, 1e-9)
        }
    }

    @Test
    fun `polylinez parses parts`() {
        val content = polyContent(ShpFormat.TYPE_POLYLINEZ, true, 1.0, 2.0, 3.0, 4.0)
        val bytes = header(ShpFormat.TYPE_POLYLINEZ, 50 + content.size / 2) + record(1, content)
        val shapes = ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
        assertEquals(1, shapes.size)
        assertEquals(2, shapes[0].parts[0].size)
        assertEquals(3.0, shapes[0].parts[0][1][0], 1e-9)
    }
}
