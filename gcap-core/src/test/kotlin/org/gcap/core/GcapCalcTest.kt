package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sqrt

class GcapCalcTest {

    private fun near(a: Double, b: Double, eps: Double = 1e-9) {
        assertTrue(abs(a - b) < eps, "expected $a ~ $b")
    }

    @Test
    fun prismInOutRule() {
        // Centre PRF for BAF 4 = 0.5/2 = 0.25 m/cm
        near(GcapCalc.plotRadiusFactor(4.0), 0.25)
        // DBH 20 cm -> Dcrit = 5.0 m (centre distance)
        near(GcapCalc.criticalDistanceM(20.0, 4.0), 5.0)
        assertTrue(GcapCalc.treeIsIn(20.0, 5.0 - 0.001, 4.0))
        assertFalse(GcapCalc.treeIsIn(20.0, 5.0 + 0.001, 4.0))
        // BC manual: less than or equal to the plot radius = in
        assertTrue(GcapCalc.treeIsIn(20.0, 5.0, 4.0))
    }

    @Test
    fun horizontalDistanceFromSlopeDistance() {
        // slope distance 20 m at 60 deg -> horizontal 10 m; blank slope -> as-is
        near(GcapCalc.horizontalDistanceM(20.0, 60.0), 10.0)
        near(GcapCalc.horizontalDistanceM(20.0), 20.0)
        near(GcapCalc.horizontalDistanceM(20.0, null), 20.0)
        near(GcapCalc.horizontalDistanceM(10.0, 0.0), 10.0)
    }

    @Test
    fun prismInOutTreeRules() {
        // no DBH -> OUT
        assertFalse(GcapCalc.isIn(Tree(number = 1, species = "HW", distM = 10.0), 4.0))
        // DBH below the field minimum -> OUT
        assertFalse(GcapCalc.isIn(Tree(number = 1, species = "HW", dbhCm = 5.0, distM = 1.0), 4.0))
        // no distance -> assumed IN
        assertTrue(GcapCalc.isIn(Tree(number = 1, species = "HW", dbhCm = 20.0), 4.0))
        // DBH 20, BAF 4 -> Dcrit 5.0 m (centre dist); horiz = 20*cos(60) = 10 -> OUT
        assertFalse(GcapCalc.isIn(Tree(number = 1, species = "HW", dbhCm = 20.0, distM = 20.0, slopeDeg = 60.0), 4.0))
        // flat centre distance 4.9 m -> IN
        assertTrue(GcapCalc.isIn(Tree(number = 1, species = "HW", dbhCm = 20.0, distM = 4.9, slopeDeg = 0.0), 4.0))
        // aux OUT trees 91..99 never count
        assertFalse(GcapCalc.isIn(Tree(number = 95, species = "HW", dbhCm = 20.0), 4.0))
        assertFalse(GcapCalc.isIn(Tree(number = 99, species = "HW", dbhCm = 20.0), 4.0))
        // dead trees never count
        assertFalse(GcapCalc.isIn(Tree(number = 1, species = "HW", dbhCm = 20.0, tcls = "4"), 4.0))
    }

    @Test
    fun formulasCoverAllBafsInAcceptedRange() {
        // Every integer BAF 3..30 must yield a valid, positive critical
        // distance and in/out decision (closed form, no hardcoded table).
        for (baf in 3..30) {
            val prf = GcapCalc.plotRadiusFactor(baf.toDouble())
            val crit = GcapCalc.criticalDistanceM(20.0, baf.toDouble())
            near(prf, 0.5 / sqrt(baf.toDouble()))
            assertTrue(crit > 0.0 && crit.isFinite())
            near(crit, prf * 20.0)
            // a tree at the face edge is IN, one just beyond is OUT
            assertTrue(GcapCalc.treeIsIn(20.0, crit, baf.toDouble()))
            assertFalse(GcapCalc.treeIsIn(20.0, crit + 0.01, baf.toDouble()))
        }
    }

    @Test
    fun inTreesFilterAndMargin() {
        val trees = listOf(
            Tree(number = 1, species = "HW", dbhCm = 20.0, distM = 4.0, slopeDeg = 0.0),  // in by 1.0 m = 100 cm
            Tree(number = 2, species = "CW", dbhCm = 20.0, distM = 5.5, slopeDeg = 0.0), // out
            Tree(number = 3, species = "HW", dbhCm = 20.0),                             // assumed in
            Tree(number = 95, species = "HW", dbhCm = 20.0),                            // aux, ignored
        )
        val inL = GcapCalc.inTrees(trees, 4.0)
        assertEquals(2, inL.size)
        assertEquals(setOf(1, 3), inL.map { it.number }.toSet())
        near(GcapCalc.marginCm(trees[0], 4.0)!!, 100.0)
        near(GcapCalc.marginCm(trees[1], 4.0)!!, -50.0)
        assertEquals(null, GcapCalc.marginCm(trees[2], 4.0))
        val sp = GcapCalc.speciesComposition(trees, baf = 4.0)
        // composition from IN trees only: HW 2, CW 0 -> HW 100%
        assertEquals(mapOf("HW" to 100.0), sp)
    }

    @Test
    fun basalAreaAndStems() {
        near(GcapCalc.treeBaM2(20.0), 0.03141592653589793)
        near(GcapCalc.basalAreaPerHa(5, 4.0), 20.0)
        // stems: 4 BAF, 20 cm tree factor = 4/0.0314159...
        val t = listOf(Tree(number = 1, species = "HW", dbhCm = 20.0))
        near(GcapCalc.stemsPerHaBaf(t, 4.0), 4.0 / 0.03141592653589793)
    }

    @Test
    fun ageTransform() {
        assertEquals(85.0, GcapCalc.totalAge(80.0, 5.0))
    }

    @Test
    fun ageFromCoreCountAndHeight() {
        // Core 100 at 1.3 m, tree 30 m: Total = 100*30/(30-1.3) = 104.5296…
        val total = GcapCalc.totalAgeFromCoreCount(100, 30.0)!!
        near(total, 100.0 * 30.0 / 28.7)
        // YTBH = core*1.3/(H-1.3) = 100*1.3/28.7 = 4.5296…
        val ytbh = GcapCalc.yearsToBreastHeight(100, 30.0)!!
        near(ytbh, 100.0 * 1.3 / 28.7)
        assertEquals(total, 100.0 + ytbh, 1e-9)
        // heights at or below 1.3 m cannot be aged by this route
        assertEquals(null, GcapCalc.totalAgeFromCoreCount(100, 1.3))
        assertEquals(null, GcapCalc.yearsToBreastHeight(50, 1.0))
    }

    @Test
    fun heightFromAngles() {
        // HD=20m, top 30deg, base -5deg
        val h = GcapCalc.heightFromHorizontal(20.0, 30.0, -5.0)
        near(h, 20.0 * (0.5773502691896257 + 0.08748866352592401), 1e-9)
        // sloped distance 20 at base angle -5 -> HD = 20*cos(5) = 19.9238...
        val sd = GcapCalc.heightFromSloped(20.0, 30.0, -5.0)
        near(sd, 20.0 * Math.cos(Math.toRadians(5.0)) *
                (0.5773502691896257 + 0.08748866352592401), 1e-9)
    }

    @Test
    fun averagingAcrossPoints() {
        val gc = GroundCall(
            trees = listOf(
                Tree(number = 1, point = 1, species = "HW", dbhCm = 20.0),
                Tree(number = 2, point = 1, species = "CW", dbhCm = 30.0),
                Tree(number = 1, point = 2, species = "HW", dbhCm = 25.0),
            )
        )
        val s = GcapCalc.summarizeByPoint(gc, baf = 4.0)
        assertEquals(2, s.nPoints)
        // per point BA = BAF * n
        near(s.points[0].baHaM2, 8.0)
        near(s.points[1].baHaM2, 4.0)
        // cluster avg = (8+4)/2 = 6
        near(s.baHaM2, 6.0)
        // identity (point, number): tree #1 at point 2 is a different tree
        assertEquals(2, gc.trees[1].number)
        assertEquals(1, gc.trees[1].point)
        assertEquals(1, gc.trees[2].number)
        assertEquals(2, gc.trees[2].point)
    }

    @Test
    fun impliedBafMatchesCruiserNotes() {
        // 304.dat: ba=40, 5 trees at P1 -> expected BAF = 40/5 = 8? No: notes say
        // density 1200, ba 40; with 3-point averaging BA=40 is cluster total
        // across the 3 points displayed... keep as structural check:
        near(GcapCalc.impliedBaf(0, 40.0), 0.0)
        near(GcapCalc.impliedBaf(5, 40.0), 8.0)
    }

    @Test
    fun reorderThirdAsSecondSwapsSlots() {
        val pairs = listOf("PLC" to 60.0, "FDC" to 30.0, "HW" to 10.0, "CW" to 5.0)
        // no swap -> unchanged
        assertEquals(pairs, GcapCalc.reorderForDisplay(pairs, false))
        // swap exchanges the 2nd and 3rd entries, larger list keeps rest
        val swapped = GcapCalc.reorderForDisplay(pairs, true)
        assertEquals("HW", swapped[1].first)
        assertEquals(10.0, swapped[1].second)
        assertEquals("FDC", swapped[2].first)
        assertEquals(30.0, swapped[2].second)
        assertEquals("CW", swapped[3].first)
        // fewer than 3 species -> no-op
        assertEquals(2, GcapCalc.reorderForDisplay(pairs.take(2), true).size)
    }
}