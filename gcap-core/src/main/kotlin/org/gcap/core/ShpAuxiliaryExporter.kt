package org.gcap.core

object ShpAuxiliaryExporter {
    private const val PRJ_CONTENT = "PROJCS[\"NAD_1983_UTM_Zone_10N\",GEOGCS[\"GCS_North_American_1983\",DATUM[\"D_North_American_1983\",SPHEROID[\"GRS_1980\",6378137.0,298.257222101]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]],PROJECTION[\"Transverse_Mercator\"],PARAMETER[\"False_Easting\",500000.0],PARAMETER[\"False_Northing\",0.0],PARAMETER[\"Central_Meridian\",-123.0],PARAMETER[\"Scale_Factor\",0.9996],PARAMETER[\"Latitude_Of_Origin\",0.0],UNIT[\"Meter\",1.0]]\n"

    /** WGS 84 geographic (degrees) sidecar for new-layer templates. */
    const val PRJ_WGS84 = "GEOGCS[\"GCS_WGS_1984\",DATUM[\"D_WGS_1984\",SPHEROID[\"WGS_1984\",6378137.0,298.257223563]],PRIMEM[\"Greenwich\",0.0],UNIT[\"Degree\",0.0174532925199433]]\n"

    private const val CPG_CONTENT = "UTF-8\n"

    private fun xmlMetadata(title: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <metadata>
          <idinfo>
            <citation>
              <citeinfo>
                <title>$title</title>
              </citeinfo>
            </citation>
          </idinfo>
        </metadata>
    """.trimIndent() + "\n"

    private fun aplContent(): String = "VERSION 1.0\n"

    fun writeAuxFiles(
        storage: GCapStorage,
        baseNameWithoutExt: String,
        prjContent: String = PRJ_CONTENT,
    ) {
        val prjBytes = prjContent.toByteArray(Charsets.US_ASCII)
        val cpgBytes = CPG_CONTENT.toByteArray(Charsets.US_ASCII)
        val xmlBytes = xmlMetadata(baseNameWithoutExt).toByteArray(Charsets.UTF_8)
        val aplBytes = aplContent().toByteArray(Charsets.US_ASCII)

        storage.writeSubFolderBytes("GPS_MAP", "$baseNameWithoutExt.prj", prjBytes)
        storage.writeSubFolderBytes("GPS_MAP", "$baseNameWithoutExt.cpg", cpgBytes)
        storage.writeSubFolderBytes("GPS_MAP", "$baseNameWithoutExt.shp.xml", xmlBytes)
        storage.writeSubFolderBytes("GPS_MAP", "$baseNameWithoutExt.apl", aplBytes)
    }

    /**
     * Only the two sidecars that carry meaning for a shapefile: the CRS (.prj)
     * and the encoding (.cpg). For layers the app itself creates, .shp.xml and
     * .apl are unread clutter — and each SAF write costs seconds on a slow
     * phone, so skipping them halves the cost of a first MOB or track save.
     * [writeAuxFiles] still writes the full set for the Add-layer template.
     */
    /**
     * Write the .prj and .cpg beside a shapefile. Returns false if either was refused, so the
     * caller can say so: a track saved without its .prj has no coordinate system on disk.
     */
    fun writeCrsSidecars(
        storage: GCapStorage,
        baseNameWithoutExt: String,
        prjContent: String = PRJ_CONTENT,
    ): Boolean {
        val prj = storage.writeSubFolderBytes(
            "GPS_MAP", "$baseNameWithoutExt.prj", prjContent.toByteArray(Charsets.US_ASCII)
        )
        val cpg = storage.writeSubFolderBytes(
            "GPS_MAP", "$baseNameWithoutExt.cpg", CPG_CONTENT.toByteArray(Charsets.US_ASCII)
        )
        return prj && cpg
    }
}
