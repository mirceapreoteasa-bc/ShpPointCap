package org.shppointcap.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Enhanced storage backend enforcing the required directory structure under
 * the user's internal memory ForestPoints folder:
 *   - ForestPoints/ (root)
 *       - <name>.dat
 *       - <name>.gc.json
 *       - GPS_MAP/ (preserved if existing)
 *       - XGV_ODS_XLSX/
 *           - <name>.ods
 *           - <name>.xlsx
 */
interface GCapStorage : org.gcap.core.GCapStorage {
    /** Names (no extension) of *.json project files at the data-folder root. */
    fun listRootJson(): List<String> = emptyList()
}

class InternalStorage(private val appDir: File) : GCapStorage {

    override fun listRootJson(): List<String> = try {
        if (!targetDir.exists()) emptyList()
        else targetDir.listFiles { f -> f.isFile && f.name.endsWith(".json", true) }
            .orEmpty().map { it.name.substringBeforeLast('.') }.sorted()
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * App-private dir only. (The BAF_cap-era Download/ForestPoints fallback
     * is gone: with no SAF folder picked there is nowhere shared to keep
     * projects, and creating stale folders confused users.)
     */
    private val targetDir: File
        get() {
            return try {
                if (!appDir.exists()) appDir.mkdirs()
                appDir
            } catch (e: Exception) {
                appDir
            }
        }

    override fun listDatNames(): List<String> =
        try {
            targetDir.listFiles { f -> f.isFile && f.name.endsWith(".dat") }
                .orEmpty().map { it.name.removeSuffix(".dat") }.sorted()
        } catch (e: Exception) {
            emptyList()
        }

    override fun readBytes(name: String): ByteArray? =
        try {
            File(targetDir, name).takeIf { it.exists() }?.readBytes()
        } catch (e: Exception) {
            null
        }

    override fun writeBytes(name: String, bytes: ByteArray): Boolean =
        try {
            if (!targetDir.exists()) targetDir.mkdirs()
            FileOutputStream(File(targetDir, name)).use { it.write(bytes) }; true
        } catch (e: Exception) {
            Log.w("InternalStorage", "writeBytes failed on targetDir, falling back to appDir: ${e.message}")
            try {
                appDir.mkdirs()
                FileOutputStream(File(appDir, name)).use { it.write(bytes) }; true
            } catch (ex: Exception) {
                false
            }
        }

    override fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray): Boolean =
        try {
            if (!targetDir.exists()) targetDir.mkdirs()
            val sub = File(targetDir, subFolder).apply { mkdirs() }
            FileOutputStream(File(sub, name)).use { it.write(bytes) }; true
        } catch (e: Exception) {
            Log.w("InternalStorage", "writeSubFolderBytes failed on targetDir, falling back to appDir: ${e.message}")
            try {
                val sub = File(appDir, subFolder).apply { mkdirs() }
                FileOutputStream(File(sub, name)).use { it.write(bytes) }; true
            } catch (ex: Exception) {
                false
            }
        }

    override fun delete(name: String) {
        try {
            File(targetDir, name).delete()
        } catch (e: Exception) { }
    }

    override fun describe(): String = targetDir.absolutePath
}

class SafStorage(
    context: Context,
    private val treeUri: Uri,
) : GCapStorage {

    private val context = context.applicationContext
    private val resolver = context.contentResolver

    // fromTreeUri() only parses the URI (no I/O). The expensive calls are
    // listFiles()/findFile() — and DocumentFile.findFile() is implemented as
    // a FULL directory re-listing on every call. The old `root` getter ran
    // two findFile() scans on every single access (plus one more per
    // child()), so one sample-list reload issued 100+ provider queries and
    // blocked the UI for tens of seconds. Cache one listing per instance
    // instead (the instance itself is cached by GCapApp); writes invalidate.
    private val rootDoc: DocumentFile? by lazy {
        try { DocumentFile.fromTreeUri(context, treeUri) } catch (e: Exception) { null }
    }

    private var childrenCache: Map<String, DocumentFile>? = null

    private fun refreshChildren(): Map<String, DocumentFile> {
        val map = try {
            rootDoc?.listFiles()?.mapNotNull { f -> f.name?.let { it to f } }?.toMap()
        } catch (e: Exception) { null }.orEmpty()
        childrenCache = map
        return map
    }

    private fun child(name: String): DocumentFile? = try {
        (childrenCache ?: refreshChildren())[name]
            ?: rootDoc?.findFile(name) // fallback: file appeared outside the app
    } catch (e: Exception) { null }

    private fun subDir(subFolder: String): DocumentFile? = try {
        // Never creates folders (user uploads the SHP_CAP structure); a
        // missing subfolder simply means unwritable.
        child(subFolder)?.takeIf { it.isDirectory }
    } catch (e: Exception) { null }

    override fun listDatNames(): List<String> =
        try {
            refreshChildren().keys.filter { it.endsWith(".dat") }
                .map { it.removeSuffix(".dat") }
                .sorted()
        } catch (e: Exception) { emptyList() }

    override fun listRootJson(): List<String> =
        try {
            refreshChildren().keys.filter { it.endsWith(".json", true) }
                .map { it.substringBeforeLast('.') }
                .sorted()
        } catch (e: Exception) { emptyList() }

    override fun readBytes(name: String): ByteArray? =
        try {
            child(name)?.let { f ->
                resolver.openInputStream(f.uri)?.use { it.readBytes() }
            }
        } catch (e: Exception) { null }

    override fun writeBytes(name: String, bytes: ByteArray): Boolean =
        try {
            val f = child(name) ?: rootDoc?.createFile("application/octet-stream", name) ?: return false
            childrenCache = null
            // "wt" truncates. The default mode does not on every provider, so a shorter
            // rewrite left the tail of the old file behind and the project JSON stopped
            // parsing.
            resolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes); true } ?: false
        } catch (e: Exception) { false }

    override fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray): Boolean =
        try {
            val sub = subDir(subFolder) ?: return false
            val f = sub.findFile(name) ?: sub.createFile("application/octet-stream", name) ?: return false
            resolver.openOutputStream(f.uri, "wt")?.use { it.write(bytes); true } ?: false
        } catch (e: Exception) { false }

    override fun delete(name: String) {
        try {
            child(name)?.delete()
            childrenCache = null
        } catch (e: Exception) { }
    }

    override fun describe(): String =
        try {
            val doc = DocumentFile.fromTreeUri(context, treeUri)
            (doc?.name ?: DocumentsContract.getTreeDocumentId(treeUri)) + "  (" + treeUri + ")"
        } catch (e: Exception) { treeUri.toString() }
}