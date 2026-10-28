package org.gcap.core

object AllXgvShpExporter {

    fun generate(appStorage: GCapStorage, samples: List<GroundCall>, crew: String): Pair<String, Int> {
        val sorted = samples.filter { it.sampleId.isNotEmpty() && (it.easting != null || it.capture.utmPoints.isNotEmpty()) }
            .sortedBy { it.sampleId.lowercase() }

        if (sorted.isEmpty()) {
            throw IllegalStateException("No valid ground call coordinates found to export.")
        }

        val firstZoneStr = sorted.first().capture.utmZone.trim()
        val targetZone = firstZoneStr.filter { it.isDigit() }.toIntOrNull() ?: 10

        val coords = mutableListOf<DoubleArray>()
        val names = mutableListOf<String>()

        for (gc in sorted) {
            val pt = gc.capture.utmChosenIndex?.let { gc.capture.utmPoints.getOrNull(it) }
                ?: gc.capture.utmPoints.firstOrNull()
            val e = gc.easting ?: pt?.easting ?: continue
            val n = gc.northing ?: pt?.northing ?: continue

            val srcZoneStr = gc.capture.utmZone.trim()
            val srcZone = srcZoneStr.filter { it.isDigit() }.toIntOrNull() ?: targetZone

            val (re, rn) = UtmProj.reproject(e, n, srcZone, targetZone)
            coords.add(doubleArrayOf(re, rn))
            names.add(gc.sampleId)
        }

        val shpBytes = ShpFormat.buildPointShp(coords)
        val shxBytes = ShpFormat.buildPointShx(coords)
        val dbfBytes = ShpFormat.buildDbf(byteArrayOf(), names, "GCALL_ID")

        val baseName = "xgv_all_${crew.lowercase()}"
        val fileName = "$baseName.shp"
        val shxName = "$baseName.shx"
        val dbfName = "$baseName.dbf"

        appStorage.writeSubFolderBytes("GPS_MAP", fileName, shpBytes)
        appStorage.writeSubFolderBytes("GPS_MAP", shxName, shxBytes)
        appStorage.writeSubFolderBytes("GPS_MAP", dbfName, dbfBytes)

        ShpAuxiliaryExporter.writeAuxFiles(appStorage, baseName)

        return fileName to coords.size
    }
}
