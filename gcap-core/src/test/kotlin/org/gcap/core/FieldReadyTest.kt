package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FieldReadyTest {

    @Test
    fun typedFieldsRoundTripThroughSlots() {
        val t = Tree(
            number = 3, species = "FDC", dbhCm = 27.0, dbhRaw = "27.0",
            crownClass = "c", measured = true, point = 1,
            tcls = "4", heightM = 27.5, distM = 5.5, ageCore = 117,
            ageCor = 11.5, slopeDeg = 12.0,
        )
        val gc = GroundCall(
            version = "v5.1.1 6/8/26", sampleId = "999", mapSheet = "092K096",
            sampleType = "mp", entryDate = "6/3/26", trees = listOf(t),
            headerLines = DatParser.parseText(
                DatWriter.render(GroundCall(version = "v5.1.1 6/8/26", sampleId = "999", mapSheet = "092K096", sampleType = "mp", entryDate = "6/3/26"))
            ).headerLines,
        )
        val rendered = DatWriter.render(gc)
        val back = DatParser.parseText(rendered).trees.single()

        assertEquals("4", back.tcls)
        assertEquals(27.5, back.heightM)
        assertEquals(5.5, back.distM)
        assertEquals(117, back.ageCore)
        assertEquals(11.5, back.ageCor)
        assertEquals(12.0, back.slopeDeg)
        assertEquals("c", back.crownClass)
        // measured flag quirk: last record of the file has no flag line
        assertTrue(back.measured == false)
    }

    @Test
    fun measuredFlagQuirk() {
        // only the LAST record in the file omits its measured flag
        val t1 = Tree(number = 1, species = "FDC", dbhCm = 20.0, measured = true)
        val t2 = Tree(number = 2, species = "FDC", dbhCm = 21.0, measured = true)
        val gc = GroundCall(version = "v5.1.1 6/8/26", sampleId = "x", sampleType = "mp",
            trees = listOf(t1, t2), headerLines = TestUtil.minimalHeader())
        val back = DatParser.parseText(DatWriter.render(gc)).trees
        assertEquals(true, back[0].measured)
        assertEquals(false, back[1].measured)   // quirk: last tree flag not written
    }

    @Test
    fun heightFormattingIsPreserved() {
        // parsed "27.0" stays "27.0"; a freshly entered 27.0 writes canonical "27"
        val fromFile = Tree(number = 1, species = "FDC", dbhCm = 34.0, dbhRaw = "34",
            heightM = 27.0, slots = listOf("", "", "", "", "27.0"))
        val fresh = Tree(number = 1, species = "FDC", dbhCm = 34.0, dbhRaw = "34", heightM = 27.0)
        fun lines(t: Tree): List<String> {
            val gc = GroundCall(version = "v5.1.1 6/8/26", sampleId = "x", sampleType = "mp",
                trees = listOf(t), headerLines = TestUtil.minimalHeader())
            return DatWriter.render(gc).split("\r\n")
        }
        assertEquals("27.0", lines(fromFile)[149 + 7])
        assertEquals("27", lines(fresh)[149 + 7])
    }

    @Test
    fun legacyCrownAtSlot3IsPreserved() {
        // files 054/308 stored the crown letter at slot offset 3
        val t = Tree(number = 1, species = "FDC", dbhCm = 25.2, slots = listOf("c", "", "", "", "15.2", "", "117", "11.5", "", ""))
        val gc = GroundCall(
            version = "v5.1.1 6/8/26", sampleId = "x", sampleType = "mp",
            trees = listOf(t), headerLines = TestUtil.minimalHeader(),
        )
        val rendered = DatWriter.render(gc)
        val back = DatParser.parseText(rendered).trees.single()
        assertEquals("", back.crownClass)      // slot 3 legacy, not slot 13
        assertEquals("c", back.slots.getOrNull(0))
        assertEquals(15.2, back.heightM)
        assertEquals(117, back.ageCore)
        assertEquals(11.5, back.ageCor)
        // untouched render keeps the legacy 'c' at slot 3
        assertTrue(rendered.split("\r\n")[149 + 3] == "c")
    }

    @Test
    fun validationRanges() {
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.species("!" ).level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.species("ZZZ").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.species("HW").level)

        assertEquals(FieldChecks.Level.ERROR, FieldChecks.dbhText("7.0").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.dbhText("500.9").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.dbhText("7.4").level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.dbh(null).level)

        assertEquals(FieldChecks.Level.RED, FieldChecks.crownClass("i").level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.crownClass("x").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.crownClass("d").level)

        assertEquals(FieldChecks.Level.RED, FieldChecks.treeClass("3").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.treeClass("4").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.treeClass("").level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.treeClass("x").level)   // unusual but alive
        assertEquals(FieldChecks.Level.RED, FieldChecks.treeClass("d").level)  // crown letter in TCls is a warning

        assertEquals(FieldChecks.Level.OK, FieldChecks.crownClass("c").level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.crownClass("s").level) // Category II

        assertEquals(FieldChecks.Level.RED, FieldChecks.height(2.9).level)   // <3.0 red
        assertEquals(FieldChecks.Level.RED, FieldChecks.height(55.5).level)  // >55 red
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.height(1.4).level) // <1.5
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.height(91.0).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.height(12.0).level)

        assertEquals(FieldChecks.Level.ERROR, FieldChecks.dist(4.9).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.dist(5.0).level)

        assertEquals(FieldChecks.Level.ERROR, FieldChecks.vc(6).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.vc(3).level)

        assertEquals(FieldChecks.Level.ERROR, FieldChecks.cc(17.0).level)   // not a step of 5
        assertEquals(FieldChecks.Level.RED, FieldChecks.cc(15.0).level)     // <20 red
        assertEquals(FieldChecks.Level.OK, FieldChecks.cc(30.0).level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.cc(95.5).level)

        assertEquals(FieldChecks.Level.ERROR, FieldChecks.snagsCount(3001).level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.snagsCount(-1).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.snagsCount(150).level)

        assertEquals(FieldChecks.Level.ERROR, FieldChecks.comments("x".repeat(351)).level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.ocular("a,b").level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.ocular("x".repeat(101)).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.ocular("no commas here").level)
    }

    @Test
    fun sampleNameStandardFormat() {
        assertEquals(FieldChecks.Level.OK, FieldChecks.sampleName("0508").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.sampleName("0508-").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.sampleName("0508b").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.sampleName("0508-a").level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.sampleName("508").level)   // 3 digits ok
        assertEquals(FieldChecks.Level.RED, FieldChecks.sampleName("50").level)  // 2 digits
        assertEquals(FieldChecks.Level.RED, FieldChecks.sampleName("12345").level) // 5 digits
        assertEquals(FieldChecks.Level.RED, FieldChecks.sampleName("0508-B").level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.sampleName("my01").level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.sampleName("").level)
    }

    @Test
    fun bafReminderAndApprovedList() {
        val gc = GroundCall(version = "v", sampleId = "1", sampleType = "mp")
        val issues = FieldChecks.sample(gc)
        assertTrue(issues.any { it.message.contains("BAF is empty") }, "must remind when BAF empty")
        assertEquals(FieldChecks.Level.RED, FieldChecks.bafApproved(40.0).level)
        assertEquals(FieldChecks.Level.RED, FieldChecks.bafApproved(2.0).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.bafApproved(3.0).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.bafApproved(7.0).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.bafApproved(30.0).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.bafApproved(6.25).level)
    }

    @Test
    fun heightWithCorrection() {
        // sd=20m base=-5deg cor=1.3m top=+40deg
        val r = Math::toRadians
        val expectedNoCor = 20.0 * kotlin.math.cos(r(-5.0)) *
            (kotlin.math.tan(r(40.0)) - kotlin.math.tan(r(-5.0)))
        val h = GcapCalc.heightFromSloped(20.0, 40.0, -5.0, 1.3)
        assertEquals(expectedNoCor + 1.3, h, 1e-6)
    }

    @Test
    fun compositionVariableVsFixed() {
        val t1 = Tree(number = 1, species = "FDC", dbhCm = 20.0)
        val t2 = Tree(number = 2, species = "FDC", dbhCm = 10.0)
        val t3 = Tree(number = 3, species = "HW", dbhCm = 10.0)
        // variable radius: by count -> FDC 67%, HW 33%
        val compC = GcapCalc.speciesComposition(listOf(t1, t2, t3), useBasalArea = false)
        assertEquals(66.7, compC["FDC"]!!, 0.1)
        assertEquals(33.3, compC["HW"]!!, 0.1)
        // fixed radius: by basal area -> FDC dominates even more
        val compBa = GcapCalc.speciesComposition(listOf(t1, t2, t3), useBasalArea = true)
        assertTrue(compBa["FDC"]!! > compC["FDC"]!!)
    }

    @Test
    fun deadTreesExcludedFromLiveSummary() {
        val live = Tree(number = 1, species = "FDC", dbhCm = 20.0, tcls = "")
        val dead = Tree(number = 2, species = "HW", dbhCm = 20.0, tcls = "4")
        val gc = GroundCall(version = "v", sampleId = "1", sampleType = "mp", trees = listOf(live, dead))
        val liveOnly = gc.liveTrees
        assertEquals(1, liveOnly.size)
        val comp = GcapCalc.speciesComposition(gc.trees)
        assertEquals(setOf("FDC"), comp.keys)
    }

    @Test
    fun meanAgeAndHeightBySpecies() {
        val a = Tree(number = 1, species = "FDC", dbhCm = 20.0, ageCore = 100, ageCor = 11.5, heightM = 20.0)
        val b = Tree(number = 2, species = "FDC", dbhCm = 20.0, ageCore = 110, ageCor = 11.5, heightM = 30.0)
        val ages = GcapCalc.meanTotalAgeBySpecies(listOf(a, b))
        assertEquals((111.5 + 121.5) / 2, ages["FDC"]!!, 1e-6)
        val hs = GcapCalc.meanHeightBySpecies(listOf(a, b))
        assertEquals(25.0, hs["FDC"]!!, 1e-6)
    }
}

object TestUtil {
    fun minimalHeader(): List<String> {
        val lines = MutableList(149) { "" }
        lines[0] = "v5.1.1 6/8/26"; lines[1] = "3"; lines[2] = "999"
        lines[4] = "092K096"; lines[5] = "mp"; lines[6] = "6/3/26"
        lines[141] = "10"; lines[148] = "1"
        return lines
    }

    // suppress unused warnings for assertNull import parity
    fun unused() { }
}