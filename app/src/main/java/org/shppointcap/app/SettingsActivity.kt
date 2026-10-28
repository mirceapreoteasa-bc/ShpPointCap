package org.shppointcap.app

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.gcap.core.ShpAuxiliaryExporter
import org.gcap.core.ShpFormat
import org.json.JSONObject
import org.shppointcap.app.map.FileMapDir
import org.shppointcap.app.map.LayerImport
import org.shppointcap.app.map.LayerStyle
import org.shppointcap.app.map.MapCanvasView
import org.shppointcap.app.map.MapFileDir
import org.shppointcap.app.map.SafMapDir
import org.shppointcap.app.map.Utm
import java.io.File

/**
 * General Settings page (see settings_1.ods): data folder, working
 * coordinate system, autosave + track intervals, zoom range/limits, default
 * scale, go-to layer/column, and default Add-Point / MOB / track layers.
 * Persists into data-folder project.json next to "layers".
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var app: CollectorApp
    private lateinit var mapDir: MapFileDir

    private var workingCrs = ""
    private var autosave = ""
    private var trackInterval = ""
    private var trackMinDist = "5"
    private var zoomMin = 500
    private var zoomMax = 400000
    private var defaultScale = 10000
    private var goToLayer = "Ground_Call"
    private var goToColumn = "Call_Num"
    private var addPointLayer = "XGV_MP"
    private var mobLayer = "POINTS"
    private var trackLayer = "track"

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            Thread {
                app.setDataFolder(uri)
                runOnUiThread { recreate() }
            }.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as CollectorApp
        mapDir = app.safTreeUri?.let { SafMapDir(this, it) }
            ?: FileMapDir(File(cacheDir, "no-folder-picked"))

        val cur = currentSettings()
        workingCrs = cur.workingCrs
        autosave = cur.autosaveSeconds?.toString().orEmpty()
        trackInterval = cur.trackIntervalSeconds?.toString().orEmpty()
        trackMinDist = cur.trackMinDistanceM.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() }
        zoomMin = cur.zoomMin
        zoomMax = cur.zoomMax
        defaultScale = cur.defaultScale
        goToLayer = cur.goToLayer
        goToColumn = cur.goToColumn
        addPointLayer = cur.addPointLayer
        mobLayer = cur.mobLayer
        trackLayer = cur.trackLayer

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        root.addView(TextView(this).apply {
            text = "SETTINGS"
            textSize = 22f
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(commandButton("Save").apply { setOnClickListener { saveAndClose() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(actionButton("Cancel").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        // Two columns (settings_1.ods): A = setting rows, B = project commands.
        val scroller = ScrollView(this)
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        list.addView(settingRow(
            "Layers folder",
            "Folder holding SHP_cap layers and project files on this device.",
            TextView(this).apply { text = app.storageDescription(); textSize = 13f },
            actionButton("Change…").apply {
                setOnClickListener {
                    pickFolder.launch(Uri.parse("content://com.android.externalstorage.documents/"))
                }
            },
        ))

        list.addView(settingRow(
            "Create new layer",
            "Empty point/line template (.shp/.shx/.dbf/.prj/.cpg) in the Layers folder.",
            actionButton("Create…").apply {
                setOnClickListener { createLayerDialog(null, 0, 0, null, null) }
            },
        ))

        list.addView(settingRow(
            "Verify layers",
            "Check every .shp for sidecars, readable header, known CRS and project match.",
            actionButton("Verify…").apply {
                setOnClickListener { verifyLayers() }
            },
        ))

        // Display labels; persisted values are "" / "WGS84" / "UTM Zone NN".
        val crsOptions = listOf("Auto (first layer)", "WGS84 (auto zone)") +
            LayerStyle.CRS_OPTIONS.filter { it != "WGS84" }
        list.addView(settingRow(
            "Default Coordinate System",
            "Working system for conversions next session. Default: first layer (XGV_MP or other XGV crew layer).",
            spinner(crsOptions, crsToUi(workingCrs, crsOptions)) { workingCrs = uiToCrs(it, crsOptions) },
        ))

        list.addView(settingRow(
            "Save Edits every … seconds",
            "Autosave interval for edits (blank = no autosave). Default 40.",
            numEdit(autosave, "40 (blank = off)") { autosave = it },
        ))

        list.addView(settingRow(
            "Record track interval … seconds",
            "Time between consecutive recorded track nodes (with min distance). Default 10.",
            numEdit(trackInterval, "10") { trackInterval = it },
        ))

        list.addView(settingRow(
            "Minimum distance … meters in track",
            "Min distance between track nodes, avoids overcrowding. Default 5.",
            numEdit(trackMinDist, "5") { trackMinDist = it },
        ))

        val scaleLabels = LayerStyle.FIXED_SCALES.map { "1:$it" }
        list.addView(settingRow(
            "Available scales for zoom functions",
            "Zoom_IN / Zoom_OUT clamp range (default whole interval).",
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(spinner(scaleLabels, scaleLabels.indexOf("1:$zoomMin").takeIf { it >= 0 } ?: 0) {
                    zoomMin = LayerStyle.FIXED_SCALES[it]
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(spinner(scaleLabels, scaleLabels.indexOf("1:$zoomMax").takeIf { it >= 0 } ?: (scaleLabels.size - 1)) {
                    zoomMax = LayerStyle.FIXED_SCALES[it]
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            },
        ))

        list.addView(settingRow(
            "Default Scale",
            "Map scale on start.",
            spinner(scaleLabels, scaleLabels.indexOf("1:$defaultScale").takeIf { it >= 0 } ?: 9) {
                defaultScale = LayerStyle.FIXED_SCALES[it]
            },
        ))

        val pointFiles = layerFiles(MapCanvasView.MapLayer.Kind.POINT)
        val lineFiles = layerFiles(MapCanvasView.MapLayer.Kind.LINE)
        list.addView(settingRow(
            "Default layer for “Go to …”",
            "Point layer searched by Go to (default Ground_Call.shp).",
            layerSpinner(pointFiles, goToLayer) { goToLayer = it },
        ))
        list.addView(settingRow(
            "Search value in .dbf “Go to…” layer",
            "DBF column shown in the Go-to point picker.",
            textEdit(goToColumn, "Call_Num") { goToColumn = it },
        ))
        list.addView(settingRow(
            "Add Point layer",
            "Default target for Add_Point (default XGV_MP.shp).",
            layerSpinner(pointFiles, addPointLayer) { addPointLayer = it },
        ))
        list.addView(settingRow(
            "MOB point collection layer",
            "Fixed target for MOB captures (default POINTS.shp).",
            layerSpinner(pointFiles, mobLayer) { mobLayer = it },
        ))
        list.addView(settingRow(
            "Track line collection layer",
            "Target for recorded tracks (default track.shp).",
            layerSpinner(lineFiles, trackLayer) { trackLayer = it },
        ))

        // Column A holds the setting rows, column B the project commands.
        cols.addView(list, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        cols.addView(
            projectSection(),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f),
        )
        scroller.addView(cols)
        root.addView(
            scroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        setContentView(root)
    }

    private fun settingRow(title: String, desc: String, vararg views: android.view.View): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = desc
            textSize = 12f
            setTextColor(0xFF4A4A4A.toInt())
        })
        for (v in views) box.addView(v)
        box.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (6 * resources.displayMetrics.density).toInt(),
            )
            setBackgroundColor(0xFFE0E0E0.toInt())
        })
        return box
    }

    private fun spinner(options: List<String>, selected: Int, onPos: (Int) -> Unit): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@SettingsActivity,
                android.R.layout.simple_spinner_item,
                options,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(selected.coerceIn(options.indices))
            onItemSelectedListener = SimpleSelected(onPos)
        }

    private fun numEdit(value: String, hint: String, set: (String) -> Unit): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            addTextChangedListener(SimpleWatcher { s -> set(s) })
        }

    private fun textEdit(value: String, hint: String, set: (String) -> Unit): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine(true)
            addTextChangedListener(SimpleWatcher { s -> set(s) })
        }

    private fun layerSpinner(files: List<String>, current: String, set: (String) -> Unit): Spinner {
        val options = if (files.any { it.equals(current, true) }) files else listOf(current) + files
        return spinner(options, options.indexOfFirst { it.equals(current, true) }.takeIf { it >= 0 } ?: 0) {
            set(options[it])
        }
    }

    private fun layerFiles(kind: MapCanvasView.MapLayer.Kind): List<String> {
        val out = mutableListOf<String>()
        for (f in mapDir.listFiles().filter { it.endsWith(".shp", true) }) {
            val bytes = try { mapDir.readHeader(f, 100) } catch (e: Exception) { null } ?: continue
            val k = try {
                LayerStyle.kindFor(ShpFormat.shpHeader(bytes)?.shapeType)
            } catch (e: Exception) { continue }
            if (k == kind) out.add(f.substringBeforeLast('.'))
        }
        return out.sorted()
    }

    private fun crsToUi(persisted: String, options: List<String>): Int = when {
        persisted.isBlank() -> 0
        persisted == "WGS84" -> 1
        else -> options.indexOf(persisted).takeIf { it >= 0 } ?: 0
    }

    private fun uiToCrs(pos: Int, options: List<String>): String = when (pos) {
        0 -> ""
        1 -> "WGS84"
        else -> options[pos]
    }

    private fun projectDocText(): String? =
        app.projectText() ?: try {
            assets.open("mirshp.json").readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) { null }

    private fun currentSettings(): LayerStyle.Settings =
        LayerStyle.parseSettings(projectDocText() ?: "{}")

    private fun saveAndClose() {
        if (defaultScale != defaultScale.coerceIn(minOf(zoomMin, zoomMax), maxOf(zoomMin, zoomMax))) {
            Toast.makeText(this, "Default scale clamped into zoom range", Toast.LENGTH_SHORT).show()
        }
        if (saveFormToActive(stay = false)) finish()
    }

    // ---- project file commands (second column of the spec sheet) --------------

    private fun projectSection(): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 10, 4, 10)
        }
        box.addView(TextView(this).apply {
            text = "Project file"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        box.addView(TextView(this).apply {
            text = "Current map name: ${app.activeProject} (default “mirshp”)"
            textSize = 13f
        })
        box.addView(commandButton("Save Map Settings").apply {
            setOnClickListener { saveFormToActive(stay = true) }
        })
        val nameInput = EditText(this).apply {
            hint = "new map name"
            setText(app.activeProject)
            setSingleLine(true)
        }
        box.addView(nameInput)
        box.addView(commandButton("Save Map As").apply {
            setOnClickListener {
                val name = CollectorApp.sanitizeProjectName(nameInput.text.toString())
                if (name == null) {
                    Toast.makeText(this@SettingsActivity, "Enter a map name first", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (name == app.activeProject) {
                    Toast.makeText(
                        this@SettingsActivity,
                        "Enter a new map name first (currently $name)",
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                if (saveFormTo(name)) {
                    app.setActiveProject(name)
                    Toast.makeText(this@SettingsActivity, "Saved as $name", Toast.LENGTH_LONG).show()
                    finish()
                } else {
                    Toast.makeText(this@SettingsActivity, "Save failed", Toast.LENGTH_LONG).show()
                }
            }
        })
        box.addView(actionButton("Restore default settings").apply {
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@SettingsActivity)
                    .setTitle("Restore defaults?")
                    .setMessage("Overwrite ${app.activeProject} with the shipped default settings? Layer list is kept.")
                    .setPositiveButton("Restore") { _, _ ->
                        if (restoreDefaults()) recreate() else Toast.makeText(
                            this@SettingsActivity, "Restore failed", Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })
        return box
    }

    /** Collect the form into a Settings value (shared by Save paths). */
    private fun formSettings(): LayerStyle.Settings {
        val lo = minOf(zoomMin, zoomMax)
        val hi = maxOf(zoomMin, zoomMax)
        return LayerStyle.Settings(
            workingCrs = workingCrs,
            autosaveSeconds = autosave.toIntOrNull(),
            trackIntervalSeconds = trackInterval.toIntOrNull(),
            trackMinDistanceM = trackMinDist.toDoubleOrNull() ?: 5.0,
            zoomMin = lo,
            zoomMax = hi,
            defaultScale = defaultScale.coerceIn(lo, hi),
            goToLayer = goToLayer,
            goToColumn = goToColumn,
            addPointLayer = addPointLayer,
            mobLayer = mobLayer,
            trackLayer = trackLayer,
        )
    }

    /** Save the form into the active file. [stay]=true keeps the page open. */
    private fun saveFormToActive(stay: Boolean): Boolean {
        val ok = try {
            app.writeProject(
                app.activeProject,
                LayerStyle.docWithSettings(projectDocText(), formSettings()),
            )
        } catch (e: Exception) {
            false
        }
        Toast.makeText(
            this,
            if (ok) "Settings saved to ${app.activeProject}" else "Save failed",
            Toast.LENGTH_LONG,
        ).show()
        return ok
    }

    /** Save the form under another project name (layers carried over). */
    private fun saveFormTo(name: String): Boolean {
        // Carry the layers over from the current doc (or pristine defaults).
        val layersText = try {
            val cur = projectDocText()
            if (cur != null) org.json.JSONObject(cur).optJSONArray("layers")?.toString()
            else null
        } catch (e: Exception) {
            null
        } ?: try {
            assets.open("mirshp.json").readBytes().toString(Charsets.UTF_8).let {
                org.json.JSONObject(it).optJSONArray("layers")?.toString()
            }
        } catch (e: Exception) {
            null
        }
        return try {
            val root = org.json.JSONObject()
            root.put("layers", org.json.JSONArray(layersText ?: "[]"))
            root.put("settings", LayerStyle.settingsToJson(formSettings()))
            app.writeProject(name, root.toString(1))
        } catch (e: Exception) {
            false
        }
    }

    /** Overwrite the active file's settings with shipped defaults (layers kept). */
    private fun restoreDefaults(): Boolean {
        return try {
            val doc = LayerStyle.docWithSettings(projectDocText(), LayerStyle.Settings())
            app.writeProject(app.activeProject, doc)
        } catch (e: Exception) {
            false
        }
    }

    // ---- new-layer templates ---------------------------------------------------

    /** Adapter so ShpAuxiliaryExporter writes .prj/.cpg/.xml/.apl next to the shapefiles. */
    private fun mapAuxStorage(): org.gcap.core.GCapStorage = object : org.gcap.core.GCapStorage {
        override fun listDatNames() = emptyList<String>()
        override fun readBytes(name: String) = mapDir.readFile(name)
        override fun writeBytes(name: String, bytes: ByteArray): Boolean {
            val existing = mapDir.listFiles().firstOrNull { it.equals(name, true) }
            return if (existing != null) mapDir.overwriteFile(existing, bytes)
            else mapDir.createFile(name, bytes)
        }
        override fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray) =
            writeBytes(name, bytes)
        override fun delete(name: String) {}
        override fun describe() = "GPS_MAP"
    }

    private fun lbl(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 13f
    }

    private fun typeRadio(type: Int): RadioGroup {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val point = RadioButton(this).apply { text = "Point"; id = 0 }
        val line = RadioButton(this).apply { text = "Line"; id = 1 }
        group.addView(point)
        group.addView(line)
        group.check(type)
        return group
    }

    private fun crsRadio(crs: Int): RadioGroup {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val utm = RadioButton(this).apply { text = "UTM Zone 10N"; id = 0 }
        val wgs = RadioButton(this).apply { text = "WGS 84"; id = 1 }
        group.addView(utm)
        group.addView(wgs)
        group.check(crs)
        return group
    }

    /**
     * New-layer form (name, point/line, UTM/WGS84, two dbf fields). Invalid
     * input re-opens the form with the entered values kept.
     */
    private fun createLayerDialog(
        nameD: String?,
        typeD: Int,
        crsD: Int,
        f1D: String?,
        f2D: String?,
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }
        val nameInput = EditText(this).apply {
            setText(nameD.orEmpty())
            setSingleLine(true)
            hint = "e.g. NEW_POINTS"
            textSize = 14f
        }
        val typeGroup = typeRadio(typeD)
        val crsGroup = crsRadio(crsD)
        val f1Input = EditText(this).apply {
            setText(f1D ?: "NAME")
            setSingleLine(true)
            textSize = 14f
        }
        val f2Input = EditText(this).apply {
            setText(f2D ?: "NOTE")
            setSingleLine(true)
            hint = "blank for one field"
            textSize = 14f
        }
        box.addView(lbl("Layer name"))
        box.addView(nameInput)
        box.addView(lbl("Type"))
        box.addView(typeGroup)
        box.addView(lbl("Coordinate system"))
        box.addView(crsGroup)
        box.addView(lbl("Field 1 (text, 15 chars)"))
        box.addView(f1Input)
        box.addView(lbl("Field 2 (text, 15 chars)"))
        box.addView(f2Input)
        AlertDialog.Builder(this)
            .setTitle("Create new layer")
            .setView(box)
            .setPositiveButton("Create") { _, _ ->
                val type = typeGroup.checkedRadioButtonId
                val crs = crsGroup.checkedRadioButtonId
                createNewLayer(
                    nameInput.text.toString(),
                    f1Input.text.toString(),
                    f2Input.text.toString(),
                    type,
                    crs,
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createNewLayer(
        rawName: String,
        rawF1: String,
        rawF2: String,
        type: Int,
        crs: Int,
    ) {
        val stem = LayerImport.cleanLayerName(rawName)
        if (stem == null) {
            Toast.makeText(this, "Use letters, numbers, _ or - (no / \\ ? % * : | \" < >)", Toast.LENGTH_LONG).show()
            createLayerDialog(rawName, type, crs, rawF1, rawF2)
            return
        }
        if (mapDir.listFiles().any { LayerImport.stemOf(it).equals(stem, true) }) {
            Toast.makeText(this, "$stem already exists in the Layers folder", Toast.LENGTH_LONG).show()
            createLayerDialog(stem, type, crs, rawF1, rawF2)
            return
        }
        val f1 = LayerImport.cleanDbfField(rawF1)
        if (f1 == null) {
            Toast.makeText(this, "Field 1: 1–10 chars, A–Z 0–9 _, starts with a letter", Toast.LENGTH_LONG).show()
            createLayerDialog(stem, type, crs, rawF1, rawF2)
            return
        }
        val f2 = if (rawF2.trim().isEmpty()) null else LayerImport.cleanDbfField(rawF2)
        if (rawF2.trim().isNotEmpty() && f2 == null) {
            Toast.makeText(this, "Field 2: 1–10 chars, A–Z 0–9 _, starts with a letter", Toast.LENGTH_LONG).show()
            createLayerDialog(stem, type, crs, rawF1, rawF2)
            return
        }
        if (f2 != null && f2.equals(f1, true)) {
            Toast.makeText(this, "Field 1 and Field 2 must differ", Toast.LENGTH_LONG).show()
            createLayerDialog(stem, type, crs, rawF1, rawF2)
            return
        }
        val fields = if (f2 != null) listOf(f1 to 15, f2 to 15) else listOf(f1 to 15)
        val kindName: String
        val shpBytes: ByteArray
        val shxBytes: ByteArray
        if (type == 0) {
            shpBytes = ShpFormat.buildPointShp(emptyList())
            shxBytes = ShpFormat.buildPointShx(emptyList())
            kindName = "point"
        } else {
            shpBytes = ShpFormat.buildPolylineShp(emptyList())
            shxBytes = ShpFormat.buildPolylineShx(emptyList())
            kindName = "line"
        }
        val dbfBytes = ShpFormat.buildDbfFields(fields, emptyList())
        if (!mapDir.createFile("$stem.shp", shpBytes)) {
            Toast.makeText(
                this,
                "Cannot write $stem.shp — is GPS_MAP present in the picked folder?",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        mapDir.createFile("$stem.shx", shxBytes)
        mapDir.createFile("$stem.dbf", dbfBytes)
        val prj = if (crs == 0) null else ShpAuxiliaryExporter.PRJ_WGS84
        if (prj != null) ShpAuxiliaryExporter.writeAuxFiles(mapAuxStorage(), stem, prj)
        else ShpAuxiliaryExporter.writeAuxFiles(mapAuxStorage(), stem)
        val crsName = if (crs == 0) "UTM Zone 10N" else "WGS 84"
        val fieldNames = fields.joinToString { it.first }
        Toast.makeText(
            this,
            "Layer $stem created ($kindName, $crsName, $fieldNames). " +
                "Restart the map (Close_MAP/Start_MAP) to show it.",
            Toast.LENGTH_LONG,
        ).show()
    }

    // ---- verify layers ---------------------------------------------------------

    /**
     * Check every .shp in the Layers folder (sidecars, header, CRS, project
     * match) on a background thread, then report per layer. Read-only: fixes
     * nothing, so a scary report can't break data.
     */
    private fun verifyLayers() {
        Toast.makeText(this, "Checking layers…", Toast.LENGTH_SHORT).show()
        Thread {
            val report = buildVerifyReport()
            runOnUiThread { showVerifyReport(report) }
        }.start()
    }

    private fun buildVerifyReport(): String {
        val out = StringBuilder()
        val files: List<String>
        try {
            files = mapDir.listFiles().filter { it.endsWith(".shp", true) }.sorted()
        } catch (e: Exception) {
            return "Cannot list the Layers folder: ${e.message}"
        }
        if (files.isEmpty()) return "No .shp files in the Layers folder."
        val specs = try {
            LayerStyle.parseProject(projectDocText() ?: "{\"layers\":[]}")
        } catch (e: Exception) { emptyList() }
        val match = try {
            LayerStyle.match(files, specs)
        } catch (e: Exception) { null }
        var ok = 0; var warn = 0; var skip = 0; var err = 0
        for (f in files) {
            val stem = LayerImport.stemOf(f)
            val problems = mutableListOf<String>()
            // Sidecars.
            val names = try { mapDir.listFiles() } catch (e: Exception) { emptyList() }
            fun has(ext: String) = names.any {
                LayerImport.stemOf(it).equals(stem, true) && it.endsWith(".$ext", true)
            }
            if (!has("shx")) problems.add("no .shx")
            if (!has("dbf")) problems.add("no .dbf (no labels/attributes)")
            val prjName = names.firstOrNull {
                LayerImport.stemOf(it).equals(stem, true) &&
                    (it.endsWith(".prj", true) || it.endsWith(".PRJ", true))
            }
            // Header + CRS.
            var kind = "?"
            var crsNote = ""
            try {
                val head = mapDir.readHeader(f, 100)
                if (head == null || head.size < 100) {
                    problems.add("unreadable header")
                } else {
                    val t = try { ShpFormat.shpHeader(head)?.shapeType } catch (e: Exception) { null }
                    kind = when (t) {
                        ShpFormat.TYPE_POINT, ShpFormat.TYPE_POINTZ -> "point"
                        ShpFormat.TYPE_POLYLINE, ShpFormat.TYPE_POLYLINEZ -> "line"
                        5, 15, 25 -> "polygon"
                        else -> "type $t?"
                    }
                }
            } catch (e: Exception) {
                problems.add("unreadable (${e.message})")
            }
            if (prjName == null) {
                crsNote = "no .prj (assumed UTM)"
            } else {
                val prj = try {
                    LayerImport.decodePrj(mapDir.readFile(prjName))
                } catch (e: Exception) { null }
                crsNote = when (Utm.classifyCrs(prj)) {
                    Utm.Crs.UTM_METERS -> "UTM"
                    Utm.Crs.WGS84_DEGREES -> "WGS84"
                    Utm.Crs.ALBERS -> "Albers"
                    Utm.Crs.OTHER -> {
                        problems.add("unrecognized CRS — will be skipped")
                        "CRS?"
                    }
                }
            }
            // Project match.
            val spec = match?.ordered?.firstOrNull {
                LayerImport.stemOf(it.file).equals(stem, true)
            }?.spec
            val rowNote = if (spec == null) "no project row (defaults)"
            else "${if (spec.visible) "ON" else "OFF"}${if (spec.editable) ", editable" else ""}"
            // Verdict.
            val status = when {
                problems.any { it.startsWith("unreadable") || it.startsWith("unrecognized") } -> { skip++; "SKIP" }
                problems.isNotEmpty() -> { warn++; "WARN" }
                else -> { ok++; "OK" }
            }
            out.append("$f [$kind, $crsNote, $rowNote] $status")
            if (problems.isNotEmpty()) out.append(" — ${problems.joinToString("; ")}")
            out.append("\n")
        }
        out.append("\n${files.size} layers: $ok OK, $warn warnings, $skip skipped, $err errors.")
        return out.toString()
    }

    private fun showVerifyReport(report: String) {
        val scroll = ScrollView(this)
        val text = TextView(this).apply {
            text = report
            textSize = 12f
            setPadding(24, 16, 24, 16)
            setTextIsSelectable(true)
        }
        scroll.addView(text)
        AlertDialog.Builder(this)
            .setTitle("Layers compatibility")
            .setView(scroll)
            .setPositiveButton("OK", null)
            .show()
    }
}
