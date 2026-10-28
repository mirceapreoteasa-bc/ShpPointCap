package org.gcap.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.io.File

class ByteFidelityTest {
    @Test
    fun diffAll() {
        for (f in File("/home/mircea/GroundCallsCapture511/Groundcalls").listFiles { it.name.endsWith(".dat") }!!.sortedBy { it.name }) {
            val original = f.readBytes()
            val gc = DatParser.parseText(original.toString(Charsets.ISO_8859_1))
            val rend = DatWriter.render(gc).toByteArray(Charsets.ISO_8859_1)
            val lo = original.toString(Charsets.ISO_8859_1).split("\r\n")
            val lr = rend.toString(Charsets.ISO_8859_1).split("\r\n")
            if (lo.size != lr.size) println("${f.name}: lines ${lo.size} vs ${lr.size}")
            for (i in 0 until maxOf(lo.size, lr.size)) {
                if (lo.getOrElse(i) { "<eof>" } != lr.getOrElse(i) { "<eof>" }) {
                    println("${f.name} L$i orig=${lo.getOrNull(i) ?: "<eof>"} rend=${lr.getOrNull(i) ?: "<eof>"}")
                }
            }
            assertArrayEquals(original, rend)
        }
    }
}
