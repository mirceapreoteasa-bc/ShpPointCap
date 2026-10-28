package org.shppointcap.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerStyleTest {

    private fun spec(
        name: String,
        visible: Boolean = true,
        editable: Boolean = false,
        symbol: String = "",
        color: String = "",
    ) = LayerStyle.LayerSpec(name, visible, editable, symbol, color)

    @Test
    fun exactMatchWinsOverPrefix() {
        val specs = listOf(spec("fire"), spec("track"))
        val res = LayerStyle.match(listOf("fire.shp", "fire2.shp", "track.shp", "TRACKS.shp"), specs)
        val byFile = res.ordered.associate { it.file to it.spec?.name }
        assertEquals("fire", byFile["fire.shp"])
        assertEquals("fire", byFile["fire2.shp"]) // prefix fallback, same row
        assertEquals("track", byFile["track.shp"])
        assertEquals("track", byFile["TRACKS.shp"]) // prefix fallback
    }

    @Test
    fun dottedRowMatchesSeveralFiles() {
        val specs = listOf(spec("Air_Calls.."), spec("roads"))
        val res = LayerStyle.match(
            listOf("Air_Calls_YR_4.shp", "roads4.shp", "Air_Calls_A_Only.shp"), specs)
        val names = res.ordered.map { it.file }
        // Sheet order kept; both Air_Calls files share the first row.
        assertEquals(
            listOf("Air_Calls_A_Only.shp", "Air_Calls_YR_4.shp", "roads4.shp"),
            names,
        )
    }

    @Test
    fun unknownFilesGoLastVisible() {
        val specs = listOf(spec("XGV_MP"), spec("prov_parks"))
        val res = LayerStyle.match(listOf("XGV_MP.shp", "zzz_new.shp", "prov_parks4.shp"), specs)
        val names = res.ordered.map { it.file }
        assertEquals(listOf("XGV_MP.shp", "prov_parks4.shp", "zzz_new.shp"), names)
        val last = res.ordered.last()
        assertNull(last.spec)
    }

    @Test
    fun activeEditablePrefersXgvMp() {
        val specs = listOf(
            spec("XGV_MP", editable = true),
            spec("track", editable = true),
            spec("POINTS", editable = true),
        )
        val res = LayerStyle.match(listOf("POINTS.shp", "track.shp", "XGV_MP.shp"), specs)
        assertEquals("XGV_MP.shp", res.activeEditable)
    }

    @Test
    fun activeEditableFallsBackToPoints() {
        val specs = listOf(
            spec("track", editable = true),
            spec("POINTS", editable = true),
        )
        val res = LayerStyle.match(listOf("POINTS.shp", "track.shp"), specs)
        assertEquals("POINTS.shp", res.activeEditable)
    }

    @Test
    fun activeEditableFallsBackToFirst() {
        val specs = listOf(spec("XGV_MP", editable = true))
        val res = LayerStyle.match(listOf("XGV_MP.shp", "POINTS.shp"), specs)
        assertEquals("XGV_MP.shp", res.activeEditable)
    }

    @Test
    fun symbolAliases() {
        assertEquals(MapCanvasView.PointSymbol.TRIANGLE, LayerStyle.pointSymbolOf("triangle"))
        assertEquals(MapCanvasView.PointSymbol.CIRCLE, LayerStyle.pointSymbolOf("dot"))
        assertEquals(MapCanvasView.PointSymbol.CIRCLE, LayerStyle.pointSymbolOf("whatever"))
        assertEquals(MapCanvasView.LineSymbol.DASH, LayerStyle.lineSymbolOf("dash"))
        assertEquals(MapCanvasView.LineSymbol.DOT, LayerStyle.lineSymbolOf("doted line"))
        assertEquals(MapCanvasView.LineSymbol.DOUBLE_ROAD, LayerStyle.lineSymbolOf("double line topo road"))
        assertEquals(MapCanvasView.LineSymbol.SIMPLE, LayerStyle.lineSymbolOf("single line"))
        assertEquals(MapCanvasView.PolySymbol.HATCH_45, LayerStyle.polySymbolOf("Hashed /"))
        assertEquals(MapCanvasView.PolySymbol.HATCH_NEG45, LayerStyle.polySymbolOf("Hashed \\"))
        assertEquals(MapCanvasView.PolySymbol.HATCH_V, LayerStyle.polySymbolOf("hashed vertically"))
        assertEquals(MapCanvasView.PolySymbol.HATCH_H, LayerStyle.polySymbolOf("hashed horizontally"))
        assertEquals(MapCanvasView.PolySymbol.DOT, LayerStyle.polySymbolOf("pattern dot"))
        assertEquals(MapCanvasView.PolySymbol.NOFILL, LayerStyle.polySymbolOf("no fill"))
        assertEquals(MapCanvasView.PolySymbol.FILL, LayerStyle.polySymbolOf("simple fill"))
    }

    @Test
    fun colorsAndBorderSyntax() {
        assertEquals(0xFFCC6600.toInt(), LayerStyle.colorOf("orange"))
        assertEquals(0xFFD6A800.toInt(), LayerStyle.colorOf("Yellow"))
        assertEquals(0xFF888888.toInt(), LayerStyle.colorOf("notacolor"))
        assertEquals(0xFF2E7D32.toInt(), LayerStyle.borderOf("green border"))
        assertNull(LayerStyle.borderOf("blue"))
    }

    @Test
    fun jsonRoundTrip() {
        val specs = listOf(
            LayerStyle.LayerSpec("XGV_MP", true, false, "triangle", "orange", 2f, "GCALL_ID", 7f),
            LayerStyle.LayerSpec("grid", false, false, "no fill", "green border", 0.6f, null, null),
            LayerStyle.LayerSpec("plain", true, false, "", "", null, null, null),
        )
        val back = LayerStyle.parseProject(LayerStyle.toJson(specs))
        assertEquals(specs, back)
    }

    @Test
    fun explicitJsonNullsParseAsAbsent() {
        // Python's json emits null for blanks; Android's optString() turns an
        // explicit JSON null into the literal string "null" — must not leak
        // into fields (seen live as Label="null" in the settings page).
        val specs = LayerStyle.parseProject(
            """{"layers": [{"name":"track","visible":true,"symbol":"dash",
               "color":"red","size":0.2,"label":null,"labelSize":null}]}""",
        )
        assertEquals(1, specs.size)
        assertNull(specs[0].labelField)
        assertNull(specs[0].labelSizeSp)
        assertEquals(0.2f, specs[0].sizeDp!!, 0.001f)
    }

    @Test
    fun blankNumericsStayBlank() {
        val specs = LayerStyle.parseProject(
            """{"layers": [{"name":"rivers","visible":false,"symbol":"simple fill",
               "color":"blue","size":"","label":"","labelSize":""}]}""",
        )
        assertNull(specs[0].sizeDp)
        assertNull(specs[0].labelField)
        assertNull(specs[0].labelSizeSp)
    }

    @Test
    fun settingsDefaultsMatchSheet() {
        val s = LayerStyle.parseSettings("{}")
        assertEquals("", s.workingCrs)
        assertEquals(40, s.autosaveSeconds)
        assertEquals(10, s.trackIntervalSeconds)
        assertEquals(5.0, s.trackMinDistanceM, 0.001)
        assertEquals(500, s.zoomMin)
        assertEquals(400000, s.zoomMax)
        assertEquals(10000, s.defaultScale)
        assertEquals("Ground_Call", s.goToLayer)
        assertEquals("Call_Num", s.goToColumn)
        assertEquals("XGV_MP", s.addPointLayer)
        assertEquals("POINTS", s.mobLayer)
        assertEquals("track", s.trackLayer)
    }

    @Test
    fun blankAutosaveStaysOffAcrossRoundTrip() {
        val s = LayerStyle.parseSettings("""{"settings":{"autosaveSeconds":null}}""")
        assertNull(s.autosaveSeconds)
        val json = "{\"settings\":" + LayerStyle.settingsToJson(s).toString() + "}"
        assertNull(LayerStyle.parseSettings(json).autosaveSeconds)
    }

    @Test
    fun resolveWorkingZonePrefersExplicitThenLiveThenFirst() {
        assertEquals(9, LayerStyle.resolveWorkingZone("UTM Zone 9N", -124.9, 10))
        assertEquals(10, LayerStyle.resolveWorkingZone("WGS84", -123.15, 9))
        assertEquals(10, LayerStyle.resolveWorkingZone("", null, 10))
        assertEquals(10, LayerStyle.resolveWorkingZone("", null, null))
        assertEquals(400000, LayerStyle.FIXED_SCALES.last())
        assertTrue(LayerStyle.FIXED_SCALES.contains(100000))
    }

    @Test
    fun docMergesPreserveTheOtherHalf() {
        val withLayers = LayerStyle.docWithLayers(
            """{"settings":{"autosaveSeconds":40}}""",
            listOf(LayerStyle.LayerSpec("XGV_MP")),
        )
        // Layers added, settings untouched.
        assertEquals(40, LayerStyle.parseSettings(withLayers).autosaveSeconds)
        assertEquals(1, LayerStyle.parseProject(withLayers).size)

        val withSettings = LayerStyle.docWithSettings(
            withLayers, LayerStyle.Settings(autosaveSeconds = null))
        // Settings replaced, layers untouched.
        assertNull(LayerStyle.parseSettings(withSettings).autosaveSeconds)
        assertEquals(listOf("XGV_MP"), LayerStyle.parseProject(withSettings).map { it.name })
    }

    @Test
    fun parseProjectReadsSheetExport() {
        val json = """
            {"layers": [
              {"name":"XGV_MP","visible":true,"editable":false,"symbol":"triangle",
               "color":"orange","size":2.0,"label":"GCALL_ID","labelSize":7.0},
              {"name":"grid","visible":false,"symbol":"no fill","color":"green border","size":0.6}
            ]}
        """.trimIndent()
        val specs = LayerStyle.parseProject(json)
        assertEquals(2, specs.size)
        assertEquals("XGV_MP", specs[0].name)
        assertTrue(specs[0].visible)
        assertEquals(2.0f, specs[0].sizeDp!!, 0.001f)
        assertEquals("GCALL_ID", specs[0].labelField)
        assertEquals("green border", specs[1].colorRaw)
    }

    @Test
    fun duplicateSpecRowsDoNotDuplicateFiles() {
        // project.json with the same row twice (e.g. after a Remove-layer
        // write raced a Layer-settings Save): each file must still appear
        // exactly once, claimed by the first row in sheet order.
        val specs = listOf(spec("track", visible = false), spec("track", visible = true))
        val res = LayerStyle.match(listOf("track.shp", "TRACKS.shp"), specs)
        assertEquals(2, res.ordered.size)
        assertEquals(
            listOf("TRACKS.shp", "track.shp"),
            res.ordered.map { it.file }.sorted(),
        )
        // The first row owns both files; the duplicate row gets nothing and is
        // dropped from the Layer settings list (self-healing on next Save).
        assertTrue(res.ordered.all { it.spec == specs[0] })
    }
}
