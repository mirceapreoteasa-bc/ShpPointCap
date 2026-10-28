package org.shppointcap.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayerImportTest {

    @Test
    fun cleanLayerNameAcceptsSaneStemsAndRejectsJunk() {
        // Stems are lowercased: one lower-case stem per layer, so the .shp and
        // its sidecars always agree (Android storage is case-insensitive, so a
        // mixed-case stem collides instead of coexisting).
        assertEquals("new_points", LayerImport.cleanLayerName("  NEW_POINTS  "))
        assertEquals("my-line_2", LayerImport.cleanLayerName("my-line_2"))
        assertEquals("xgv_mp", LayerImport.cleanLayerName("XGV_MP"))
        assertNull(LayerImport.cleanLayerName("   "))
        assertNull(LayerImport.cleanLayerName("."))
        assertNull(LayerImport.cleanLayerName(".."))
        assertNull(LayerImport.cleanLayerName("a/b"))
        assertNull(LayerImport.cleanLayerName("a\\b"))
        assertNull(LayerImport.cleanLayerName("a:b"))
        assertNull(LayerImport.cleanLayerName("a?b"))
        assertNull(LayerImport.cleanLayerName("a*b"))
    }

    @Test
    fun cleanDbfFieldAcceptsSaneNamesAndRejectsJunk() {
        assertEquals("NAME", LayerImport.cleanDbfField("  name "))
        assertEquals("CALL_NUM", LayerImport.cleanDbfField("Call_Num"))
        assertEquals("A1_", LayerImport.cleanDbfField("a1_"))
        assertNull(LayerImport.cleanDbfField(""))
        assertNull(LayerImport.cleanDbfField("   "))
        assertNull(LayerImport.cleanDbfField("AVERYLONGFIELDNAME"))
        assertNull(LayerImport.cleanDbfField("1ABC"))
        assertNull(LayerImport.cleanDbfField("A-B"))
        assertNull(LayerImport.cleanDbfField("A B"))
    }

    @Test
    fun decodePrjHandlesUtf16AndBom() {
        val ascii = "PROJCS[\"NAD_1983_UTM_Zone_10N\"]".toByteArray(Charsets.US_ASCII)
        assertEquals("PROJCS[\"NAD_1983_UTM_Zone_10N\"]", LayerImport.decodePrj(ascii))
        // UTF-16LE with BOM.
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "PROJCS[\"X\"]".toByteArray(Charsets.UTF_16LE)
        assertEquals("PROJCS[\"X\"]", LayerImport.decodePrj(utf16))
        // UTF-8 BOM.
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "PROJCS[\"Y\"]".toByteArray(Charsets.US_ASCII)
        assertEquals("PROJCS[\"Y\"]", LayerImport.decodePrj(bom))
        assertNull(LayerImport.decodePrj(null))
        assertNull(LayerImport.decodePrj(ByteArray(0)))
    }

    @Test
    fun stemAndKind() {
        assertEquals("Ground_Call", LayerImport.stemOf("Ground_Call.shp"))
        assertEquals("Ground_Call", LayerImport.stemOf("Ground_Call")) // no ext
        assertTrue(LayerImport.isShp("a.SHP"))
        assertFalse(LayerImport.isShp("a.dbf"))
        assertTrue(LayerImport.isSidecar("a.CPG"))
        assertFalse(LayerImport.isSidecar("a.shp"))
    }

    @Test
    fun sidecarCandidatesCoverBothLetterCases() {
        assertEquals(
            listOf(
                listOf("Ground_Call.shx", "Ground_Call.SHX"),
                listOf("Ground_Call.dbf", "Ground_Call.DBF"),
                listOf("Ground_Call.prj", "Ground_Call.PRJ"),
                listOf("Ground_Call.cpg", "Ground_Call.CPG"),
            ),
            LayerImport.sidecarCandidates("Ground_Call.shp"),
        )
    }

    @Test
    fun groupsKeepsSidecarsWithTheirShpAndDropsOrphans() {
        val groups = LayerImport.groupsByStem(
            listOf("a.shp", "a.dbf", "b.dbf", "c.shx", "c.shp", "d.prj"),
        )
        assertEquals(2, groups.size)
        assertEquals(listOf("a.shp", "a.dbf"), groups[0])
        assertEquals(listOf("c.shp", "c.shx"), groups[1]) // .shp first
    }

    @Test
    fun sidecarsInMatchesStemCaseInsensitivelyAndByExtension() {
        val sidecars = LayerImport.sidecarsIn(
            listOf("Ground_Call.SHP", "ground_call.dbf", "Ground_Call.PRJ", "other.dbf", "x.cpg"),
            "Ground_call.shp",
        )
        assertEquals(mapOf("dbf" to "ground_call.dbf", "prj" to "Ground_Call.PRJ"), sidecars)
    }

    @Test
    fun documentDocIdDecodesAndSiblingReplacesOnlyTheFileName() {
        assertEquals(
            "primary:Download/ForestPoints/a.shp",
            LayerImport.documentDocId(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3ADownload%2FForestPoints%2Fa.shp",
            ),
        )
        assertEquals(
            "primary:Download/ForestPoints/a.dbf",
            LayerImport.siblingDocId(
                "content://com.android.externalstorage.documents/document/" +
                    "primary%3ADownload%2FForestPoints%2Fa.shp",
                "a.dbf",
            ),
        )
        assertNull(
            LayerImport.siblingDocId(
                "content://com.android.externalstorage.documents/document/no_folder_here",
                "a.dbf",
            ),
        )
        assertNull(LayerImport.documentDocId("content://media/external/file/42"))
    }

    @Test
    fun siblingUriReplacesTheFileInItsDocumentFolder() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FGrounds%2Fa.dbf",
            LayerImport.siblingDocUri(
                "content://com.android.externalstorage.documents/document/primary%3ADownload%2FGrounds%2Fa.shp",
                "a.dbf",
            ),
        )
    }

    @Test
    fun siblingUriKeepsSpacesAndPercentEncodedNames() {
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3AMy%20Docs%2Fa.prj",
            LayerImport.siblingDocUri(
                "content://com.android.externalstorage.documents/document/primary%3AMy%20Docs%2Fa%201.shp",
                "a.prj",
            ),
        )
        // A literal '%' in the file name survives the round trip (no double decode).
        assertEquals(
            "content://com.android.externalstorage.documents/document/primary%3AD%2F100%25.shx",
            LayerImport.siblingDocUri(
                "content://com.android.externalstorage.documents/document/primary%3AD%2F100%25.shp",
                "100%.shx",
            ),
        )
    }

    @Test
    fun siblingUriRejectsNonPathDocumentUris() {
        // Media store file: no document parent.
        assertNull(
            LayerImport.siblingDocUri("content://media/external/file/42", "a.dbf"),
        )
        // Opaque provider id without a folder path.
        assertNull(
            LayerImport.siblingDocUri(
                "content://com.android.providers.downloads.documents/document/msf%3A42",
                "a.dbf",
            ),
        )
        // Tree URI (a folder, not a file) and non-content schemes.
        assertNull(
            LayerImport.siblingDocUri(
                "content://com.android.externalstorage.documents/tree/primary%3ADocs",
                "a.dbf",
            ),
        )
        assertNull(LayerImport.siblingDocUri("file:///sdcard/a.shp", "a.dbf"))
        assertNull(LayerImport.siblingDocUri("not a uri", "a.dbf"))
    }
}
