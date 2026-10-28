package org.gcap.core

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object OdsExporter {
    private const val S_LABEL = "sp"
    private const val S_RED = "sp"
    private const val S_GREEN = "sp"
    private const val S_BLUE = "sp"
    private const val S_YELLOW = "sp"
    private const val S_ORANGE = "sp"
    private const val S_PLAIN = "sp"

    data class Cell(val text: String, val style: String = S_PLAIN)
    class Sheet(val title: String, val colWidths: List<Double>, val rows: List<List<Cell>>)

    fun write(gc: GroundCall, storage: GCapStorage): Boolean {
        val bytes = packageSheets(sheets(gc))
        val odsOk = storage.writeSubFolderBytes("XGV_ODS_XLSX", "${gc.sampleId}.ods", bytes)
        val xlsxBytes = XlsxExporter.packageBytes(sheets(gc))
        val xlsxOk = storage.writeSubFolderBytes("XGV_ODS_XLSX", "${gc.sampleId}.xlsx", xlsxBytes)
        return odsOk && xlsxOk
    }

    internal fun spreadsheetXml(sheetsList: List<Sheet>): String {
        val sb = StringBuilder()
        sb.append(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<office:document-content " +
                "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" " +
                "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" " +
                "xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" " +
                "xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" " +
                "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\" " +
                "office:version=\"1.2\">\n"
        )
        sb.append("<office:font-face-decls/>\n")
        sb.append("<office:automatic-styles>\n")
        sb.append(cellStyle(S_LABEL, null))
        for (sheet in sheetsList) {
            sheet.colWidths.forEachIndexed { i, w ->
                sb.append(
                    "<style:style style:name=\"${sheetCol("x", sheet.title, i)}\" style:family=\"table-column\">" +
                        "<style:table-column-properties fo:column-width=\"$w\"/>" +
                        "</style:style>\n"
                )
            }
        }
        sb.append("</office:automatic-styles>\n")
        sb.append("<office:body><office:spreadsheet>\n")
        for (sheet in sheetsList) {
            sb.append("<table:table table:name=\"${xml(sheet.title)}\">\n")
            for (i in sheet.colWidths.indices) {
                sb.append("<table:table-column table:style-name=\"${sheetCol("x", sheet.title, i)}\"/>")
            }
            sb.append("\n")
            for (row in sheet.rows) {
                sb.append("<table:table-row>\n")
                for (cell in row) {
                    sb.append("<table:table-cell table:style-name=\"${cell.style}\">")
                    sb.append("<text:p>${xml(cell.text)}</text:p>")
                    sb.append("</table:table-cell>\n")
                }
                sb.append("</table:table-row>\n")
            }
            sb.append("</table:table>\n")
        }
        sb.append("</office:spreadsheet></office:body>\n")
        sb.append("</office:document-content>\n")
        return sb.toString()
    }

    fun packageSheets(sheetsList: List<Sheet>): ByteArray {
        val content = spreadsheetXml(sheetsList)
        val mimetype = "application/vnd.oasis.opendocument.spreadsheet".toByteArray(Charsets.US_ASCII)
        val styles = stylesXml().toByteArray(Charsets.UTF_8)
        val meta = metaXml().toByteArray(Charsets.UTF_8)
        val manifest = manifestXml().toByteArray(Charsets.UTF_8)

        val out = ByteArrayOutputStream()
        val zip = ZipOutputStream(out)
        val mt = ZipEntry("mimetype").apply {
            this.method = ZipEntry.STORED
            size = mimetype.size.toLong()
            crc = CRC32().also { it.update(mimetype) }.value
        }
        zip.putNextEntry(mt)
        zip.write(mimetype)
        zip.closeEntry()

        fun add(name: String, bytes: ByteArray) {
            val e = ZipEntry(name)
            zip.putNextEntry(e)
            zip.write(bytes)
            zip.closeEntry()
        }
        add("content.xml", content.toByteArray(Charsets.UTF_8))
        add("styles.xml", styles)
        add("meta.xml", meta)
        add("META-INF/manifest.xml", manifest)
        zip.close()
        return out.toByteArray()
    }

    private fun cellStyle(id: String, bg: String?): String {
        return """
            <style:style style:name="$id" style:family="table-cell" style:parent-style-name="Default">
                <style:text-properties
                    fo:color="#000000"
                    fo:font-size="10pt"
                    style:font-weight="normal"/>
            </style:style>
        """.trimIndent() + "\n"
    }

    private fun sheetCol(prefix: String, title: String, i: Int): String =
        "c${title.filter { it.isLetterOrDigit() }}$i"

    private fun xml(raw: String): String = raw
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun gpsPts(gc: GroundCall): List<String> = gc.capture.utmPoints.map { p -> p.id + " " + fmt(p.easting) + " " + fmt(p.northing) }

    private fun gpsSheet(gc: GroundCall): Sheet {
        val rows = mutableListOf<List<Cell>>()
        fun r(vararg cells: Cell) = rows.add(cells.toList())
        val blank = Cell("")
        r(Cell("GPS"), Cell(gc.sampleId), blank, Cell("UtmZone"), Cell(gc.capture.utmZone))
        r(Cell("Chosen"), Cell(if (gc.capture.utmChosenIndex == null) "last collected" else "P${(gc.capture.utmChosenIndex ?: 0) + 1}"))
        gpsPts(gc).forEachIndexed { i, t ->
            r(Cell("P${i + 1}"), Cell(t))
        }
        return Sheet("GPS", listOf(1.4, 6.0, 1.2, 5.0), rows)
    }

    fun sheets(gc: GroundCall): List<Sheet> = buildList {
        add(summarySheet(gc))
        val perPoint = gc.points()
        for (p in 1..3) add(pointSheet(gc, perPoint[p] ?: emptyList(), p))
        add(commentsSheet(gc))
        add(gpsSheet(gc))
    }

    private fun ecoS(gc: GroundCall, key: String): String =
        (gc.ecology[key] as? String).orEmpty()

    private fun ecoList(gc: GroundCall, key: String, i: Int): String =
        (gc.ecology[key] as? List<*>)?.getOrNull(i)?.toString().orEmpty()

    private fun fmt(d: Double?): String {
        if (d == null || !d.isFinite()) return ""
        val l = d.toLong()
        return if (d == l.toDouble()) l.toString() else "%.1f".format(d)
    }

    private fun summarySheet(gc: GroundCall): Sheet {
        val hasFixed = gc.capture.radiusM != null && gc.capture.radiusM!! > 0.0 && gc.capture.conflictChoice != "BAF"
        val activeBaf = if (hasFixed) 4.0 else (gc.capture.baf ?: 4.0)
        val activeRadius = if (hasFixed) gc.capture.radiusM else null
        val calc = GcapCalc.summarizeByPoint(gc, baf = activeBaf, radiusM = activeRadius)
        val density = Math.round(
            calc.points.map { Math.round(it.stemsHa).toDouble() }.average()
        )
        val inTrees = if (hasFixed) gc.trees.filter { it.isLive && it.dbhCm != null } else GcapCalc.inTrees(gc.trees, activeBaf)

        val comp = GcapCalc.reorderForDisplay(
            GcapCalc.speciesComposition(gc.trees, useBasalArea = hasFixed, baf = if (hasFixed) null else activeBaf).toList(),
            gc.capture.use3rdAsSecond,
        ).take(5)
        val codes = comp.map { it.first }
        val ages = GcapCalc.meanTotalAgeBySpecies(gc.trees)
        val hgts = GcapCalc.meanHeightBySpecies(gc.trees)
        fun ageView(code: String?): String {
            val a = code?.let { ages[it] }
            return if (a == null || !a.isFinite()) "" else Math.round(a).toString()
        }
        fun hgtView(code: String?): String = code?.let { fmt(hgts[it]) }.orEmpty()

        val radius = if (gc.capture.radiusM != null) fmt(gc.capture.radiusM) else ""
        val radio = if (gc.capture.baf != null) fmt(gc.capture.baf) else radius

        val rows = mutableListOf<List<Cell>>()
        fun r(vararg cells: Cell) = rows.add(cells.toList())
        val blank = Cell("")

        r(Cell("Gcall"), Cell(gc.sampleId), blank, Cell("SE"), Cell(ecoS(gc, "line8")), Cell("BAF"))
        r(Cell("Poly"), Cell(gc.capture.poly), blank, Cell("SPM"), Cell(ecoS(gc, "line9")), Cell(radio))
        r(Cell("Map"), Cell(gc.mapSheet), blank, Cell("SMR"), Cell(ecoS(gc, "smr")), Cell("RADIUS"))
        r(Cell("Crew"), Cell(gc.sampleType), blank, Cell("SNR"), Cell(ecoS(gc, "snr")), Cell(radius))
        r(Cell("Date"), Cell(gc.entryDate), blank, Cell("MP"), Cell(ecoS(gc, "line12")), blank)
        r(Cell("Photo"), Cell(gc.capture.photo), blank, blank, blank, blank)

        r(blank, Cell("NV"), Cell("HE"), Cell("SH"), Cell("BR"), blank)
        r(Cell("CT"), Cell(ecoS(gc, "landcover")), Cell(ecoS(gc, "surface")), Cell(ecoList(gc, "cover_bytes", 1)), Cell("BR"), blank)
        r(Cell("%"), Cell(ecoS(gc, "slope")), Cell(ecoList(gc, "cover_bytes", 0)), Cell(ecoList(gc, "cover_bytes", 2)), Cell(ecoList(gc, "cover_bytes", 3)), blank)

        r(blank, blank, blank, blank, blank, blank)
        r(Cell("Layer"), blank, Cell("1"), blank, Cell("Vert Compl"), Cell(ecoList(gc, "layer_counts", 0)))
        r(Cell("Points"), Cell(gc.nPoints.toString()), Cell("Trees"), Cell(inTrees.size.toString()), Cell("CC"), Cell(ecoList(gc, "layer_cover_a", 0)))
        val outTrees = gc.liveTrees.size - inTrees.size
        r(blank, blank, Cell("Dead trees"), Cell(gc.deadTrees.size.toString()), Cell("Ocular"), Cell(if (gc.capture.ocularComments.isBlank()) "N" else "Y"))
        if (outTrees > 0) r(blank, blank, Cell("Out trees"), Cell(outTrees.toString()), blank, blank)

        r(Cell("Sp"), *codes.map { Cell(it) }.toTypedArray(), *Array(5 - codes.size) { blank })
        r(Cell("%"), *comp.map { Cell("%.0f".format(it.second)) }.toTypedArray(), *Array(5 - comp.size) { blank })

        val age1 = ageView(codes.getOrNull(0))
        val age2 = ageView(codes.getOrNull(1))
        val age3 = ageView(codes.getOrNull(2))
        r(Cell("Age"), Cell(age1), Cell(age2), Cell(age3), Cell("BA"), Cell(fmt(calc.baHaM2)))
        r(Cell("Hgt"), Cell(hgtView(codes.getOrNull(0))), Cell(hgtView(codes.getOrNull(1))), Cell(hgtView(codes.getOrNull(2))), Cell("Density"), Cell(density.toString()))

        return Sheet("Summary", List(6) { 2.3 }, rows)
    }

    private fun pointSheet(gc: GroundCall, trees: List<Tree>, point: Int): Sheet {
        val dead = trees.count { it.isDead }
        val head = Cell("Point $point  ·  ${trees.size} trees" + if (dead > 0) "  ·  $dead dead" else "")
        val header = listOf("#", "Sp", "DBH", "TCls", "CCls", "Hgt", "Age", "Dist", "Slope")
        val rows = mutableListOf<List<Cell>>()
        rows.add(listOf(head, *List(8) { Cell("") }.toTypedArray()))
        rows.add(header.map { Cell(it) })
        for (t in trees.sortedBy { it.number }) {
            rows.add(listOf(
                Cell(t.number.toString()), Cell(t.species), Cell(t.dbhRaw ?: fmt(t.dbhCm)),
                Cell(t.tcls), Cell(t.crownClass), Cell(fmt(t.heightM)),
                Cell(t.shownAge?.toString().orEmpty()), Cell(fmt(t.distM)), Cell(fmt(t.slopeDeg)),
            ))
        }
        return Sheet("Point$point", listOf(1.4, 2.0, 2.2, 1.7, 1.7, 2.2, 2.2, 2.4, 2.2), rows)
    }

    private fun commentsSheet(gc: GroundCall): Sheet {
        val rows = listOf(
            listOf(Cell("Comments"), Cell(gc.comment)),
            listOf(Cell("Ocular"), Cell(gc.capture.ocularComments)),
        )
        return Sheet("Comments", listOf(2.4, 12.0), rows)
    }

    private fun stylesXml(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-styles
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
                office:version="1.2">
                <office:styles>
                    <style:default-style style:family="table-cell">
                        <style:table-cell-properties style:vertical-align="middle"/>
                        <style:text-properties fo:font-size="10pt"/>
                    </style:default-style>
                </office:styles>
                <office:automatic-styles/>
            </office:document-styles>
        """.trimIndent() + "\n"

    private fun metaXml(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
            <office:document-meta
                xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0"
                xmlns:dc="http://purl.org/dc/elements/1.1/"
                office:version="1.2">
                <office:meta>
                    <meta:generator>GCapAndroid</meta:generator>
                    <dc:title>GCap export</dc:title>
                </office:meta>
            </office:document-meta>
        """.trimIndent() + "\n"

    private fun manifestXml(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
            <manifest:manifest
                xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"
                manifest:version="1.2">
                <manifest:file-entry manifest:full-path="/"
                    manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
                <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
            </manifest:manifest>
        """.trimIndent() + "\n"
}
