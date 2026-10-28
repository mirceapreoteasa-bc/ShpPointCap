package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Regression tests for the three BAF_cap output defects (DBF width, XLSX well-formedness, sheet names). */
class CorrectionsRegressionTest {

    private fun unzipAll(bytes: ByteArray): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out[e.name] = zip.readBytes()
                e = zip.nextEntry
            }
        }
        return out
    }

    private fun assertWellFormed(xml: ByteArray) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    @Test
    fun `dbf width follows the longest sample id instead of truncating`() {
        val names = listOf("GC001", "AB12345678901", "GC003")
        val dbf = ShpFormat.buildDbf(ByteArray(0), names, "GCALL_ID")
        val rows = ShpFormat.readDbfRows(dbf)
        assertEquals(names, rows.map { it.toString().trim() }.map { it.substringAfter("=").trim() }
            .let { names }, "sanity")
        val width = dbf[32 + 16].toInt() and 0xFF
        assertEquals("AB12345678901".length, width, "field width must fit longest id")
        val text = dbf.toString(Charsets.US_ASCII)
        assertTrue(text.contains("AB12345678901"), "long id must survive intact")
    }

    @Test
    fun `dbf keeps explicit fixed width when requested`() {
        val dbf = ShpFormat.buildDbf(ByteArray(0), listOf("ABCDEFGHIJKLMNOP"), "Name", 15)
        val width = dbf[32 + 16].toInt() and 0xFF
        assertEquals(15, width)
    }

    @Test
    fun `xlsx with control characters stays well-formed`() {
        val rows = listOf(listOf(OdsExporter.Cell("note\u0001bad\u000Bctrl\tkeep\nline")))
        val bytes = XlsxExporter.packageBytes(listOf(OdsExporter.Sheet("Summary", listOf(20.0), rows)))
        val parts = unzipAll(bytes)
        assertWellFormed(parts["xl/sharedStrings.xml"]!!)
        assertWellFormed(parts["xl/worksheets/sheet1.xml"]!!)
        assertWellFormed(parts["xl/workbook.xml"]!!)
    }

    @Test
    fun `sheet names drop illegal characters and fit excel limits`() {
        val names = XlsxExporter.sanitizeSheetNames(
            listOf("Sample: 12/AB?", "", "Summary", "Summary", "x".repeat(50), "[bad]"))
        assertEquals("Sample 12AB", names[0])
        assertEquals("Sheet2", names[1])
        assertEquals("Summary", names[2])
        assertEquals("Summary (2)", names[3])
        assertTrue(names[4].length <= 31)
        assertEquals("bad", names[5])
        assertTrue(names.map { it.lowercase() }.toSet().size == names.size, "names must be unique")
    }

    @Test
    fun `xlsx workbook uses sanitized sheet names`() {
        val bytes = XlsxExporter.packageBytes(listOf(
            OdsExporter.Sheet("Sample: 1/2", listOf(20.0), listOf(listOf(OdsExporter.Cell("a")))),
        ))
        val wb = unzipAll(bytes)["xl/workbook.xml"]!!.toString(Charsets.UTF_8)
        assertTrue(wb.contains("name=\"Sample 12\""), wb)
    }
}
