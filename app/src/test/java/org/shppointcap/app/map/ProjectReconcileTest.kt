package org.shppointcap.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The project file brought into line with the folder: written when missing, rewritten
 * when obsolete, and left completely alone when it is already right.
 *
 * NOT YET WIRED UP, and these are the tests that said why. Reconciling needs a stable
 * round trip: the document written by one version must be readable and comparable by the
 * next. It is not - re-serialising a project file changes it even when every layer is
 * correct, because numeric fields are read as strings and lost on the way back - so
 * "has anything changed?" cannot yet be answered reliably. Until it can, the app must not
 * rewrite the file on the guess, so ensureProject is defined but not called.
 */
class ProjectReconcileTest {

    private fun doc(vararg names: String) = names.joinToString(",", "[", "]") { """{"name":"$it"}""" }

    private fun layersOf(text: String?): List<String> =
        LayerStyle.parseProject(text ?: "").map { it.name }

    @Test
    fun aMissingProjectFileIsWritten() {
        val out = LayerStyle.reconcile(null, listOf("roads", "trails"))
        assertNotNull("a folder with layers must get a project file", out)
        assertEquals(listOf("roads", "trails"), layersOf(out))
    }

    @Test
    fun aBlankProjectFileIsTreatedAsMissing() {
        val out = LayerStyle.reconcile("   ", listOf("roads"))
        assertNotNull(out)
        assertEquals(listOf("roads"), layersOf(out))
    }

    @Test
    fun anUnchangedProjectFileIsNotRewritten() {
        val first = LayerStyle.reconcile(null, listOf("roads", "trails"))!!
        assertNull("nothing changed, so write nothing", LayerStyle.reconcile(first, listOf("roads", "trails")))
    }

    @Test
    fun aLayerAddedToTheFolderIsPickedUp() {
        val before = LayerStyle.reconcile(null, listOf("roads"))!!
        val after = LayerStyle.reconcile(before, listOf("roads", "trails"))
        assertNotNull("a new layer in the folder must reach the project file", after)
        assertEquals(listOf("roads", "trails"), layersOf(after))
    }

    @Test
    fun aLayerRemovedFromTheFolderIsDropped() {
        val before = LayerStyle.reconcile(null, listOf("roads", "trails"))!!
        val after = LayerStyle.reconcile(before, listOf("roads"))
        assertNotNull(after)
        assertEquals(listOf("roads"), layersOf(after))
    }

    @Test
    fun existingSettingsSurviveAReconcile() {
        val styled = LayerStyle.docWithLayers(
            null,
            listOf(LayerStyle.LayerSpec(name = "roads", visible = false, colorRaw = "violet", labelField = "NAME")),
        )
        val after = LayerStyle.reconcile(styled, listOf("roads", "trails"))!!
        val kept = LayerStyle.parseProject(after).first { it.name == "roads" }
        assertTrue("a layer switched off must stay off", !kept.visible)
        assertEquals("violet", kept.colorRaw)
        assertEquals("NAME", kept.labelField)
        assertTrue("the new layer is added", layersOf(after).contains("trails"))
    }

    @Test
    fun orderIsKeptAndNewLayersAppended() {
        val before = LayerStyle.reconcile(null, listOf("b", "a"))!!
        val after = LayerStyle.reconcile(before, listOf("b", "a", "c"))!!
        assertEquals(listOf("b", "a", "c"), layersOf(after))
    }

    @Test
    fun anOldFileWithNoSchemaIsRewritten() {
        // written before the schema field existed: identical layers, still needs stamping
        val old = """{"layers":[${doc("roads")}]}"""
        val out = LayerStyle.reconcile(old, listOf("roads"))
        assertNotNull("a file with no schema cannot be recognised, so it is rewritten", out)
        assertTrue("the rewrite stamps the schema", out!!.contains("\"schema\""))
    }

    @Test
    fun matchingIgnoresCaseAndRepeats() {
        val before = LayerStyle.reconcile(null, listOf("Roads"))!!
        assertNull(
            "the same layer written differently is not a change",
            LayerStyle.reconcile(before, listOf("roads", "ROADS", "Roads")),
        )
    }

    @Test
    fun aProjectFileSurvivesBeingReadAndWrittenAgain() {
        // The bug that made everything else unstable: numbers were read with a string
        // accessor, so a size came back as nothing and was written out as null.
        val original = LayerStyle.docWithLayers(
            null,
            listOf(
                LayerStyle.LayerSpec(name = "roads", sizeDp = 2.5f, labelField = "NAME", labelSizeSp = 9.0f),
                LayerStyle.LayerSpec(name = "trails", sizeDp = 6.0f),
            ),
        )
        val again = LayerStyle.docWithLayers(original, LayerStyle.parseProject(original))
        assertEquals(
            "a project file must not change just by being re-read",
            LayerStyle.parseProject(original),
            LayerStyle.parseProject(again),
        )
        val sizes = LayerStyle.parseProject(again).map { it.sizeDp }
        assertTrue("numbers must survive", sizes.containsAll(listOf(2.5f, 6.0f)))
    }

    @Test
    fun numbersAlreadyInAFileAreKept() {
        val handWritten = """{"layers":[{"name":"roads","size":2.5,"labelSize":9.0}]}"""
        val out = LayerStyle.reconcile(handWritten, listOf("roads"))!!
        val spec = LayerStyle.parseProject(out).first()
        assertEquals(2.5f, spec.sizeDp)
        assertEquals(9.0f, spec.labelSizeSp)
    }

    @Test
    fun anEmptyFolderDoesNotThrow() {
        assertNull("nothing to describe, nothing to write", LayerStyle.reconcile(null, emptyList()))
    }

}