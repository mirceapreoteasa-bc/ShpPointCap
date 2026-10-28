package org.gcap.core

import kotlin.math.*

object UtmProj {
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257223563
    private const val K0 = 0.9996
    private const val E2 = F * (2 - F)
    private const val E4 = E2 * E2
    private const val E6 = E4 * E2

    fun utmToLatLon(easting: Double, northing: Double, zone: Int, southern: Boolean = false): Pair<Double, Double> {
        val x = easting - 500000.0
        val y = if (southern) northing - 10000000.0 else northing
        val m = y / K0
        val mu = m / (A * (1.0 - E2 / 4.0 - 3.0 * E4 / 64.0 - 5.0 * E6 / 256.0))

        val e1 = (1.0 - sqrt(1.0 - E2)) / (1.0 + sqrt(1.0 - E2))
        val phi1 = mu + (3.0 * e1 / 2.0 - 27.0 * e1 * e1 * e1 / 32.0) * sin(2.0 * mu) +
            (21.0 * e1 * e1 / 16.0 - 55.0 * e1 * e1 * e1 * e1 / 32.0) * sin(4.0 * mu) +
            (151.0 * e1 * e1 * e1 / 96.0) * sin(6.0 * mu)

        val n1 = A / sqrt(1.0 - E2 * sin(phi1) * sin(phi1))
        val t1 = tan(phi1) * tan(phi1)
        val c1 = (E2 / (1.0 - E2)) * cos(phi1) * cos(phi1)
        val r1 = A * (1.0 - E2) / (1.0 - E2 * sin(phi1) * sin(phi1)).pow(1.5)
        val d = x / (n1 * K0)

        val lat = phi1 - (n1 * tan(phi1) / r1) * (d * d / 2.0 - (5.0 + 3.0 * t1 + 10.0 * c1 - 4.0 * c1 * c1 - 9.0 * (E2 / (1.0 - E2))) * d * d * d * d / 24.0 +
            (61.0 + 90.0 * t1 + 298.0 * c1 + 45.0 * t1 * t1 - 252.0 * (E2 / (1.0 - E2)) - 3.0 * c1 * c1) * d * d * d * d * d * d / 720.0)

        val lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3.0)
        val lon = lon0 + (d - (1.0 + 2.0 * t1 + c1) * d * d * d / 6.0 +
            (5.0 - 2.0 * c1 + 28.0 * t1 - 3.0 * c1 * c1 + 8.0 * (E2 / (1.0 - E2)) + 24.0 * t1 * t1) * d * d * d * d * d / 120.0) / cos(phi1)

        return Math.toDegrees(lat) to Math.toDegrees(lon)
    }

    fun latLonToUtm(latDeg: Double, lonDeg: Double, targetZone: Int): Pair<Double, Double> {
        val phi = Math.toRadians(latDeg)
        val lambda0 = Math.toRadians((targetZone - 1) * 6 - 180 + 3.0)
        val lambda = Math.toRadians(lonDeg)
        val dl = lambda - lambda0

        val ep2 = E2 / (1.0 - E2)
        val n = A / sqrt(1.0 - E2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = ep2 * cos(phi) * cos(phi)
        val a = cos(phi) * dl

        val m = A * (
            (1.0 - E2 / 4.0 - 3.0 * E4 / 64.0 - 5.0 * E6 / 256.0) * phi -
                (3.0 * E2 / 8.0 + 3.0 * E4 / 32.0 + 45.0 * E6 / 1024.0) * sin(2.0 * phi) +
                (15.0 * E4 / 256.0 + 45.0 * E6 / 1024.0) * sin(4.0 * phi) -
                (35.0 * E6 / 3072.0) * sin(6.0 * phi)
            )

        val x = K0 * n * (
            a + (1.0 - t + c) * a * a * a / 6.0 +
                (5.0 - 18.0 * t + t * t + 72.0 * c - 58.0 * ep2) * a * a * a * a * a / 120.0
            )
        val y = K0 * (
            m + n * tan(phi) * (
                a * a / 2.0 +
                    (5.0 - t + 9.0 * c + 4.0 * c * c) * a * a * a * a / 24.0 +
                    (61.0 - 58.0 * t + t * t + 600.0 * c - 330.0 * ep2) * a * a * a * a * a * a / 720.0
                )
            )
        val easting = 500000.0 + x
        val northing = if (latDeg < 0) 10000000.0 + y else y
        return easting to northing
    }

    fun reproject(easting: Double, northing: Double, srcZone: Int, targetZone: Int): Pair<Double, Double> {
        if (srcZone == targetZone || srcZone <= 0 || targetZone <= 0) return easting to northing
        val (lat, lon) = utmToLatLon(easting, northing, srcZone)
        return latLonToUtm(lat, lon, targetZone)
    }
}
