package org.shppointcap.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.io.File

/**
 * App-level state for Forest Point Collector. Holds the user-chosen data
 * folder (SAF tree URI persisted across runs) and hands out the storage
 * backend. v1 has no sample database — points live in GPS_MAP shapefiles.
 */
class CollectorApp : Application() {

    private val PREF_SAF_TREE = "spc_saf_tree_uri"
    private val PREF_PROJECT = "spc_active_project"
    private val PREF_CRASH = "spc_last_crash"

    /** Display name of the active project (default "mirshp"); file is "<name>.json". */
    var activeProject: String = "mirshp"
        private set

    fun projectFile(): String = "$activeProject.json"

    fun setActiveProject(name: String) {
        val clean = sanitizeProjectName(name) ?: return
        activeProject = clean
        prefs.edit().putString(PREF_PROJECT, clean).apply()
    }

    companion object {
        private const val BACKUP_MARK = "_backup_"
        private const val MAX_BACKUPS = 5

        /** Filename-safe project names (spaces kept, hostile chars become _). */
        fun sanitizeProjectName(raw: String): String? {
            val clean = raw.trim().replace(Regex("[/\\\\:*?\"<>|]"), "_").trim()
            return clean.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * Make sure the active project file exists and still describes the folder.
     *
     * Two cases, both safe to repeat:
     * - **Missing.** The app used to fall back to the bundled default in memory and never
     *   write it, so a fresh folder had no project file until someone opened Settings and
     *   pressed Save. It is now written straight away, built from the folder's own layers
     *   rather than from a template listing layers this folder does not contain.
     * - **Obsolete.** Layers added to or removed from the folder leave the file describing
     *   something that is no longer there, and a file written before this schema existed
     *   has no way to be recognised. Both are corrected here.
     *
     * Writes only when something actually changed, so an unchanged file is never touched.
     * Returns the text now in force, or null when the folder could not be read.
     *
     * Blocking: callers run it off the UI thread.
     */
    fun ensureProject(shpStems: List<String>): String? {
        val current = projectText()
        val merged = org.shppointcap.app.map.LayerStyle.reconcile(current, shpStems)
        if (merged == null) return current
        // Keep what is being replaced before writing over it. This app rewrites the
        // project file from the folder's own contents, so the one thing that could be
        // lost is settings somebody set by hand; a backup costs one small file.
        if (current != null) backupProject(current)
        if (writeProject(activeProject, merged)) return merged
        // The write failed: carry on with what is in memory rather than losing the
        // session's settings, which is what the bundled fallback used to do.
        return current
    }

    /**
     * Save the outgoing project text as `<name>_backup_1`, `_2`, ...
     *
     * These sit beside the real projects in the data folder, so the ordinary project
     * picker already lists them and opening one makes it the active project again -
     * which is the whole point: a backup nobody can get back is not a backup.
     * [isBackup] is how the picker marks them.
     */
    fun backupProject(text: String): Boolean {
        val used = try {
            storage().listRootJson().mapNotNull { backupIndexOf(it) }
        } catch (e: Exception) {
            emptyList()
        }
        val next = (used.maxOrNull() ?: 0) + 1
        // Bounded, oldest reused: an unbounded series of backups in a field data folder
        // is its own mess. Five is enough to walk back through a day's changes.
        val slot = if (next > MAX_BACKUPS) next - MAX_BACKUPS else next
        return try {
            storage().writeBytes("$activeProject$BACKUP_MARK$slot.json", text.toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            false
        }
    }

    /** The backup number in a project name, or null when it is a real project. */
    fun backupIndexOf(name: String): Int? {
        if (!name.startsWith("$activeProject$BACKUP_MARK")) return null
        return name.removePrefix("$activeProject$BACKUP_MARK").removeSuffix(".json").toIntOrNull()
    }

    /** True when [name] is a backup rather than a project to work on. */
    fun isBackup(name: String): Boolean = backupIndexOf(name) != null



    /** Raw text of the active project file (data folder), or null. */
    fun projectText(): String? = try {
        storage().readBytes(projectFile())?.toString(Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    fun writeProject(name: String, text: String): Boolean = try {
        storage().writeBytes("$name.json", text.toByteArray(Charsets.UTF_8))
    } catch (e: Exception) {
        false
    }

    /** All project names (= *.json stems) at the data-folder root. */
    fun listProjects(): List<String> = try {
        storage().listRootJson()
    } catch (e: Exception) {
        emptyList()
    }

    fun deleteProject(name: String) {
        try {
            storage().delete("$name.json")
        } catch (e: Exception) { }
    }

    lateinit var internalDir: File
        private set

    var safTreeUri: Uri? = null
        private set

    private val prefs by lazy { getSharedPreferences("shp_point_cap", MODE_PRIVATE) }

    /** Cached backend; SafStorage re-resolves its root per construction. */
    private var storageCache: GCapStorage? = null

    override fun onCreate() {
        super.onCreate()
        internalDir = File(filesDir, "points").apply { mkdirs() }
        prefs.getString(PREF_SAF_TREE, null)?.let { safTreeUri = Uri.parse(it) }
        prefs.getString(PREF_PROJECT, null)?.takeIf { it.isNotBlank() }?.let { activeProject = it }
        installCrashHandler()
        Log.i("CollectorApp", "filesDir=${internalDir.absolutePath} saf=${safTreeUri} project=$activeProject")
    }

    /**
     * Field crashes are unreproducible on the emulator, so record them. The
     * trace goes to [crashFile] and [pendingCrash] holds a one-line summary
     * that the map shows on the next launch, then clears.
     */
    private fun installCrashHandler() {
        val prior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, err ->
            try {
                val text = buildString {
                    append("Thread: ${thread.name}\n")
                    append(err.stackTraceToString())
                }
                crashFile()?.writeText(text)
                prefs.edit()
                    .putString(PREF_CRASH, "${err.javaClass.simpleName}: ${err.message}")
                    .apply()
            } catch (ignored: Throwable) { }
            if (prior != null) prior.uncaughtException(thread, err)
        }
    }

    /** Crash log path, or null if the directory is unavailable. */
    fun crashFile(): File? = try {
        val d = getExternalFilesDir(null) ?: File(filesDir, "crash")
        d.mkdirs(); File(d, "crash.log")
    } catch (e: Exception) { null }

    /** One-line crash summary from a previous run, cleared once read. */
    fun consumeCrash(): String? =
        prefs.getString(PREF_CRASH, null)?.also {
            prefs.edit().remove(PREF_CRASH).apply()
        }

    fun storage(): GCapStorage {
        storageCache?.let { return it }
        val st: GCapStorage = safTreeUri?.let { uri ->
            try {
                SafStorage(this, uri)
            } catch (e: Exception) {
                Log.w("CollectorApp", "SafStorage failed, using internal: ${e.message}")
                InternalStorage(internalDir)
            }
        } ?: InternalStorage(internalDir)
        storageCache = st
        return st
    }

    fun setDataFolder(uri: Uri) {
        storageCache = null
        safTreeUri = uri
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: Exception) {
            Log.w("CollectorApp", "persist uri failed: ${e.message}")
        }
        prefs.edit().putString(PREF_SAF_TREE, uri.toString()).apply()
    }

    fun clearDataFolder() {
        storageCache = null
        safTreeUri = null
        prefs.edit().remove(PREF_SAF_TREE).apply()
    }

    fun storageDescription(): String = storage().describe()
}
