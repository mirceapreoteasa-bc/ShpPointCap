package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class XlsxExporterVerifyTest {

    private fun unzipAll(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var e = zip.nextEntry
            while (e != null) {
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
                e = zip.nextEntry
            }
        }
        return out
    }

    @Test
    fun `package is a readable zip with all required parts`() {
        val rows = listOf(
            listOf(OdsExporter.Cell("A"), OdsExporter.Cell("B")),
            listOf(OdsExporter.Cell("1"), OdsExporter.Cell("2")),
        )
        val bytes = XlsxExporter.packageBytes(listOf(OdsExporter.Sheet("Sheet1", listOf(20.0, 20.0), rows)))
        val parts = unzipAll(bytes)
        assertTrue(parts.containsKey("[Content_Types].xml"))
        assertTrue(parts.containsKey("_rels/.rels"))
        assertTrue(parts.containsKey("xl/workbook.xml"))
        assertTrue(parts.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(parts.containsKey("xl/sharedStrings.xml"))
        assertTrue(parts.containsKey("xl/styles.xml"))
        assertTrue(parts.containsKey("xl/worksheets/sheet1.xml"))
    }

    @Test
    fun `sharedStrings count matches uniqueCount and escapes xml`() {
        val rows = listOf(
            listOf(OdsExporter.Cell("caf\u00e9 & <tag> \"q\" 's'"), OdsExporter.Cell(" ")),
            listOf(OdsExporter.Cell(""), OdsExporter.Cell("plain")),
        )
        val bytes = XlsxExporter.packageBytes(listOf(OdsExporter.Sheet("S", listOf(20.0, 20.0), rows)))
        val parts = unzipAll(bytes)
        val sst = parts["xl/sharedStrings.xml"]!!
        assertTrue(sst.contains("xml:space=\"preserve\""), "sst must preserve spaces")
        assertTrue(sst.contains("caf\u00e9 &amp; &lt;tag&gt; &quot;q&quot; &apos;s&apos;"), "special chars escaped, got: $sst")
        val count = Regex("count=\"(\\d+)\"").find(sst)!!.groupValues[1].toInt()
        val uniq = Regex("uniqueCount=\"(\\d+)\"").find(sst)!!.groupValues[1].toInt()
        assertEquals(count, uniq, "count must equal uniqueCount")
        assertEquals(3, uniq, "3 distinct strings expected (empty dedups to blank)")
    }

    @Test
    fun `worksheet has dimension and shared-string cell refs`() {
        val rows = listOf(
            listOf(OdsExporter.Cell("H1"), OdsExporter.Cell("H2")),
            listOf(OdsExporter.Cell("v1"), OdsExporter.Cell("v2")),
            listOf(OdsExporter.Cell("v3"), OdsExporter.Cell("v4")),
        )
        val bytes = XlsxExporter.packageBytes(listOf(OdsExporter.Sheet("Data", listOf(20.0, 20.0), rows)))
        val parts = unzipAll(bytes)
        val ws = parts["xl/worksheets/sheet1.xml"]!!
        assertTrue(ws.contains("<dimension ref=\"A1:B3\"/>"), "dimension must cover data, got: ${ws.take(300)}")
        assertTrue(ws.contains("<c r=\"A1\" t=\"s\"><v>"), "cells must be shared-string refs")
    }

    @Test
    fun `workbook content type is sheet main like libreoffice template`() {
        val bytes = XlsxExporter.packageBytes(
            listOf(OdsExporter.Sheet("S", listOf(20.0), listOf(listOf(OdsExporter.Cell("x")))))
        )
        val ct = unzipAll(bytes)["[Content_Types].xml"]!!
        assertTrue(
            ct.contains("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"),
            "workbook part must use sheet.main+xml (LibreOffice rejects workbook+xml)"
        )
    }

    @Test
    fun `styles match template font set`() {
        val bytes = XlsxExporter.packageBytes(
            listOf(OdsExporter.Sheet("S", listOf(20.0), listOf(listOf(OdsExporter.Cell("x")))))
        )
        val styles = unzipAll(bytes)["xl/styles.xml"]!!
        assertTrue(styles.contains("<fonts count=\"5\">"), "5 fonts like template")
        assertTrue(styles.contains("<numFmt numFmtId=\"164\""), "numFmt 164 like template")
    }
}
