package org.gcap.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AllXgvSummaryExporter {

    fun generate(appStorage: GCapStorage, samples: List<GroundCall>): Pair<String, ByteArray> {
        val sortedSamples = samples.sortedBy { it.sampleId.lowercase() }
        val dateStr = SimpleDateFormat("ddMMMyyyy", Locale.US).format(Date()).lowercase()
        val filenameBase = "${dateStr}_all_xgv"

        val headers = listOf(
            "CALL_TYPE,C,254", "CALL_NUM,N,16,6", "CALL_YEAR,N,16,6", "LAYER,N,16,6",
            "OCULAR_SPE,C,254", "VERT_COMPL,N,16,6",
            "SPCS_1,C,254", "SP1_PCT,N,16,6", "SPCS_2,C,254", "SP2_PCT,N,16,6",
            "SPCS_3,C,254", "SP3_PCT,N,16,6", "SPCS_4,C,254", "SP4_PCT,N,16,6",
            "SPCS_5,C,254", "SP5_PCT,N,16,6", "SPCS_6,C,254", "SP6_PCT,N,16,6",
            "SP1_AGE,N,16,6", "SP1_HT,N,16,6", "SP2_AGE,N,16,6", "SP2_HT,N,16,6",
            "BASAL_AREA,N,16,6", "DENSITY,N,16,6", "SNAGS,N,16,6",
            "SHRUB_HT,N,16,6", "SHRUB_CC,N,16,6", "HERB_TYPE,C,254", "HERB_PCT,N,16,6",
            "BRYOID_PCT,N,16,6", "NON_VEG_TY,C,254", "NVC_PCT,N,16,6",
            "SP_MESO,C,254", "SOIL_MOIS,C,254", "SOIL_NUT,C,254", "COMMENTS,C,254",
            "CALL_CENTE,N,16,6", "CALL_CEN_1,N,16,6", "CALL_CEN_2,N,16,6"
        )

        val rows = mutableListOf<List<OdsExporter.Cell>>()
        rows.add(headers.map { OdsExporter.Cell(it) })

        for (gc in sortedSamples) {
            // Dat line 6 is crew, not call type: every row in all_xgv is XGV.
            val callType = "XGV"
            // CALL_NUM is text: keep leading zeros ("024"), never Double ("24.0").
            val callNum = gc.sampleId
            val year = parseYear(gc.entryDate)

            val baf = gc.capture.baf ?: 4.0
            val comp = GcapCalc.speciesComposition(gc.trees, baf = baf).toList()
            val ages = GcapCalc.meanTotalAgeBySpecies(gc.trees)
            val hgts = GcapCalc.meanHeightBySpecies(gc.trees)

            val spCodes = comp.map { it.first }
            val spPcts = comp.map { it.second }

            val sp1 = spCodes.getOrNull(0).orEmpty()
            val p1 = spPcts.getOrNull(0) ?: 0.0
            val sp2 = spCodes.getOrNull(1).orEmpty()
            val p2 = spPcts.getOrNull(1) ?: 0.0
            val sp3 = spCodes.getOrNull(2).orEmpty()
            val p3 = spPcts.getOrNull(2) ?: 0.0
            val sp4 = spCodes.getOrNull(3).orEmpty()
            val p4 = spPcts.getOrNull(3) ?: 0.0
            val sp5 = spCodes.getOrNull(4).orEmpty()
            val p5 = spPcts.getOrNull(4) ?: 0.0
            val sp6 = spCodes.getOrNull(5).orEmpty()
            val p6 = spPcts.getOrNull(5) ?: 0.0

            val age1 = sp1.let { ages[it] } ?: 0.0
            val ht1 = sp1.let { hgts[it] } ?: 0.0
            val age2 = sp2.let { ages[it] } ?: 0.0
            val ht2 = sp2.let { hgts[it] } ?: 0.0

            val calc = GcapCalc.summarizeByPoint(gc, baf)
            val ba = calc.baHaM2
            val density = calc.points.map { it.stemsHa }.average().takeIf { it.isFinite() } ?: 0.0

            val snagsTotal = gc.capture.snags.values.sum()
            // Dat lines 17-20 live in ecology "cover_bytes": 0 herb %, 1 shrub ht,
            // 2 shrub cc, 3 bryoid %. Dat line 15 ("slope") is the non-veg %.
            val shrubHt = ecoCoverRaw(gc, 1).ifEmpty { "0" }
            val shrubCc = ecoCover(gc, 2)
            val herbType = ecoS(gc, "surface")
            val herbPct = ecoCover(gc, 0)
            val bryoidPct = ecoCover(gc, 3)
            val nonVegTy = ecoS(gc, "landcover")
            val nvcPct = ecoD(gc, "slope")
            // Same value the Summary page shows (dat line 52).
            val vertCompl = ecoList0(gc, "layer_counts")
            val spMeso = ecoS(gc, "line9")
            val soilMois = ecoS(gc, "smr")
            val soilNut = ecoS(gc, "snr")
            val comments = gc.comment.ifBlank { gc.capture.ocularComments }

            val chosenPt = gc.capture.utmChosenIndex?.let { gc.capture.utmPoints.getOrNull(it) }
                ?: gc.capture.utmPoints.lastOrNull()
            // New sidecar points win; old files fall back to .dat lines 143/144.
            val easting = chosenPt?.easting ?: gc.easting ?: 0.0
            val northing = chosenPt?.northing ?: gc.northing ?: 0.0
            // Zone: sidecar point, else sidecar zone, else .dat line 142.
            val zoneRaw = chosenPt?.zone?.takeIf { it.isNotBlank() }
                ?: gc.capture.utmZone.takeIf { it.isNotBlank() }
                ?: gc.headerLines?.getOrNull(141).orEmpty()
            val zone = zoneRaw.filter { it.isDigit() }.ifEmpty { "0" }

            rows.add(listOf(
                OdsExporter.Cell(callType),
                OdsExporter.Cell(callNum),
                OdsExporter.Cell(year.toString()),
                OdsExporter.Cell("1"),
                OdsExporter.Cell(gc.capture.ocularComments.ifBlank { gc.compositionRaw }),
                OdsExporter.Cell(intStr(vertCompl)),
                OdsExporter.Cell(sp1), OdsExporter.Cell(intStr(p1)),
                OdsExporter.Cell(sp2), OdsExporter.Cell(intStr(p2)),
                OdsExporter.Cell(sp3), OdsExporter.Cell(intStr(p3)),
                OdsExporter.Cell(sp4), OdsExporter.Cell(intStr(p4)),
                OdsExporter.Cell(sp5), OdsExporter.Cell(intStr(p5)),
                OdsExporter.Cell(sp6), OdsExporter.Cell(intStr(p6)),
                OdsExporter.Cell(intStr(age1)), OdsExporter.Cell(oneDec(ht1)),
                OdsExporter.Cell(intStr(age2)), OdsExporter.Cell(oneDec(ht2)),
                OdsExporter.Cell(intStr(ba)), OdsExporter.Cell(intStr(density)),
                OdsExporter.Cell(snagsTotal.toString()),
                OdsExporter.Cell(shrubHt), OdsExporter.Cell(intStr(shrubCc)),
                OdsExporter.Cell(herbType), OdsExporter.Cell(intStr(herbPct)),
                OdsExporter.Cell(intStr(bryoidPct)), OdsExporter.Cell(nonVegTy),
                OdsExporter.Cell(intStr(nvcPct)), OdsExporter.Cell(spMeso),
                OdsExporter.Cell(soilMois), OdsExporter.Cell(soilNut),
                OdsExporter.Cell(comments),
                OdsExporter.Cell(zone), OdsExporter.Cell(easting.toString()), OdsExporter.Cell(northing.toString())
            ))
        }

        val sheet = OdsExporter.Sheet("Sheet1", List(headers.size) { 2.0 }, rows)
        val sheets = listOf(sheet)

        val odsBytes = OdsExporter.packageSheets(sheets)
        val xlsxBytes = XlsxExporter.packageBytes(sheets)

        appStorage.writeSubFolderBytes("XGV_ODS_XLSX", "$filenameBase.ods", odsBytes)
        appStorage.writeSubFolderBytes("XGV_ODS_XLSX", "$filenameBase.xlsx", xlsxBytes)

        return "$filenameBase.ods" to odsBytes
    }

    private fun parseYear(date: String): Int {
        val p = date.split(Regex("[/.-]"))
        if (p.size >= 3) {
            val y = p[2].toIntOrNull() ?: 2026
            return if (y < 100) 2000 + y else y
        }
        return 2026
    }

    private fun ecoS(gc: GroundCall, key: String): String =
        (gc.ecology[key] as? String).orEmpty()

    private fun ecoD(gc: GroundCall, key: String): Double =
        (gc.ecology[key] as? String)?.toDoubleOrNull() ?: 0.0

    /** Raw text of dat lines 17-20 (cover_bytes[idx]); keeps "2.5" verbatim. */
    private fun ecoCoverRaw(gc: GroundCall, idx: Int): String =
        (gc.ecology["cover_bytes"] as? List<*>)?.getOrNull(idx)?.toString().orEmpty()

    private fun ecoCover(gc: GroundCall, idx: Int): Double =
        ecoCoverRaw(gc, idx).toDoubleOrNull() ?: 0.0

    /** First value of a per-point list (e.g. layer_counts[0], dat line 52). */
    private fun ecoList0(gc: GroundCall, key: String): Double =
        (gc.ecology[key] as? List<*>)?.getOrNull(0)?.toString()?.toDoubleOrNull() ?: 0.0

    private fun intStr(d: Double): String = Math.round(d).toString()

    private fun oneDec(d: Double): String = "%.1f".format(Locale.US, d)
}
