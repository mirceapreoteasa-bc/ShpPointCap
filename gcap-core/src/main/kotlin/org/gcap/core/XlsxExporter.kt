package org.gcap.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {

    fun packageBytes(sheets: List<OdsExporter.Sheet>): ByteArray {
        val strings = mutableListOf<String>()
        val stringMap = mutableMapOf<String, Int>()

        fun getStringId(s: String): Int {
            val clean = if (s.isEmpty()) " " else s
            return stringMap.getOrPut(clean) {
                strings.add(clean)
                strings.size - 1
            }
        }

        for (sheet in sheets) {
            for (row in sheet.rows) {
                for (cell in row) {
                    getStringId(cell.text)
                }
            }
        }

        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)

        fun add(path: String, data: ByteArray) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(data)
            zip.closeEntry()
        }

        val sheetOverrides = sheets.indices.joinToString("\n") { i ->
            "<Override PartName=\"/xl/worksheets/sheet" + (i + 1) + ".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
        }
        add(
            "[Content_Types].xml",
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
                "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>\n" +
                "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>\n" +
                sheetOverrides + "\n</Types>").toByteArray(Charsets.UTF_8)
        )

        add(
            "_rels/.rels",
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
                "</Relationships>").toByteArray(Charsets.UTF_8)
        )

        val sheetRels = sheets.indices.joinToString("\n") { i ->
            "<Relationship Id=\"rId" + (i + 1) + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet" + (i + 1) + ".xml\"/>"
        }
        add(
            "xl/_rels/workbook.xml.rels",
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>\n" +
                "<Relationship Id=\"rIdStrings\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>\n" +
                sheetRels + "\n</Relationships>").toByteArray(Charsets.UTF_8)
        )

        val sheetNames = sanitizeSheetNames(sheets.map { it.title })
        val sheetEntries = sheetNames.mapIndexed { i, name ->
            "<sheet name=\"" + xml(name) + "\" sheetId=\"" + (i + 1) + "\" r:id=\"rId" + (i + 1) + "\"/>"
        }.joinToString("\n")
        add(
            "xl/workbook.xml",
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
                "<sheets>\n" + sheetEntries + "\n</sheets>\n</workbook>").toByteArray(Charsets.UTF_8)
        )

        val sstXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
            append("<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"")
            append(strings.size)
            append("\" uniqueCount=\"")
            append(strings.size)
            append("\">\n")
            for (s in strings) {
                append("<si><t xml:space=\"preserve\">")
                append(xml(s))
                append("</t></si>\n")
            }
            append("</sst>")
        }
        add("xl/sharedStrings.xml", sstXml.toByteArray(Charsets.UTF_8))

        add(
            "xl/styles.xml",
            ("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"General\"/></numFmts>" +
                "<fonts count=\"5\">" +
                "<font><sz val=\"10\"/><name val=\"Arial\"/><family val=\"2\"/></font>" +
                "<font><sz val=\"10\"/><name val=\"Arial\"/><family val=\"0\"/></font>" +
                "<font><sz val=\"10\"/><name val=\"Arial\"/><family val=\"0\"/></font>" +
                "<font><sz val=\"10\"/><name val=\"Arial\"/><family val=\"0\"/></font>" +
                "<font><sz val=\"10\"/><color rgb=\"FF000000\"/><name val=\"Arial\"/><family val=\"2\"/></font>" +
                "</fonts>" +
                "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
                "<borders count=\"1\"><border diagonalUp=\"false\" diagonalDown=\"false\"><left/><right/><top/><bottom/><diagonal/></border></borders>" +
                "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>" +
                "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>" +
                "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>" +
                "</styleSheet>").toByteArray(Charsets.UTF_8)
        )

        sheets.forEachIndexed { idx, sheet ->
            val maxCol = sheet.rows.maxOfOrNull { it.size } ?: 1
            val maxRow = sheet.rows.size.coerceAtLeast(1)
            val dimRef = "A1:" + colName(maxCol - 1) + maxRow

            val wsXml = buildString {
                append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n")
                append("<dimension ref=\"")
                append(dimRef)
                append("\"/>\n<sheetData>\n")
                sheet.rows.forEachIndexed { rIdx, row ->
                    val rowNum = rIdx + 1
                    append("<row r=\"")
                    append(rowNum)
                    append("\">\n")
                    row.forEachIndexed { cIdx, cell ->
                        val cellRef = colName(cIdx) + rowNum
                        val text = if (cell.text.isEmpty()) " " else cell.text
                        append("<c r=\"")
                        append(cellRef)
                        append("\" t=\"s\"><v>")
                        append(getStringId(text))
                        append("</v></c>\n")
                    }
                    append("</row>\n")
                }
                append("</sheetData>\n</worksheet>\n")
            }
            add("xl/worksheets/sheet" + (idx + 1) + ".xml", wsXml.toByteArray(Charsets.UTF_8))
        }

        zip.close()
        return out.toByteArray()
    }

    private fun colName(n: Int): String {
        var num = n.coerceAtLeast(0)
        var s = ""
        while (true) {
            val rem = num % 26
            s = ('A'.code + rem).toChar().toString() + s
            num = (num / 26) - 1
            if (num < 0) break
        }
        return s
    }

    /** Sheet names Excel accepts: no []:*?/\\, 1..31 chars, unique. */
    internal fun sanitizeSheetNames(titles: List<String>): List<String> {
        val used = mutableSetOf<String>()
        return titles.mapIndexed { i, raw ->
            var name = raw.filter { it !in "[]:*?/\\" && isXmlChar(it) }.trim('\'', ' ')
            if (name.isEmpty()) name = "Sheet${i + 1}"
            name = name.take(31)
            var candidate = name
            var n = 2
            while (!used.add(candidate.lowercase())) {
                val suffix = " ($n)"
                candidate = name.take(31 - suffix.length) + suffix
                n++
            }
            candidate
        }
    }

    /** XML 1.0 allows TAB, LF, CR and U+0020 and up, except surrogates and U+FFFE/U+FFFF. */
    internal fun isXmlChar(ch: Char): Boolean =
        ch == '\t' || ch == '\n' || ch == '\r' ||
            (ch.code in 0x20..0xD7FF) || (ch.code in 0xE000..0xFFFD)

    private fun xml(raw: String): String {
        val sb = StringBuilder(raw.length + 16)
        for (ch in raw) {
            if (!isXmlChar(ch)) {
                sb.append('?')
                continue
            }
            when (ch) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
