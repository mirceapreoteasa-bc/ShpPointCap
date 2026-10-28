package org.gcap.core

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Derived metrics for GCap ground calls — BC VRI sampling.
 *
 * Each sample consists of 1..n measurement points (P1/P2/P3...). Trees are
 * tallied at every point with the same in/out rule; derived BA/ha and stems/ha
 * figures are AVERAGED across points (each point is an independent viewpoint),
 * never summed. Species composition (%) is unaffected; mean age / mean height
 * are averaged per species.
 */
object GcapCalc {

    /** Fixed-radius plot default: 0.01 ha = 100 m^2 (radius 5.64 m). */
    const val FIXED_PLOT_AREA_M2 = 100.0

    fun treeBaM2(dbhCm: Double): Double = PI * (dbhCm / 200.0) * (dbhCm / 200.0)

    // ---- 1. Prism in/out + BA/ha + stems/ha -----------------------------

    /** PRFc = 0.5 / sqrt(BAF)  (m per cm, plot centre to tree CENTRE).
     *  Distances are recorded to the tree centre: centre = face + DBH/2
     *  (DBH in m), so the centre PRF is used directly. The formula is
     *  defined for any positive BAF, so every size in the accepted
     *  3..30 range (and beyond) is handled by the same closed form. */
    fun plotRadiusFactor(baf: Double): Double = 0.5 / sqrt(baf)

    /** 'In' threshold (m): a tree is measurable if distance <= this value
     *  (BC manual: "less than or equal to the plot radius = in"). */
    fun criticalDistanceM(dbhCm: Double, baf: Double): Double =
        plotRadiusFactor(baf) * dbhCm

    fun treeIsIn(dbhCm: Double, horizontalDistM: Double, baf: Double): Boolean =
        horizontalDistM <= criticalDistanceM(dbhCm, baf)

    /** Horizontal distance from the measured slope distance (blank slope = 0). */
    fun horizontalDistanceM(sdM: Double, slopeDeg: Double? = null): Double =
        sdM * cos(Math.toRadians(slopeDeg ?: 0.0))

    /**
     * Prism in/out for a tallied tree. Counted (IN) only when *all* hold:
     *  - alive (TCls blank) and not an auxiliary OUT tree (numbered 91..99)
     *  - DBH is present and >= the field minimum
     *  - no distance recorded -> assumed IN; otherwise horizontal distance
     *    (Dist x cos(Slope)) is within the BAF critical distance
     */
    fun isIn(t: Tree, baf: Double): Boolean {
        if (!t.isLive) return false
        if (t.number in 91..99) return false
        val d = t.dbhCm ?: return false
        if (d < FieldChecks.DBH_MIN) return false
        val sd = t.distM ?: return true
        return treeIsIn(d, horizontalDistanceM(sd, t.slopeDeg), baf)
    }

    /** Trees that count toward the alive-tally (IN trees). */
    fun inTrees(trees: List<Tree>, baf: Double): List<Tree> = trees.filter { isIn(it, baf) }

    /** In/out margin in cm: (critical distance - horizontal distance) * 100.
     *  Positive -> tree is IN by that much; null when it cannot be decided
     *  (dead, no DBH, or no recorded distance). */
    fun marginCm(t: Tree, baf: Double): Double? {
        if (!t.isLive) return null
        val critical = t.dbhCm?.let { criticalDistanceM(it, baf) } ?: return null
        val hd = t.distM?.let { horizontalDistanceM(it, t.slopeDeg) } ?: return null
        return (critical - hd) * 100.0
    }

    fun basalAreaPerHa(nIn: Int, baf: Double): Double = baf * nIn

    /** Stems/ha weighted by tree factor BAF / (pi*(dbh/200)^2). */
    fun stemsPerHaBaf(trees: List<Tree>, baf: Double): Double {
        var total = 0.0
        for (t in trees) {
            val d = t.dbhCm
            if (d != null && d > 0.0) total += baf / treeBaM2(d)
        }
        return total
    }

    /** Return the BAF that reproduces a recorded/expected BA/ha value. */
    fun impliedBaf(nTrees: Int, targetBaHa: Double): Double =
        if (nTrees == 0) 0.0 else targetBaHa / nTrees

    // ---- 2. Age transform: DBH (boring) age -> total age ---------------

    fun totalAge(ageAtBoredHeight: Double, yearsToBreastHeight: Double): Double =
        ageAtBoredHeight + yearsToBreastHeight

    /** Total age from a 1.3 m core count, using the tree's own height as the
     *  growth-height proxy: TotalAge = core * H / (H - 1.3). Null when the
     *  height does not exceed breast height (the tree has not reached 1.3 m). */
    fun totalAgeFromCoreCount(coreCount: Int, heightM: Double): Double? {
        if (heightM <= 1.3) return null
        return coreCount * heightM / (heightM - 1.3)
    }

    /** Years from germination to breast height implied by the same height proxy:
     *  TotalAge - coreCount = core * 1.3 / (H - 1.3). Null for H <= 1.3 m. */
    fun yearsToBreastHeight(coreCount: Int, heightM: Double): Double? =
        totalAgeFromCoreCount(coreCount, heightM)?.minus(coreCount.toDouble())

    // ---- 3. Height from distance + vertical angles (clinometer) --------

    fun heightFromHorizontal(hdM: Double, angleTopDeg: Double, angleBaseDeg: Double): Double {
        val top = tan(Math.toRadians(angleTopDeg))
        val base = tan(Math.toRadians(angleBaseDeg))
        return hdM * (top - base)
    }

    fun heightFromSloped(sdM: Double, angleTopDeg: Double, angleBaseDeg: Double): Double {
        val hd = sdM * cos(Math.toRadians(angleBaseDeg))
        return heightFromHorizontal(hd, angleTopDeg, angleBaseDeg)
    }

    /**
     * Height with the GCap "Cor" correction. When the base angle (Bot) was
     * taken at [corM] metres above the true base (e.g. 1.3 m or 2.0 m), the
     * measured height is `HD*(tan(top) - tan(bot))` and the correction metres
     * are added to reference the true base.
     */
    fun heightFromSloped(sdM: Double, angleTopDeg: Double, angleBaseDeg: Double, corM: Double): Double =
        heightFromSloped(sdM, angleTopDeg, angleBaseDeg) + corM

    // ---- 4. Fixed-radius summary ----------------------------------------

    fun basalAreaPerHaFixed(
        gc: GroundCall,
        plotAreaM2: Double = FIXED_PLOT_AREA_M2,
    ): Double = gc.liveTrees.filter { it.dbhCm != null }.sumOf { treeBaM2(it.dbhCm!!) } * 10000.0 / plotAreaM2

    fun stemsPerHaFixed(
        gc: GroundCall,
        plotAreaM2: Double = FIXED_PLOT_AREA_M2,
    ): Double = gc.liveTrees.size * 10000.0 / plotAreaM2

    // ---- 5. Dead trees ---------------------------------------------------

    /** BA/ha of dead (TCls=4) trees, averaged across points. */
    fun deadBasalAreaPerHaFixed(gc: GroundCall, plotAreaM2: Double): Double {
        val perPt = gc.points().values.map {
            it.filter { t -> t.isDead }.sumOf { t -> t.basalAreaM2 } * 10000.0 / plotAreaM2
        }
        return averagePerPoint(perPt)
    }

    // ---- 6. Species composition ------------------------------------------

    /**
     * Species composition (%).
     *  - variable radius plots: share by tree count (manual §2.5)
     *  - fixed radius plots: share by individual basal area (BA)
     * Only live, category-I (D/C/H) trees are used; when no crown class is
     * recorded all live trees are used.
     */
    fun speciesComposition(
        trees: List<Tree>,
        useBasalArea: Boolean = false,
        baf: Double? = null,
    ): Map<String, Double> {
        val live = if (baf != null) inTrees(trees, baf) else trees.filter { it.isLive && it.dbhCm != null }
        val keyed = live.filter {
            val c = it.crownClassUpper
            c.isEmpty() || c in setOf("D", "C", "H")
        }
        val base = keyed.ifEmpty { live }
        val weight: (Tree) -> Double = if (useBasalArea) { t -> t.basalAreaM2 } else { { t -> 1.0 } }
        val total = base.sumOf(weight)
        if (total <= 0.0) return emptyMap()
        val sums = linkedMapOf<String, Double>()
        for (t in base) sums[t.species] = (sums[t.species] ?: 0.0) + weight(t)
        val out = linkedMapOf<String, Double>()
        for ((sp, w) in sums.toList().sortedByDescending { it.second }) {
            out[sp] = Math.round(w * 100000.0 / total) / 1000.0
        }
        return out
    }

    /** Display order for the summary composition. When `use3rdAsSecond` is set
     *  the 2nd and 3rd most-prominent species exchange places, so the former
     *  3rd is reported as the second (see Comments "Use 3dr Species"). */
    fun reorderForDisplay(
        pairs: List<Pair<String, Double>>,
        use3rdAsSecond: Boolean,
    ): List<Pair<String, Double>> {
        if (!use3rdAsSecond || pairs.size < 3) return pairs
        val out = pairs.toMutableList()
        val a = out[1]
        out[1] = out[2]
        out[2] = a
        return out
    }

    /** Mean age (total age) per species, averaged per species across points. */
    fun meanTotalAgeBySpecies(trees: List<Tree>): Map<String, Double> {
        val bySp = trees.filter { it.isLive && it.totalAge != null }.groupBy { it.species }
        return bySp.mapValues { (_, ts) -> ts.map { it.totalAge!! }.average() }
            .filterValues { it.isFinite() }
    }

    /** Mean height (m) per species, averaged per species across points. */
    fun meanHeightBySpecies(trees: List<Tree>): Map<String, Double> {
        val bySp = trees.filter { it.isLive && it.heightM != null }.groupBy { it.species }
        return bySp.mapValues { (_, ts) -> ts.map { it.heightM!! }.average() }
            .filterValues { it.isFinite() }
    }

    // ---- Point-cluster averaging --------------------------------------

    fun averagePerPoint(values: List<Double>): Double =
        if (values.isEmpty()) 0.0 else values.sum() / values.size

    /** BA/ha averaged across points: mean over points of (baf * n_in_point). */
    fun clusterBaPerHa(pointLists: List<List<Tree>>, baf: Double): Double =
        averagePerPoint(pointLists.map { baf * it.size })

    /** Stems/ha averaged across points. */
    fun clusterStemsPerHa(pointLists: List<List<Tree>>, baf: Double): Double =
        averagePerPoint(pointLists.map { stemsPerHaBaf(it, baf) })

    data class PointSummary(
        val point: Int,
        val n: Int,
        val nDead: Int,
        val baHaM2: Double,
        val stemsHa: Double,
    )

    data class ClusterSummary(
        val points: List<PointSummary>,
        val nPoints: Int,
        val baf: Double,
        val baHaM2: Double,
        val stemsHa: Double,
    )

    fun summarizeByPoint(gc: GroundCall, baf: Double = 4.0, radiusM: Double? = null): ClusterSummary {
        val pts = gc.points()
        val keys = pts.keys.sorted()
        val hasFixed = radiusM != null && radiusM > 0.0
        val plotAreaM2 = if (hasFixed) PI * radiusM!! * radiusM!! else 0.0
        val plotAreaHa = plotAreaM2 / 10000.0

        val per = keys.map { p ->
            val pointTrees = pts[p]!!
            if (hasFixed) {
                val live = pointTrees.filter { it.isLive && it.dbhCm != null && it.dbhCm >= FieldChecks.DBH_MIN }
                val baSum = live.sumOf { it.basalAreaM2 }
                val baHa = if (plotAreaHa > 0.0) baSum / plotAreaHa else 0.0
                val stemsHa = if (plotAreaHa > 0.0) live.size / plotAreaHa else 0.0
                PointSummary(
                    point = p,
                    n = live.size,
                    nDead = pointTrees.count { it.isDead },
                    baHaM2 = round2(baHa),
                    stemsHa = round1(stemsHa),
                )
            } else {
                val inLive = pointTrees.filter { isIn(it, baf) }
                PointSummary(
                    point = p,
                    n = inLive.size,
                    nDead = pointTrees.count { it.isDead },
                    baHaM2 = round2(baf * inLive.size),
                    stemsHa = round1(stemsPerHaBaf(inLive, baf)),
                )
            }
        }
        val baHaCluster = averagePerPoint(per.map { it.baHaM2 })
        val stemsHaCluster = averagePerPoint(per.map { it.stemsHa })
        return ClusterSummary(
            points = per,
            nPoints = per.size,
            baf = if (hasFixed) 0.0 else baf,
            baHaM2 = round2(baHaCluster),
            stemsHa = round1(stemsHaCluster),
        )
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    private fun round1(v: Double): Double = Math.round(v * 10.0) / 10.0
}