package org.shppointcap.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.gcap.core.NtsSheets
import org.gcap.core.ShpAuxiliaryExporter
import org.gcap.core.ShpFormat
import org.shppointcap.app.map.Albers
import org.shppointcap.app.map.FileMapDir
import org.shppointcap.app.map.LayerImport
import org.shppointcap.app.map.LayerStyle
import org.shppointcap.app.map.MapCanvasView
import org.shppointcap.app.map.MapFileDir
import org.shppointcap.app.map.SafMapDir
import org.shppointcap.app.map.Utm
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Forest Point Collector — main page (point_collector_ODS.ods).
 *
 * 6x4 function grid (24 buttons) over a north-oriented UTM canvas, shapefile
 * layers from GPS_MAP, tap-to-add / select / delete point editing saved back
 * to the editable layer, live GPS fix with WGS84->UTM projection, 17 readout
 * overlays, pan mode, Go to…, track recording (shp polyline + GPX), undo.
 *
 * What v1 deliberately leaves out (next steps): the P1/P2/P3 sample capture
 * form, background-map tiles.
 */
class MapActivity : AppCompatActivity() {

    companion object {
        const val EDITABLE_DEFAULT = "xgv_mp"
        const val LABEL_FIELD = "NAME"
    }

    /** General settings, reloaded on every map start (see Settings page). */
    private var settings = LayerStyle.Settings()

    /** Active project doc text: data-folder file first, pristine assets fallback. */
    private fun projectDocText(): String? =
        app.projectText() ?: try {
            assets.open("mirshp.json").readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) { null }

    private fun loadSettings(): LayerStyle.Settings =
        LayerStyle.parseSettings(projectDocText() ?: "{}")

    /** Working UTM zone for conversions: explicit zone, WGS84/live auto, else first layer. */
    private fun workingZone(): Int {
        return LayerStyle.resolveWorkingZone(settings.workingCrs, latestLoc?.longitude, firstLayerZone())
    }

    /**
     * First layer's UTM zone from its .prj. The SAF listing + project parse
     * behind it cost seconds on a slow phone, and [workingZone] runs on the
     * 1-second overlay ticker plus every fix and command — so cache it. The
     * folder/specs change rarely; invalidated on map start, folder switch,
     * resume, and layer import.
     */
    private var firstZoneCached = false
    private var firstZoneValue: Int? = null

    private fun firstLayerZone(): Int? {
        if (!firstZoneCached) {
            firstZoneCached = true
            firstZoneValue = try {
                val files = mapDir.listFiles().filter { it.endsWith(".shp", true) }
                val specs = loadSpecs()
                val first = LayerStyle.match(files, specs).ordered.firstOrNull()?.file
                first?.let { prjFor(it) }?.let { prj ->
                    prj.split("UTM_Zone").getOrNull(1)?.filter { it.isDigit() }?.toIntOrNull()
                }
            } catch (e: Exception) { null }
        }
        return firstZoneValue
    }

    private fun invalidateFirstZone() { firstZoneCached = false }

    /** Active editable point layer (Add_Point target). Default XGV_MP; the
     *  "Editable layer" switcher (settings phase) will change this at runtime. */
    private var editableName = EDITABLE_DEFAULT

    private val LAYER_COLORS = listOf(
        0xFF444444.toInt(), 0xFF3366CC.toInt(), 0xFFCC6600.toInt(),
        0xFF8A2BE2.toInt(), 0xFF00897B.toInt(), 0xFFA63D40.toInt(),
    )

    private lateinit var app: CollectorApp
    private lateinit var mapDir: MapFileDir
    private lateinit var mapView: MapCanvasView

    private var mapStarted = false
    private var mapDirKey: String? = null
    private var editing = false
    private var addArm = false
    private var selectArm = false
    private var dirty = false
    private var loadSeq = 0L
    private var lastLoadedE = Double.NEGATIVE_INFINITY
    private var lastLoadedN = Double.NEGATIVE_INFINITY

    private var origEditable: List<ShpFormat.Shape> = emptyList()
    private var editableLabels: List<String> = emptyList()
    private val addedPoints = mutableListOf<EditPoint>()
    private val deletedIdx = mutableSetOf<Int>()

    private var latestLoc: android.location.Location? = null
    private var locListener: LocationListener? = null
    private var gnssCallback: android.location.GnssStatus.Callback? = null
    private var satCount = -1

    /** Go-to target picked from the settings layer (bearing/distance source). */
    private var goToPoint: DoubleArray? = null
    private var goToLabel = ""

    /** Centre pinned on a Go to… pick (map stops following the fix until
     *  Center at your location / another Go to / map close). */
    private var viewPinned = false

    /** Undo stack for edit-buffer changes (Undo last edit). */
    private val undoStack = ArrayDeque<UndoOp>()

    /** Start_MAP builds every layer's header + .prj through SAF — dozens of
     *  provider queries. On a slow phone that is tens of seconds on the main
     *  thread (ANR), so it runs on this single background thread. */
    private val layerLoader = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** loadViewport reads a FULL shapefile per layer; cap concurrency so a slow
     *  phone is not thrashed by simultaneous multi-MB reads (2 threads halves
     *  peak parse memory vs 4 on 3 GB devices). */
    private val shapeLoader = java.util.concurrent.Executors.newFixedThreadPool(2)

    /** Start_MAP layer build in progress (Start_MAP ignored while true). */
    private var mapLoading = false

    /** Initial shape-load progress (startMap only): toast "Map ready" at 100%. */
    private var initialLoadSeq = -1L
    private var initialLoadTotal = 0
    private var initialLoadDone = 0

    /** Cached go-to file (keyed by settings value) to avoid SAF per viewport. */
    private var goToFileCache: Pair<String, String?>? = null

    private sealed class UndoOp {
        data class Added(val point: EditPoint) : UndoOp()
        data class DeletedOrig(val index: Int) : UndoOp()
        data class DeletedAdded(val point: EditPoint) : UndoOp()
    }

    /** Track recording (Record track / Stop recording track). */
    private var trackRecording = false
    private val trackPts = mutableListOf<DoubleArray>()

    /** True while a shapefile write runs on a worker thread. */
    private var writeBusy = false
    private var trackTick: Runnable? = null

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    data class EditPoint(val e: Double, val n: Double, val name: String)

    /** Add layer: pick one or more files (a .shp plus its sidecars). */
    private val openLayers = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { res ->
        val intent = res.data ?: return@registerForActivityResult
        val uris = ArrayList<Uri>()
        intent.data?.let { uris.add(it) }
        val clip = intent.clipData
        if (clip != null) for (i in 0 until clip.itemCount) clip.getItemAt(i)?.uri?.let { uris.add(it) }
        if (uris.isNotEmpty()) importLayers(uris.distinct())
    }

    private val requestPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLocationListener()
        else Toast.makeText(this, "Location permission denied - enter UTM by hand", Toast.LENGTH_LONG).show()
    }

    /**
     * Surface a crash from the previous run. Field crashes are hard to
     * reproduce, so the recorded summary is shown once on the next launch
     * instead of being lost in logcat.
     */
    private fun showCrashIfAny() {
        val summary = app?.consumeCrash() ?: return
        val where = app?.crashFile()?.absolutePath ?: "unavailable"
        Handler(Looper.getMainLooper()).postDelayed({
            AlertDialog.Builder(this)
                .setTitle("The app closed unexpectedly")
                .setMessage("$summary\n\nFull log:\n$where")
                .setPositiveButton("OK", null)
                .show()
        }, 900)
    }

    // ---- layout roots, reparented on rotation (see relayout) ---------------
    private var rootLayout: LinearLayout? = null
    private var leftPanel: LinearLayout? = null

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        // Handled via android:configChanges so a rotation does NOT recreate the
        // activity: without it every rotation threw away mapStarted, the layer
        // list, the recorded-track buffer and the undo stack, and the app came
        // back on "Press Start_MAP" as if it had never run.
        super.onConfigurationChanged(newConfig)
        // The activity survives, so the view tree must be re-flowed by hand:
        // landscape puts the function buttons in a left column beside a much
        // wider map; portrait keeps the stacked layout.
        relayout()
    }

    /**
     * Portrait: buttons above the map (unchanged). Landscape: buttons in a left
     * column, map filling the rest. In landscape the 6 button rows share the
     * full screen height, so they stay finger-sized instead of collapsing to
     * ~20 px slivers.
     */
    private fun relayout() {
        val root = rootLayout ?: return
        val left = leftPanel ?: return
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        (left.parent as? ViewGroup)?.removeView(left)
        (mapView.parent as? ViewGroup)?.removeView(mapView)
        root.orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        root.addView(
            left,
            if (landscape) {
                // Weights are proportional, so 0.66 : 1 gives the buttons ~40%
                // of the width. At 0.44 : 1 they got 31% and long labels broke
                // mid-word ("Delete_Poi nt").
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.66f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.45f)
            },
        )
        root.addView(
            mapView,
            if (landscape) {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            } else {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as CollectorApp
        initMapDir()
        showCrashIfAny()

        val root = LinearLayout(this).apply { setPadding(4, 4, 4, 4) }
        rootLayout = root

        // ---- title row: the app name only (the action bar; spec row 1) -------

        // ---- function grid: 6 rows x 4 columns (point_collector_ODS.ods) ----
        // Column-major order from the spec: col 1 Start_MAP..Center at your
        // location, col 2 Layer settings..Zoom_IN, col 3 Select..Zoom_OUT,
        // col 4 Start Editing..MOB.
        val labels = arrayOf(
            arrayOf("Start_MAP", "Layer settings", "Select feature", "Start Editing"),
            arrayOf("Close_MAP", "Add layer", "Unselect feature", "Stop Editing"),
            arrayOf("Settings", "Remove layer", "Add_Point", "Editable layer"),
            arrayOf("EXIT", "Go to…", "Delete_Point", "Undo last edit"),
            arrayOf("Pan", "Record track", "Stop recording track", "Save Edits"),
            arrayOf("Center at your location", "Zoom_IN", "Zoom_OUT", "MOB"),
        )
        val actions: Array<Array<() -> Unit>> = arrayOf(
            arrayOf(
                { startMapFlow() },
                { startActivity(Intent(this, LayerSettingsActivity::class.java)) },
                { armSelect() },
                { startEditing() },
            ),
            arrayOf(
                { closeMap() },
                { pickLayer() },
                { unselect() },
                { stopEditing() },
            ),
            arrayOf(
                { startActivity(Intent(this, SettingsActivity::class.java)) },
                { removeLayer() },
                { armAddPoint() },
                { editableLayerDialog() },
            ),
            arrayOf(
                { exitApp() },
                { goToDialog() },
                { deletePoint() },
                { undoLastEdit() },
            ),
            arrayOf(
                { panMode() },
                { startTrack() },
                { stopTrack() },
                { saveEdits() },
            ),
            arrayOf(
                { centerAtGps() },
                { zoomIn() },
                { zoomOut() },
                { mob() },
            ),
        )
        // Buttons take ~30% of the screen, the map the remaining ~66%.
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (r in labels.indices) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (c in 0 until 4) {
                row.addView(
                    mapBtn(labels[r][c], actions[r][c]),
                    // width 0 + weight: columns share the row; height fills the
                    // row (the row height comes from the grid weight below).
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
                        .apply { setMargins(2, 1, 2, 1) },
                )
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // The grid lives in a left column that relayout() parents either above
        // the map (portrait) or beside it (landscape).
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        leftPanel = left

        // ---- canvas ------------------------------------------------------------
        mapView = MapCanvasView(this)
        mapView.showP1P2P3 = false
        mapView.onMapTap = { e, n ->
            when {
                addArm -> placePoint(e, n)
                selectArm -> selectFeatureAt(e, n)
            }
        }
        // Panning reloads only after a meaningful move (loadViewport threshold).
        mapView.onPan = { loadViewport(false); updateOverlays() }
        mapView.overlayProject = "Project: ${app.activeProject}"
        relayout()
        setContentView(root)
        startLocationListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        autosaveStop()
        uiHandler.removeCallbacks(overlayTick)
        if (trackRecording) stopTrack(save = false)
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locListener?.let { lm.removeUpdates(it) }
        locListener = null
        gnssCallback?.let { lm.unregisterGnssStatusCallback(it) }
        gnssCallback = null
    }

    // ---- autosave (Save Edits every N seconds, silent; blank = off) ----------

    private val autosaveHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var autosaveTick: Runnable? = null

    private fun autosaveStart() {
        autosaveStop()
        val s = settings.autosaveSeconds ?: return
        val tick = object : Runnable {
            override fun run() {
                if (editing && dirty) commitEdits(quiet = true)
                autosaveTick = this
                autosaveHandler.postDelayed(this, s * 1000L)
            }
        }
        autosaveTick = tick
        autosaveHandler.postDelayed(tick, s * 1000L)
    }

    private fun autosaveStop() {
        autosaveTick?.let { autosaveHandler.removeCallbacks(it) }
        autosaveTick = null
    }

    /** 1 s ticker: refreshes the Date/Time overlays (the other readouts move
     *  with the fix, the viewport, or a Go to… pick). */
    private val overlayTick = object : Runnable {
        override fun run() {
            if (mapStarted && ::mapView.isInitialized) updateOverlays()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    override fun onResume() {
        super.onResume()
        settings = loadSettings()
        invalidateFirstZone()
        autosaveStart()
        uiHandler.removeCallbacks(overlayTick)
        uiHandler.postDelayed(overlayTick, 1000L)
        // Settings → Layers folder → Change… may have swapped the backend.
        if (initMapDir() && mapStarted) {
            Toast.makeText(
                this,
                "Layers folder changed — Close_MAP then Start_MAP to load it",
                Toast.LENGTH_LONG,
            ).show()
        }
        if (::mapView.isInitialized) {
            mapView.overlayProject = "Project: ${app.activeProject}"
            updateOverlays()
        }
    }

    override fun onPause() {
        autosaveStop()
        uiHandler.removeCallbacks(overlayTick)
        super.onPause()
    }

    /** Re-resolves the layer folder; returns true when it changed since last time. */
    private fun initMapDir(): Boolean {
        val key = app.safTreeUri?.toString() ?: "file"
        val changed = key != mapDirKey
        if (changed) invalidateFirstZone()
        mapDirKey = key
        // No SAF folder picked yet: private empty dir (never the old BAF_cap
        // Download/ForestPoints) so the "pick a folder" hints show instead of
        // silently loading stale files.
        mapDir = app.safTreeUri?.let { SafMapDir(this, it) }
            ?: FileMapDir(File(cacheDir, "no-folder-picked"))
        return changed
    }

    /**
     * One main-page button.
     *
     * Coloured by what it does, matching Forest Navigator so a crew who knows one app
     * knows this one: orange for the commands that start and stop work, yellow for
     * everything else you can do, grey for what is currently unavailable.
     */
    private fun mapBtn(t: String, onClick: () -> Unit): Button {
        val d = resources.displayMetrics.density
        val b = Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 10f
            setPadding(2, 2, 2, 2)
            setTextColor(
                when {
                    // Add_Point is the one button a crew reaches for in a hurry, often
                    // with wet or gloved hands. Red on dark blue reads as "the one to
                    // press" from further away than yellow does.
                    t == "Add_Point" -> Palette.ADD_POINT_TEXT
                    else -> Palette.ACTION_TEXT
                }
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = (4 * d).toInt().toFloat()
                val w = (2 * d).toInt().coerceAtLeast(2)
                when {
                    t == "Add_Point" -> {
                        setColor(Palette.ADD_POINT_FILL); setStroke(w, Palette.ADD_POINT_BORDER)
                    }
                    t in COMMAND_BUTTONS -> {
                        setColor(Palette.COMMAND_FILL); setStroke(w, Palette.COMMAND_BORDER)
                    }
                    // Everything else is an action: yellow on black with a red border.
                    // No button is greyed out for being unavailable at launch - eleven of
                    // them were, and a screen of grey buttons reads as broken rather than
                    // as "not yet". Each one says plainly when it has nothing to act on.
                    else -> {
                        setColor(Palette.ACTION_FILL); setStroke(w, Palette.ACTION_BORDER)
                    }
                }
            }
            setOnClickListener { onClick() }
        }
        return b
    }

    /** The commands that start or stop work, and so are shown in orange. */
    private val COMMAND_BUTTONS = setOf(
        "Start_MAP", "Close_MAP", "Start Editing", "Stop Editing",
        "Record track", "Stop recording track", "EXIT", "Save Edits",
    )

    // ---- GPS -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    fun startLocationListener() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            return
        }
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        latestLoc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (gnssCallback == null) {
            val cb = object : android.location.GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: android.location.GnssStatus) {
                    satCount = status.satelliteCount
                    if (::mapView.isInitialized) updateOverlays()
                }
            }
            gnssCallback = cb
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(cb)
        }
        if (locListener != null) return
        // Every abstract method must be implemented, not just onLocationChanged.
        // On API 28 (Android 9) LocationListener also declares abstract
        // onProviderEnabled/onProviderDisabled/onStatusChanged; a class that
        // omits them throws AbstractMethodError the moment the platform calls
        // one - which happens whenever GPS is toggled or power saving disables
        // the provider. That is a hard crash in the field, not a silent no-op.
        val listener = object : LocationListener {
            override fun onLocationChanged(loc: android.location.Location) {
                latestLoc = loc
                onFix(loc)
            }

            @Deprecated("Required on API < 29; the platform no longer calls it.")
            override fun onStatusChanged(
                provider: String?,
                status: Int,
                extras: android.os.Bundle?,
            ) {
            }

            override fun onProviderEnabled(provider: String) {
                android.util.Log.i("GPS", "provider enabled: $provider")
            }

            override fun onProviderDisabled(provider: String) {
                // No fix will arrive until the user re-enables GPS. Say so
                // rather than leaving a stale marker on the map.
                android.util.Log.i("GPS", "provider disabled: $provider")
                latestLoc = null
                if (::mapView.isInitialized) {
                    mapView.setGpsMarker(0.0, 0.0, 0.0, false)
                    updateOverlays()
                }
            }
        }
        locListener = listener
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, listener)
        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, listener)
    }

    private fun onFix(loc: android.location.Location) {
        // Working zone, never the fix's natural zone: across a zone boundary
        // natural-zone metres land hundreds of km off on a working-zone map.
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        if (mapStarted && ::mapView.isInitialized && !mapView.panning && !viewPinned) {
            mapView.centerE = e
            mapView.centerN = n
            mapView.setGpsMarker(e, n, loc.accuracy.toDouble(), true)
            loadViewport(false)
        }
        updateOverlays()
    }

    /**
     * MOB: capture the live fix into the fixed POINTS.shp layer with an
     * auto DDHHMM name the user may change before saving. When POINTS is the
     * active editable layer the point joins the edit buffer (saved with Save
     * Edits); otherwise it is appended to POINTS.shp straight away so an
     * unrelated edit session can never clobber it.
     */
    private fun mob() {
        if (!mapStarted) { Toast.makeText(this, "Start_MAP first", Toast.LENGTH_SHORT).show(); return }
        val loc = latestLoc
        if (loc == null) { Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_LONG).show(); return }
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        mapView.centerE = e
        mapView.centerN = n
        if (stem(editableName).equals("points", true)) {
            if (!editing) { Toast.makeText(this, "Start Editing first", Toast.LENGTH_SHORT).show(); return }
            placePoint(e, n, Utm.mobAutoName(System.currentTimeMillis()))
        } else {
            mobDirect(e, n)
        }
    }

    private fun mobDirect(e: Double, n: Double) {
        val input = EditText(this).apply {
            setText(Utm.mobAutoName(System.currentTimeMillis()))
            setSingleLine(true)
            textSize = 12f
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("MOB point")
            .setMessage("Name stored in POINTS.shp (Name, up to 15 chars)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { Utm.mobAutoName(System.currentTimeMillis()) }
                // Off the UI thread: 5 SAF writes cost ~20 s on a slow phone.
                runWrite("Saving MOB point…") { appendPointsFile(name, e, n) }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Append one named point to POINTS.shp/.shx/.dbf, preserving existing rows.
     * Coordinates are stored in the file's native CRS: a WGS84 (global) file
     * gets degrees (converted from working-zone metres), otherwise metres.
     * A WGS84 sidecar is never rewritten to UTM.
     */
    private fun appendPointsFile(name: String, e: Double, n: Double): String {
        val base = "points"
        val shpFile = mapDir.listFiles().firstOrNull { stem(it).equals(base, true) && it.endsWith(".shp", true) }
        // A POINTS.shp that does not exist yet is created as WGS 84 degrees
        // (the MOB feed's CRS). An existing file keeps its own CRS, and its
        // .prj is never touched — the data below is written in that same CRS,
        // so the .prj and the coordinates can never disagree.
        val isNew = shpFile == null
        val fileCrs = shpFile?.let { Utm.classifyCrs(prjFor(it)) } ?: Utm.Crs.WGS84_DEGREES
        val coords = ArrayList<DoubleArray>()
        val names = ArrayList<String>()
        if (shpFile != null) {
            try {
                val bytes = mapDir.readFile(shpFile)
                if (bytes != null) {
                    for (s in ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)) {
                        val c = s.parts.firstOrNull()?.firstOrNull() ?: continue
                        coords.add(doubleArrayOf(c[0], c[1]))
                    }
                }
            } catch (ex: Exception) { }
            names.addAll(pointsDbfNames())
        }
        if (fileCrs == Utm.Crs.WGS84_DEGREES) {
            val (lat, lon) = Utm.utmToLatLon(e, n, workingZone())
            coords.add(doubleArrayOf(lon, lat))
        } else {
            coords.add(doubleArrayOf(e, n))
        }
        names.add(name)
        var ok = writeMapFile("$base.shp", ShpFormat.buildPointShp(coords))
        ok = writeMapFile("$base.shx", ShpFormat.buildPointShx(coords)) && ok
        ok = writeMapFile("$base.dbf", ShpFormat.buildDbf(ByteArray(0), names, "Name", 15)) && ok
        if (ok) {
            // Sidecars: only for a brand-new file, and always as WGS 84 so the
            // .prj matches the degrees just written. Rewriting them on every
            // MOB cost seconds of SAF writes; rewriting them for an existing
            // file would relabel it and corrupt it.
            if (isNew) {
                ShpAuxiliaryExporter.writeCrsSidecars(
                    mapAuxStorage(),
                    base,
                    ShpAuxiliaryExporter.PRJ_WGS84,
                )
            }
            runOnUiThread { refreshPointsLayer() }
            return "MOB saved: $name"
        }
        return "MOB save failed - check folder write permission"
    }

    /** Existing NAME values of POINTS.shp (empty when absent/unreadable). */
    private fun pointsDbfNames(): List<String> {
        val dbfName = shpFileFor("points")?.let { listFileFor(it, ".dbf") } ?: return emptyList()
        val db = try { mapDir.readFile(dbfName) } catch (ex: Exception) { null } ?: return emptyList()
        return try {
            val fields = ShpFormat.dbfHeader(db).fields
            val idx = fields.indexOfFirst { it.name.equals("Name", ignoreCase = true) }
            if (idx < 0) emptyList()
            else ShpFormat.readDbfRows(db).map { it.values.getOrNull(idx) ?: "" }
        } catch (ex: Exception) { emptyList() }
    }

    /** Re-read POINTS.shp into its display layer after a direct MOB append. */
    private fun refreshPointsLayer() {
        var layer = mapView.layers.firstOrNull { it.name.equals("points", true) }
        if (layer == null) {
            // POINTS.shp was just created by MOB (absent at Start_MAP): add the
            // layer so the saved point shows without a map restart.
            showImportedLayer("points")
            layer = mapView.layers.firstOrNull { it.name.equals("points", true) } ?: return
            if (layer.labelField == null) layer.labelField = "Name"
        }
        val file = shpFileFor("points") ?: return
        val bytes = try { mapDir.readFile(file) } catch (ex: Exception) { null } ?: return
        val raw = try {
            ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
        } catch (ex: Exception) { emptyList() }
        // WGS84 (global) file: project to working-zone metres like loadViewport
        // does, or degrees plot as metres until the next viewport reload.
        val shapes = if (Utm.classifyCrs(prjFor(file)) == Utm.Crs.WGS84_DEGREES) {
            toUtm(raw, workingZone())
        } else raw
        val labels = fieldLabels(file, "Name")
        layer.shapes = shapes.mapIndexed { i, s ->
            s.copy(label = labels.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "POINTS_${i + 1}")
        }
        mapView.invalidate()
    }

    // ---- layers ---------------------------------------------------------------

    private fun stem(name: String): String = name.substringBeforeLast('.')

    private fun shpFileFor(name: String): String? {
        val list = mapDir.listFiles()
        return list.firstOrNull { stem(it).equals(name, true) && it.endsWith(".shp", true) }
            ?: list.firstOrNull { it.contains(name, true) && it.endsWith(".shp", true) }
    }

    /** Shape type -> canvas kind; shared with the Layer settings page. */
    private fun kindFor(shapeType: Int?): MapCanvasView.MapLayer.Kind =
        LayerStyle.kindFor(shapeType)

    /** Sidecar .prj text for a shapefile (tries .prj/.PRJ/.Prj), or null. */
    private fun prjFor(shpName: String): String? {
        val stem = stem(shpName)
        for (ext in listOf("prj", "PRJ", "Prj")) {
            val hit = mapDir.listFiles().firstOrNull { it.equals("$stem.$ext", false) }
            if (hit != null) {
                try {
                    return LayerImport.decodePrj(mapDir.readFile(hit))
                } catch (e: Exception) { /* fall through */ }
            }
        }
        return null
    }

    /** Geographic (degrees) shapes projected into the working UTM zone, with fresh bbox. */
    private fun toUtm(shapes: List<ShpFormat.Shape>, zone: Int): List<ShpFormat.Shape> =
        shapes.map { s ->
            val parts = s.parts.map { part ->
                part.map { pt ->
                    val (e, n) = Utm.wgs84ToUtmZone(pt[1], pt[0], zone)
                    doubleArrayOf(e, n)
                }
            }
            val xs = parts.flatten().map { it[0] }
            val ys = parts.flatten().map { it[1] }
            if (xs.isEmpty()) s else s.copy(
                parts = parts,
                xmin = xs.min(), ymin = ys.min(), xmax = xs.max(), ymax = ys.max(),
            )
        }

    /**
     * File-native shapes → working-zone UTM metres. UTM passes through;
     * WGS84 goes direct; Albers (VRI/BC-wide) routes via lat/lon using the
     * layer's own .prj parameters. Unparseable Albers yields nothing (never
     * garbage) — the layer simply stays empty with its toast already shown.
     */
    private fun projectShapes(
        shapes: List<ShpFormat.Shape>,
        crs: Utm.Crs,
        file: String?,
    ): List<ShpFormat.Shape> {
        val zone = workingZone()
        return when (crs) {
            Utm.Crs.WGS84_DEGREES -> toUtm(shapes, zone)
            Utm.Crs.ALBERS -> {
                val params = file?.let { prjFor(it) }?.let { Albers.params(it) }
                    ?: return emptyList()
                toUtm(shapes.map { s ->
                    s.copy(parts = s.parts.map { part ->
                        part.map { pt ->
                            val (lat, lon) = Albers.toWgs84(pt[0], pt[1], params)
                            doubleArrayOf(lon, lat)
                        }
                    })
                }, zone)
            }
            else -> shapes
        }
    }

    /**
     * Working-zone UTM metres → file-native coords for saves. Mirrors
     * [projectShapes]; UTM passes through.
     */
    private fun unprojectPoint(e: Double, n: Double, crs: Utm.Crs, file: String?): DoubleArray {
        val zone = workingZone()
        return when (crs) {
            Utm.Crs.WGS84_DEGREES -> {
                val (lat, lon) = Utm.utmToLatLon(e, n, zone)
                doubleArrayOf(lon, lat)
            }
            Utm.Crs.ALBERS -> {
                val params = file?.let { prjFor(it) }?.let { Albers.params(it) }
                    ?: return doubleArrayOf(e, n)
                val (lat, lon) = Utm.utmToLatLon(e, n, zone)
                val (ae, an) = Albers.fromWgs84(lat, lon, params)
                doubleArrayOf(ae, an)
            }
            else -> doubleArrayOf(e, n)
        }
    }

    /** Specs from the active project (fresh read every map start). */
    private fun loadSpecs(): List<LayerStyle.LayerSpec> {
        val text = projectDocText() ?: return emptyList()
        return try {
            LayerStyle.parseProject(text)
        } catch (e: Exception) { emptyList() }
    }

    private fun applySpec(
        layer: MapCanvasView.MapLayer,
        spec: LayerStyle.LayerSpec?,
        kind: MapCanvasView.MapLayer.Kind,
    ) {
        // Note: MapLayer.color is fixed at construction (resolved in
        // defaultLayers); this fills the remaining style fields only.
        layer.visible = spec?.visible ?: true
        when (kind) {
            MapCanvasView.MapLayer.Kind.POINT ->
                layer.pointSymbol = LayerStyle.pointSymbolOf(spec?.symbolRaw.orEmpty())
            MapCanvasView.MapLayer.Kind.LINE ->
                layer.lineSymbol = LayerStyle.lineSymbolOf(spec?.symbolRaw.orEmpty())
            MapCanvasView.MapLayer.Kind.POLYGON ->
                layer.polySymbol = LayerStyle.polySymbolOf(spec?.symbolRaw.orEmpty())
        }
        layer.symbolSizeDp = spec?.sizeDp ?: 2f
        layer.labelField = spec?.labelField
            ?: if (layer.name.equals("Ground_Call", true)) "Call_Num" else null
        layer.labelSizeSp = spec?.labelSizeSp ?: 7f
        layer.borderColor = spec?.colorRaw?.let { LayerStyle.borderOf(it) } ?: layer.color
    }

    private data class LayerBuild(
        val layers: List<MapCanvasView.MapLayer>,
        val skipped: List<String>,
        /** Union bbox in working-zone metres (UTM + projected WGS84), or null. */
        val dataBbox: DoubleArray?,
    )

    private fun defaultLayers(): LayerBuild {
        val specs = loadSpecs()
        val files = mapDir.listFiles().filter { it.endsWith(".shp", true) }
        val match = LayerStyle.match(files, specs)
        // Active editable: XGV_MP when eligible, else POINTS, else first eligible.
        editableName = match.activeEditable?.let { stem(it) } ?: EDITABLE_DEFAULT
        val skipped = mutableListOf<String>()
        val out = mutableListOf<MapCanvasView.MapLayer>()
        var ci = 0
        var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE
        var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
        var haveBbox = false
        fun expandBbox(x0: Double, y0: Double, x1: Double, y1: Double) {
            if (!x0.isFinite() || !y0.isFinite() || !x1.isFinite() || !y1.isFinite()) return
            if (x0 > x1 || y0 > y1) return
            // Plausible UTM North metres; rejects empty (0,0,0,0) and garbage.
            if (x0 < 100000 || x1 > 900000 || y0 < 0 || y1 > 10000000) return
            bx0 = minOf(bx0, x0); by0 = minOf(by0, y0)
            bx1 = maxOf(bx1, x1); by1 = maxOf(by1, y1)
            haveBbox = true
        }
        // Canvas draws in list order (later = on top): reverse display order.
        for (entry in match.ordered.reversed()) {
            val f = entry.file
            val spec = entry.spec
            val isEditable = stem(f).equals(editableName, true)
            // OFF layers (and only they) skip header/.prj reads entirely —
            // with most of 21 layers off, this is the bulk of Start_MAP I/O.
            // The active editable file always gets its stub/row.
            if (!isEditable && spec?.visible == false) continue
            // Header only — reading the whole (possibly multi-MB) layer here was
            // the main lag on slow devices; shapes load later in loadViewport.
            val bytes = try { mapDir.readHeader(f, 100) } catch (e: Exception) { null }
            if (bytes == null) {
                // Editable file may not exist on disk yet: styled stub.
                if (!isEditable) continue
                val color = if (spec?.colorRaw?.isNotBlank() == true) LayerStyle.colorOf(spec.colorRaw) else 0xFF2E7D32.toInt()
                out.add(MapCanvasView.MapLayer(stem(f), color, 10f, MapCanvasView.MapLayer.Kind.POINT, true).apply {
                    file = f
                    applySpec(this, spec, MapCanvasView.MapLayer.Kind.POINT)
                    if (labelField == null) labelField = LABEL_FIELD
                })
                continue
            }
            // Single-UTM-zone policy: non-UTM layers are skipped with a warning
            // instead of drawing garbage. Geographic (WGS84) layers convert.
            val crs = Utm.classifyCrs(prjFor(f))
            if (crs == Utm.Crs.OTHER) {
                skipped.add(stem(f))
                continue
            }
            val kind = try {
                val info = ShpFormat.shpHeader(bytes)
                // Union working-zone bbox for data-aware centering (empty and
                // garbage bboxes rejected inside expandBbox).
                if (info != null) {
                    when (crs) {
                        Utm.Crs.UTM_METERS -> expandBbox(info.xmin, info.ymin, info.xmax, info.ymax)
                        Utm.Crs.WGS84_DEGREES -> {
                            val zone = workingZone()
                            val corners = listOf(
                                Utm.wgs84ToUtmZone(info.ymin, info.xmin, zone),
                                Utm.wgs84ToUtmZone(info.ymin, info.xmax, zone),
                                Utm.wgs84ToUtmZone(info.ymax, info.xmin, zone),
                                Utm.wgs84ToUtmZone(info.ymax, info.xmax, zone),
                            )
                            expandBbox(
                                corners.minOf { it.first }, corners.minOf { it.second },
                                corners.maxOf { it.first }, corners.maxOf { it.second },
                            )
                        }
                        else -> {}
                    }
                }
                kindFor(info?.shapeType)
            } catch (e: Exception) { MapCanvasView.MapLayer.Kind.LINE }
            val color = if (spec?.colorRaw?.isNotBlank() == true) LayerStyle.colorOf(spec.colorRaw)
            else LAYER_COLORS[ci++ % LAYER_COLORS.size]
            out.add(MapCanvasView.MapLayer(stem(f), color, 2f, kind, isEditable).apply {
                file = f
                this.crs = crs
                applySpec(this, spec, kind)
            })
        }
        // Toasts need the main thread (this runs on layerLoader): the caller
        // reports skipped layers.
        return LayerBuild(
            out,
            skipped,
            if (haveBbox) doubleArrayOf(bx0, by0, bx1, by1) else null,
        )
    }

    private fun fieldLabels(shpFile: String, field: String): List<String> {
        val dbfName = listFileFor(shpFile, ".dbf") ?: return emptyList()
        val dbfBytes = mapDir.readFile(dbfName) ?: return emptyList()
        return try {
            val fields = ShpFormat.dbfHeader(dbfBytes).fields
            val idx = fields.indexOfFirst { it.name.equals(field, ignoreCase = true) }
            if (idx < 0) emptyList()
            else ShpFormat.readDbfRows(dbfBytes).map { it.values.getOrNull(idx) ?: "" }
        } catch (e: Exception) { emptyList() }
    }

    /**
     * Start_MAP entry: with several project files present, let the user pick
     * which map (project) to open first; a single project starts directly.
     */
    private fun startMapFlow() {
        if (mapView.layers.isNotEmpty()) {
            Toast.makeText(this, "Map already started", Toast.LENGTH_SHORT).show()
            return
        }
        val projects = try {
            app.listProjects()
        } catch (e: Exception) {
            emptyList()
        }
        if (projects.size <= 1) {
            if (projects.size == 1) app.setActiveProject(projects[0])
            startMap()
            return
        }
        var picked = projects.indexOf(app.activeProject).takeIf { it >= 0 } ?: 0
        // Backups are listed so a project file can be brought back, but marked: they are
        // something to restore from, not something to work on.
        val labels = projects.map { name ->
            val n = app.backupIndexOf(name)
            if (n == null) name else "$name  (backup $n - open to restore)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Open map")
            .setSingleChoiceItems(labels, picked) { _, which -> picked = which }
            .setPositiveButton("Open") { dialog, _ ->
                app.setActiveProject(projects[picked])
                dialog.dismiss()
                startMap()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startMap() {
        if (mapView.layers.isNotEmpty() || mapLoading) {
            Toast.makeText(this, "Map already started", Toast.LENGTH_SHORT).show()
            return
        }
        mapLoading = true
        mapStarted = true
        mapView.started = true
        mapView.panning = false
        viewPinned = false
        loadSeq++
        settings = loadSettings()
        invalidateFirstZone()
        mapView.scaleDenominator = settings.defaultScale.coerceIn(settings.zoomMin, settings.zoomMax)
        Toast.makeText(this, "Map started — loading layers…", Toast.LENGTH_SHORT).show()
        layerLoader.execute {
            try {
                // On a worker, before anything reads the project file: a fresh folder gets
                // a project file, and one whose layers changed gets brought into line.
                // Only ever called with a folder that was actually read, so a failed read
                // can never be mistaken for a folder that has no layers.
                runCatching {
                    val stems = mapDir?.listFiles().orEmpty()
                        .filter { it.endsWith(".shp", true) }
                        .map { it.substringBeforeLast('.') }
                    if (stems.isNotEmpty()) app.ensureProject(stems)
                }
                val build = defaultLayers()
                val layers = build.layers
                // Editable CRS comes from the layer list (canvas layers not added yet).
                val editableCrs = layers.firstOrNull { it.name.equals(editableName, true) }?.crs
                    ?: Utm.Crs.UTM_METERS
                val editable = loadEditableData(editableCrs)
                runOnUiThread {
                    // Guard on mapStarted/isDestroyed only — loadViewport has its
                    // own seq counter, and a GPS fix during the load must not
                    // invalidate this apply.
                    mapLoading = false
                    if (!mapStarted || isDestroyed) return@runOnUiThread
                    if (build.skipped.isNotEmpty()) {
                        Toast.makeText(
                            this,
                            "Skipped (not UTM Zone 10N): ${build.skipped.joinToString()}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    for (d in layers) {
                        val f = shpFileFor(d.name)
                        if (f == null) {
                            // Editable layer may not exist on disk yet; keep it with no shapes.
                            if (d.editable) mapView.addLayer(d)
                            continue
                        }
                        d.file = f
                        // NOTE: do NOT touch d.visible here — defaultLayers() already set it
                        // from project.json (a previous `= true` stomped OFF layers visible).
                        mapView.addLayer(d)
                    }
            mapView.editableLayerName = editableName
            val loc = latestLoc
            if (loc != null) {
                val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
                mapView.centerE = e; mapView.centerN = n
            }
            loadViewport(force = true, trackInitial = true)
                    applyEditableData(editable)
                    Toast.makeText(this, "Map started", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                android.util.Log.e("Perf", "startMap background task failed", e)
                runOnUiThread { mapLoading = false }
            }
        }
    }

    private fun shutdownMap() {
        mapStarted = false
        mapLoading = false
        loadSeq++
        try { mapDir.clearCaches() } catch (e: Exception) { }
        try { mapView.releaseCaches() } catch (e: Exception) { }
        mapView.started = false
        mapView.editing = false
        mapView.layers.clear()
        mapView.selected = null
        editing = false; addArm = false; selectArm = false
        addedPoints.clear(); deletedIdx.clear(); undoStack.clear(); dirty = false
        mapView.panning = false
        viewPinned = false
        goToPoint = null
        goToLabel = ""
        mapView.setGoToMarker(0.0, 0.0, "", false)
        mapView.overlayLeft = emptyList()
        mapView.overlayRight = emptyList()
        mapView.invalidate()
    }

    private fun closeMap() {
        if (editing && dirty) promptSaveEdits { shutdownMap() }
        else shutdownMap()
    }

    private fun zoomIn() {
        if (!requireLayersReady()) return
        // Pan mode (Pan button) is the free-form drag; zoom keeps the centre.
        mapView.scaleDenominator = maxOf(mapView.scaleDenominator / 2, settings.zoomMin)
        loadViewport(true)
    }

    private fun zoomOut() {
        if (!requireLayersReady()) return
        mapView.scaleDenominator = minOf(mapView.scaleDenominator * 2, settings.zoomMax)
        loadViewport(true)
    }

    private fun loadViewport(force: Boolean, trackInitial: Boolean = false) {
        if (!mapStarted) return
        val (w, h) = mapView.windowMeters()
        val cx = mapView.centerE; val cy = mapView.centerN
        if (!force && kotlin.math.abs(cx - lastLoadedE) < w * 0.4 &&
            kotlin.math.abs(cy - lastLoadedN) < h * 0.4
        ) return
        lastLoadedE = cx; lastLoadedN = cy
        val seq = ++loadSeq
        val x0 = cx - w / 2 - 50; val x1 = cx + w / 2 + 50
        val y0 = cy - h / 2 - 50; val y1 = cy + h / 2 + 50
        // Go-to file cached (shpFileFor is a SAF listing, not per-frame work).
        val goToKey = settings.goToLayer
        if (goToFileCache?.first != goToKey) {
            goToFileCache = goToKey to try { shpFileFor(goToKey) } catch (e: Exception) { null }
        }
        val goToFile = goToFileCache?.second
        // Field-tested load order: navigation first (Ground_Call), then the
        // small working layers (Transect, Access), then the active editable /
        // go-to / visible XGV_* crew layers, then Roads; the rest follow
        // Layer-settings order (unmatched bulk files sort last by match()).
        // Invisible (OFF) layers are skipped outright — no read, no parse.
        fun loadPriority(layer: MapCanvasView.MapLayer): Int {
            val n = layer.name
            return when {
                n.equals("Ground_Call", true) -> 0
                n.equals("Transect", true) || n.equals("Access", true) -> 1
                n.equals(editableName, true) -> 2
                goToFile != null && stem(goToFile).equals(n, true) -> 2
                n.startsWith("XGV_", true) && layer.visible -> 2
                n.equals("Roads", true) -> 3
                else -> 4
            }
        }
        val snapshot = mapView.layers
            .filter { !it.name.equals(editableName, true) && it.visible }
            .sortedWith(compareBy({ loadPriority(it) }, { it.file != goToFile },
                { it.kind != MapCanvasView.MapLayer.Kind.POINT }))
        if (trackInitial) {
            initialLoadSeq = seq
            initialLoadTotal = snapshot.size
            initialLoadDone = 0
            if (snapshot.isEmpty()) initialLoadSeq = -1L
        }
        // Bounded pool: a big file (21 MB Delineation) must never stall small
        // layers behind it, but a dozen simultaneous multi-MB reads thrash a
        // slow phone. The seq guard discards stale loads after Close_MAP / re-zoom.
        for (layer in snapshot) {
            shapeLoader.execute {
                if (mapStarted && seq == loadSeq) {
                    try {
                        val bytes = mapDir.readFile(layer.file) ?: return@execute
                        // Geographic / conic layers parse in native units, so the
                        // metre-viewport cull must not apply: read-all, project,
                        // then cull in metres.
                        val geo = layer.crs == Utm.Crs.WGS84_DEGREES || layer.crs == Utm.Crs.ALBERS
                        val raw = try {
                            if (geo) ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18)
                            else ShpFormat.readShp(bytes, x0, y0, x1, y1)
                        } catch (e: Exception) { emptyList() }
                        val shapes = if (geo) {
                            projectShapes(raw, layer.crs, layer.file)
                                .filterNot { it.xmax < x0 || it.xmin > x1 || it.ymax < y0 || it.ymin > y1 }
                        } else raw
                        val labelled = if (layer.labelField != null) {
                            val labels = fieldLabels(layer.file, layer.labelField!!)
                            if (labels.isEmpty()) shapes else shapes.map { it.copy(label = labels.getOrNull(it.recordIndex).orEmpty()) }
                        } else shapes
                        runOnUiThread {
                            if (!mapStarted || seq != loadSeq) return@runOnUiThread
                            val cur = mapView.layers.firstOrNull { it.name == layer.name } ?: return@runOnUiThread
                            cur.shapes = labelled
                            mapView.invalidate()
                            if (seq == initialLoadSeq) {
                                initialLoadDone++
                                mapView.loadProgress = "Loading layers… $initialLoadDone/$initialLoadTotal"
                                if (initialLoadDone >= initialLoadTotal) {
                                    initialLoadSeq = -1L
                                    mapView.loadProgress = ""
                                    Toast.makeText(this, "Map ready (${initialLoadTotal} layers)", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    } catch (e: OutOfMemoryError) {
                        android.util.Log.e("Load", "OOM loading ${layer.file}, skipped", e)
                        runOnUiThread {
                            if (seq == initialLoadSeq) {
                                initialLoadDone++
                                if (initialLoadDone >= initialLoadTotal) {
                                    initialLoadSeq = -1L
                                    Toast.makeText(
                                        this,
                                        "Map ready (${initialLoadTotal} layers, ${layer.name} too large)",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    this,
                                    "${layer.name} too large to load",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("Load", "failed loading ${layer.file}", e)
                    }
                }
            }
        }
    }

    // ---- editing ---------------------------------------------------------------

    private fun editableCrs(): Utm.Crs =
        mapView.layers.firstOrNull { it.name.equals(editableName, true) }?.crs
            ?: Utm.Crs.UTM_METERS

    /**
     * DBF label field for an editable file: POINTS.shp always uses Name/15
     * (crew rule, fits "motel_2027"/"parking10june"); other layers keep their
     * existing first field (e.g. XGV_MP GCALL_ID/10) so values survive a save,
     * else NAME/15 for brand-new files.
     */
    private fun labelFieldSpec(shpFile: String?): Pair<String, Int> {
        if (shpFile != null && stem(shpFile).equals("points", true)) return "Name" to 15
        val dbName = shpFile?.let { listFileFor(it, ".dbf") }
        val db = dbName?.let { try { mapDir.readFile(it) } catch (e: Exception) { null } }
        val field = db?.let {
            try { ShpFormat.dbfHeader(it).fields.firstOrNull() } catch (e: Exception) { null }
        }
        if (field != null) return field.name to field.len
        return LABEL_FIELD to 15
    }

    private data class EditableData(val shapes: List<ShpFormat.Shape>, val labels: List<String>)

    /** Read the editable layer's shapes + dbf labels (I/O — bg-thread safe). */
    private fun loadEditableData(crs: Utm.Crs): EditableData {
        val file = shpFileFor(editableName)
        if (file == null) return EditableData(emptyList(), emptyList())
        val bytes = mapDir.readFile(file)
        val raw = try {
            ShpFormat.readShp(bytes ?: ByteArray(0), -1e18, -1e18, 1e18, 1e18)
        } catch (e: Exception) { emptyList() }
        // Geographic/conic editable layers edit in UTM metres on canvas.
        val shapes = projectShapes(raw, crs, file)
        val dbName = listFileFor(file, ".dbf")
        val db = if (dbName != null) mapDir.readFile(dbName) else null
        val (labelField, _) = labelFieldSpec(file)
        val labels = if (db != null) {
            try {
                val fields = ShpFormat.dbfHeader(db).fields
                val idx = fields.indexOfFirst { it.name.equals(labelField, ignoreCase = true) }
                if (idx < 0) emptyList()
                else ShpFormat.readDbfRows(db).map { it.values.getOrNull(idx) ?: "" }
            } catch (e: Exception) { emptyList() }
        } else emptyList()
        return EditableData(shapes, labels)
    }

    private fun applyEditableData(data: EditableData) {
        origEditable = data.shapes
        editableLabels = data.labels
        deletedIdx.clear(); addedPoints.clear(); undoStack.clear(); dirty = false
        reloadEditableView()
    }

    private fun loadEditable() {
        applyEditableData(loadEditableData(editableCrs()))
    }

    private fun editableDisplayShapes(): List<ShpFormat.Shape> {
        val out = ArrayList<ShpFormat.Shape>()
        for ((i, s) in origEditable.withIndex()) if (i !in deletedIdx) {
            val lbl = editableLabels.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "${editableName}_${i + 1}"
            out.add(s.copy(label = lbl))
        }
        var idx = origEditable.size
        for (p in addedPoints) {
            out.add(ShpFormat.Shape(idx++, ShpFormat.TYPE_POINT, listOf(listOf(doubleArrayOf(p.e, p.n))), p.e, p.n, p.e, p.n, p.name))
        }
        return out
    }

    private fun reloadEditableView() {
        mapView.layers.firstOrNull { it.name.equals(editableName, true) }?.let {
            it.shapes = editableDisplayShapes()
        }
        mapView.invalidate()
    }

    private fun currentEditPoints(): List<EditPoint> {
        val out = ArrayList<EditPoint>()
        for ((i, s) in origEditable.withIndex()) if (i !in deletedIdx) {
            val c = s.parts.firstOrNull()?.firstOrNull() ?: continue
            val lbl = editableLabels.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "${editableName}_${i + 1}"
            out.add(EditPoint(c[0], c[1], lbl))
        }
        out.addAll(addedPoints)
        return out
    }

    private fun nextAutoName(): String {
        val taken = currentEditPoints().map { it.name }.toSet()
        var n = taken.size + 1
        while ("PT%03d".format(n) in taken) n++
        return "PT%03d".format(n)
    }

    /** Map started AND layers fully loaded (background build finished). */
    private fun requireLayersReady(): Boolean {
        if (!mapStarted) { Toast.makeText(this, "Start_MAP first", Toast.LENGTH_SHORT).show(); return false }
        if (mapLoading) { Toast.makeText(this, "Map still loading layers…", Toast.LENGTH_SHORT).show(); return false }
        return true
    }

    private fun startEditing() {
        if (!requireLayersReady()) return
        if (mapView.layers.none { it.name.equals(editableName, true) }) {
            val msg = if (shpFileFor(editableName) == null)
                "$editableName.shp not found in the Layers folder"
            else
                "$editableName skipped (not UTM Zone 10N?) - see Layer settings"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        if (editing) { Toast.makeText(this, "Already editing", Toast.LENGTH_SHORT).show(); return }
        editing = true; addArm = false; selectArm = false
        mapView.editing = true
        Toast.makeText(this, "Editing $editableName", Toast.LENGTH_SHORT).show()
    }

    private fun saveEdits() {
        if (!editing) { Toast.makeText(this, "Start Editing first", Toast.LENGTH_SHORT).show(); return }
        commitEdits()
    }

    private fun commitEdits(quiet: Boolean = false) {
        val pts = currentEditPoints()
        // Non-UTM editable layers persist in native coords: the canvas edits
        // in UTM metres, convert back on write (working zone).
        val file = shpFileFor(editableName)
        val crs = editableCrs()
        val outPts = pts.map { p ->
            val c = unprojectPoint(p.e, p.n, crs, file)
            EditPoint(c[0], c[1], p.name)
        }
        val coords = outPts.map { doubleArrayOf(it.e, it.n) }
        val shpBytes = ShpFormat.buildPointShp(coords)
        val shxBytes = ShpFormat.buildPointShx(coords)
        val (labelField, labelLen) = labelFieldSpec(shpFileFor(editableName))
        val dbfBytes = ShpFormat.buildDbf(ByteArray(0), pts.map { it.name }, labelField, labelLen)
        var ok = writeMapFile("$editableName.shp", shpBytes)
        ok = writeMapFile("$editableName.shx", shxBytes) && ok
        ok = writeMapFile("$editableName.dbf", dbfBytes) && ok
        if (ok) {
            if (!prjAlreadyCorrect(editableName, UTM_MARKERS)) {
                // Sidecars take the .shp's own case (e.g. XGV_MP.prj, not
                // xgv_mp.prj). Android storage is case-insensitive so the two
                // land on the same file anyway, but matching the data file keeps
                // the folder readable on case-sensitive media (USB, desktop).
                val auxStem = shpFileFor(editableName)?.let { stem(it) } ?: editableName
                ShpAuxiliaryExporter.writeAuxFiles(mapAuxStorage(), auxStem)
            }
            loadEditable()
            dirty = false
            if (!quiet) Toast.makeText(this, "Saved ${pts.size} points to $editableName", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Save failed - check folder write permission", Toast.LENGTH_LONG).show()
        }
    }

    private val UTM_MARKERS = listOf("UTM_Zone_10N", "32610")

    /**
     * True when [base]'s .prj already exists and names one of [markers].
     * Rewriting the 4 aux files on every save cost seconds of SAF writes, and
     * the aux stem is written lowercase while the data files keep their own
     * case — so the lookup must be case-insensitive or it never matches.
     */
    private fun prjAlreadyCorrect(base: String, markers: List<String>): Boolean {
        val stem = base.substringBeforeLast('.')
        val prj = mapDir.listFiles().firstOrNull {
            it.substringBeforeLast('.').equals(stem, true) && it.endsWith(".prj", true)
        } ?: return false
        val text = mapDir.readFile(prj)?.toString(Charsets.US_ASCII) ?: return false
        return markers.any { text.contains(it, ignoreCase = true) }
    }

    /** Write through createFile so the first save works when POINTS.* is absent. */
    private fun writeMapFile(name: String, bytes: ByteArray): Boolean {
        val existing = mapDir.listFiles().firstOrNull { it.equals(name, true) }
        return if (existing != null) mapDir.overwriteFile(existing, bytes)
        else mapDir.createFile(name, bytes)
    }

    /** Adapter: aux files (.prj/.cpg/.shp.xml/.apl) land next to the shapefiles. */
    private fun mapAuxStorage(): org.gcap.core.GCapStorage = object : org.gcap.core.GCapStorage {
        override fun listDatNames() = emptyList<String>()
        override fun readBytes(name: String) = mapDir.readFile(name)
        override fun writeBytes(name: String, bytes: ByteArray) = writeMapFile(name, bytes)
        override fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray) = writeMapFile(name, bytes)
        override fun delete(name: String) {}
        override fun describe() = "GPS_MAP"
    }

    private fun listFileFor(shpName: String, ext: String): String? {
        val stem = shpName.substringBeforeLast('.')
        return mapDir.listFiles().firstOrNull { it.substringBeforeLast('.').equals(stem, true) && it.endsWith(ext, true) }
    }

    private fun stopEditing() {
        if (!editing) { Toast.makeText(this, "Not editing", Toast.LENGTH_SHORT).show(); return }
        promptSaveEdits { }
    }

    private fun stopEditingNow() {
        editing = false; addArm = false; selectArm = false
        mapView.editing = false
        Toast.makeText(this, "Editing stopped", Toast.LENGTH_SHORT).show()
    }

    private fun promptSaveEdits(after: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle("Save edits?")
            .setMessage("Stop editing the $editableName shapefile? Save the last edits, discard them, or cancel.")
            .setPositiveButton("Yes, save") { _, _ -> commitEdits(); stopEditingNow(); after() }
            .setNegativeButton("No, discard") { _, _ -> loadEditable(); stopEditingNow(); after() }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun armAddPoint() {
        if (!requireLayersReady()) return
        if (!editing) { Toast.makeText(this, "Start Editing first", Toast.LENGTH_SHORT).show(); return }
        if (refuseWhilePanning("add points")) return
        addArm = true; selectArm = false
        Toast.makeText(this, "Tap the map to place the point", Toast.LENGTH_LONG).show()
    }

    /**
     * Pan mode consumes every touch, so a tap can never reach onMapTap (see
     * MapCanvasView.onTouchEvent: the tap branch is `if (!panning)`). Arming
     * anyway produced a prompt the user could not satisfy: "Tap the map to
     * place the point", and tapping did nothing. The only ways out of pan mode
     * are "Center at your location" and a Go-to, both of which clear it, so
     * the message names that button rather than saying "turn pan off".
     */
    private fun refuseWhilePanning(what: String): Boolean {
        if (!mapView.panning) return false
        Toast.makeText(
            this,
            "Cannot $what while panning — tap 'Center at your location' first, then $what again",
            Toast.LENGTH_LONG,
        ).show()
        return true
    }

    private fun placePoint(e: Double, n: Double, defaultName: String? = null) {
        addArm = false
        val (fieldName, _) = labelFieldSpec(shpFileFor(editableName))
        val input = EditText(this).apply {
            setText(defaultName ?: nextAutoName())
            setSingleLine(true)
            textSize = 12f
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Add point")
            .setMessage("Name stored in $editableName.dbf ($fieldName)")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { nextAutoName() }
                val p = EditPoint(e, n, name)
                addedPoints.add(p)
                undoStack.addLast(UndoOp.Added(p))
                dirty = true
                reloadEditableView()
                Toast.makeText(this, "Point added: $name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun armSelect() {
        if (!requireLayersReady()) return
        if (refuseWhilePanning("select features")) return
        selectArm = true; addArm = false
        Toast.makeText(this, "Tap the feature to select it", Toast.LENGTH_LONG).show()
    }

    private fun selectAt(e: Double, n: Double): Pair<Int, ShpFormat.Shape>? {
        val (w, h) = mapView.windowMeters()
        val mppxE = w / mapView.width.toDouble()
        val mppxN = h / mapView.height.toDouble()
        var best: Pair<Int, ShpFormat.Shape>? = null
        var bestPx = 28.0
        for (li in mapView.layers.indices.reversed()) {
            val layer = mapView.layers[li]
            if (!layer.visible || li >= mapView.layers.size) continue
            for (s in layer.shapes) {
                val de = maxOf(s.xmin - e, 0.0, e - s.xmax)
                val dn = maxOf(s.ymin - n, 0.0, n - s.ymax)
                val dpx = kotlin.math.hypot(de / mppxE, dn / mppxN)
                if (dpx > bestPx) continue
                for (part in s.parts) for (pt in part) {
                    val px = kotlin.math.hypot((pt[0] - e) / mppxE, (pt[1] - n) / mppxN)
                    if (px < bestPx) { bestPx = px; best = li to s }
                }
            }
        }
        return best
    }

    private fun selectFeatureAt(e: Double, n: Double) {
        selectArm = false
        val hit = selectAt(e, n)
        if (hit == null) {
            Toast.makeText(this, "No feature nearby", Toast.LENGTH_SHORT).show()
            return
        }
        mapView.selected = hit
        mapView.invalidate()
        Toast.makeText(this, "Selected", Toast.LENGTH_SHORT).show()
    }

    private fun deletePoint() {
        val sel = mapView.selected
        if (sel == null) { Toast.makeText(this, "Select a feature first", Toast.LENGTH_SHORT).show(); return }
        if (!editing) { Toast.makeText(this, "Start Editing first", Toast.LENGTH_SHORT).show(); return }
        val (li, s) = sel
        val layer = mapView.layers.getOrNull(li) ?: return
        if (!layer.name.equals(editableName, true)) { Toast.makeText(this, "Only $editableName points can be deleted", Toast.LENGTH_LONG).show(); return }
        val idx = s.recordIndex
        if (idx < origEditable.size) {
            deletedIdx.add(idx)
            undoStack.addLast(UndoOp.DeletedOrig(idx))
        } else {
            val k = idx - origEditable.size
            if (k in addedPoints.indices) {
                undoStack.addLast(UndoOp.DeletedAdded(addedPoints[k]))
                addedPoints.removeAt(k)
            }
        }
        dirty = true
        mapView.selected = null
        reloadEditableView()
    }

    private fun unselect() {
        mapView.selected = null
        mapView.invalidate()
    }

    // ---- main-page functions (point_collector_ODS.ods rows 1-6) ------------

    /** EXIT: flush open edits, then leave the app. */
    private fun exitApp() {
        if (editing && dirty) {
            promptSaveEdits { finishAffinity() }
            return
        }
        finishAffinity()
    }

    /** Pan mode: a finger drag translates the map, north stays up. */
    private fun panMode() {
        if (!mapStarted) { Toast.makeText(this, "Start_MAP first", Toast.LENGTH_SHORT).show(); return }
        mapView.panning = true
        viewPinned = false
        Toast.makeText(this, "Pan on - drag the map (north stays up)", Toast.LENGTH_LONG).show()
        updateOverlays()
    }

    /** Leave pan mode and re-centre the map on the live GPS fix. */
    private fun centerAtGps() {
        if (!mapStarted) { Toast.makeText(this, "Start_MAP first", Toast.LENGTH_SHORT).show(); return }
        val loc = latestLoc
        if (loc == null) { Toast.makeText(this, "No GPS fix yet", Toast.LENGTH_SHORT).show(); return }
        mapView.panning = false
        viewPinned = false
        mapView.setGoToMarker(0.0, 0.0, "", false)
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, workingZone())
        mapView.centerE = e
        mapView.centerN = n
        mapView.setGpsMarker(e, n, (if (loc.hasAccuracy()) loc.accuracy else 0f).toDouble(), true)
        loadViewport(true)
        updateOverlays()
        Toast.makeText(this, "Centred on your location", Toast.LENGTH_SHORT).show()
    }

    /** Go to…: pick a row by the settings layer/column, centre the map on it. */
    private fun goToDialog() {
        if (!requireLayersReady()) return
        val file = shpFileFor(settings.goToLayer)
        if (file == null) {
            Toast.makeText(this, "${settings.goToLayer}.shp not found in GPS_MAP", Toast.LENGTH_LONG).show()
            return
        }
        val crs = Utm.classifyCrs(prjFor(file))
        if (crs == Utm.Crs.OTHER) {
            Toast.makeText(this, "Cannot project ${settings.goToLayer}.prj into the working zone", Toast.LENGTH_LONG).show()
            return
        }
        val bytes = try { mapDir.readFile(file) } catch (e: Exception) { null }
        if (bytes == null) { Toast.makeText(this, "Cannot read $file", Toast.LENGTH_LONG).show(); return }
        val raw = try { ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18) } catch (e: Exception) { emptyList() }
        if (raw.isEmpty()) { Toast.makeText(this, "No features in ${settings.goToLayer}", Toast.LENGTH_SHORT).show(); return }
        val shapes = projectShapes(raw, crs, file)
        val labels = fieldLabels(file, settings.goToColumn)
        val items = shapes.mapIndexed { i, _ ->
            val l = labels.getOrNull(i)?.trim().orEmpty()
            if (l.isEmpty()) "${settings.goToColumn} #${i + 1}" else l
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Go to… (${settings.goToLayer}.${settings.goToColumn})")
            .setItems(items) { _, i ->
                val c = shapes[i].parts.firstOrNull()?.firstOrNull() ?: return@setItems
                // Pan mode ends here: the view is pinned on the target instead
                // of following the fix (bearing/distance stay meaningful).
                mapView.panning = false
                viewPinned = true
                mapView.centerE = c[0]
                mapView.centerN = c[1]
                goToPoint = doubleArrayOf(c[0], c[1])
                goToLabel = items[i]
                mapView.setGoToMarker(c[0], c[1], items[i], true)
                loadViewport(true)
                updateOverlays()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- track recording (Record track / Stop recording track) -------------

    private fun startTrack() {
        if (!mapStarted) { Toast.makeText(this, "Start_MAP first", Toast.LENGTH_SHORT).show(); return }
        if (trackRecording) { Toast.makeText(this, "Already recording a track", Toast.LENGTH_SHORT).show(); return }
        if (latestLoc == null) { Toast.makeText(this, "No GPS fix yet - cannot start the track", Toast.LENGTH_SHORT).show(); return }
        trackRecording = true
        trackPts.clear()
        addTrackFix(force = true)
        val interval = ((settings.trackIntervalSeconds ?: 10).coerceAtLeast(1)) * 1000L
        val tick = object : Runnable {
            override fun run() {
                if (!trackRecording) return
                addTrackFix(force = false)
                uiHandler.postDelayed(this, interval)
            }
        }
        trackTick = tick
        uiHandler.postDelayed(tick, interval)
        Toast.makeText(this, "Recording track → ${settings.trackLayer}", Toast.LENGTH_LONG).show()
        updateOverlays()
    }

    /** One fix into the track, honouring the settings minimum-distance gate and
     *  the max-distance outlier rejection (QField-style: a jump beyond it is a
     *  GPS glitch, not a track segment). */
    private fun addTrackFix(force: Boolean) {
        val loc = latestLoc ?: return
        if (!loc.hasAccuracy() && !force) return
        val zone = workingZone()
        val (e, n) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, zone)
        if (!force && trackPts.isNotEmpty()) {
            val last = trackPts.last()
            val dist = kotlin.math.hypot(e - last[0], n - last[1])
            if (dist < settings.trackMinDistanceM) return
            if (dist > settings.trackMaxDistanceM) {
                android.util.Log.i("Track", "outlier rejected: ${dist.toInt()} m jump")
                return
            }
        }
        trackPts.add(doubleArrayOf(e, n))
    }

    private fun stopTrack(save: Boolean = true) {
        if (!trackRecording) {
            if (save) Toast.makeText(this, "Not recording a track", Toast.LENGTH_SHORT).show()
            return
        }
        trackRecording = false
        trackTick?.let { uiHandler.removeCallbacks(it) }
        trackTick = null
        if (!save) { trackPts.clear(); updateOverlays(); return }
        if (trackPts.size < 2) {
            Toast.makeText(this, "Track too short (${trackPts.size} point) - nothing saved", Toast.LENGTH_LONG).show()
            trackPts.clear()
            updateOverlays()
            return
        }
        // Snapshot on the UI thread (the GPS ticker owns trackPts), then write
        // on a worker so the 6 SAF operations can't block the main thread.
        val pts = trackPts.toList()
        trackPts.clear()
        updateOverlays()
        runWrite("Saving track…") { writeTrack(pts) }
    }

    /**
     * Run a shapefile write off the UI thread. A save is 5-8 SAF operations and
     * costs ~20 s on a slow phone; done inline it blocks the main thread long
     * enough for Android to ANR/low-memory-kill the process, which is what
     * made "Record track" appear to make the map die. [work] returns the toast
     * text.
     */
    private fun runWrite(label: String, work: () -> String) {
        if (writeBusy) {
            // Reject rather than interleave: two threads rewriting the same
            // .shp/.dbf would corrupt it. Say clearly that nothing was saved.
            Toast.makeText(this, "Still saving the previous change — nothing written, try again", Toast.LENGTH_LONG).show()
            return
        }
        writeBusy = true
        mapView.loadProgress = label
        mapView.invalidate()
        Thread {
            val msg = try { work() } catch (e: Throwable) {
                android.util.Log.e("Write", "write failed", e)
                "Save failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            runOnUiThread {
                writeBusy = false
                mapView.loadProgress = ""
                mapView.invalidate()
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                updateOverlays()
            }
        }.start()
    }

    /**
     * Append the recorded path to <trackLayer>.shp (one polyline part) + GPX.
     * Runs on a worker thread via [runWrite]; returns the toast text.
     */
    private fun writeTrack(pts: List<DoubleArray>): String {
        val layerName = settings.trackLayer
        val file = shpFileFor(layerName)
        // A track layer created here for the first time is written as WGS 84
        // degrees with a matching .prj, like POINTS. It previously defaulted to
        // UTM metres AND wrote no .prj at all, leaving the file with no CRS for
        // QGIS or ArcGIS to read. An existing track layer keeps its own CRS and
        // its .prj is never touched.
        val isNew = file == null
        val crs = if (isNew) Utm.Crs.WGS84_DEGREES else Utm.classifyCrs(prjFor(file))
        if (crs == Utm.Crs.OTHER) {
            return "Cannot append to $layerName.shp (unsupported .prj)"
        }
        val existing: List<ShpFormat.Shape> = try {
            file?.let { mapDir.readFile(it) }
                ?.let { ShpFormat.readShp(it, -1e18, -1e18, 1e18, 1e18) }
                ?: emptyList()
        } catch (e: Exception) { emptyList() }
        val parts = existing.flatMap { it.parts }.filter { it.size >= 2 }.toMutableList()
        val newPart: List<DoubleArray> = pts.map { p ->
            unprojectPoint(p[0], p[1], crs, file)
        }
        parts.add(newPart)
        var ok = writeMapFile("$layerName.shp", ShpFormat.buildPolylineShp(parts))
        ok = writeMapFile("$layerName.shx", ShpFormat.buildPolylineShx(parts)) && ok
        val names = parts.mapIndexed { i, _ -> "TRK%03d".format(i + 1) }
        ok = writeMapFile("$layerName.dbf", ShpFormat.buildDbf(ByteArray(0), names, "Name", 15)) && ok
        // A brand-new track layer gets its WGS 84 CRS sidecars, so the .prj
        // always matches the degrees just written. Never for an existing layer.
        var sidecarsOk = true
        if (ok && isNew) {
            sidecarsOk = ShpAuxiliaryExporter.writeCrsSidecars(mapAuxStorage(), layerName, ShpAuxiliaryExporter.PRJ_WGS84)
            android.util.Log.i("ForestNav", "track CRS sidecars written=$sidecarsOk")
        }
        // GPX is always WGS84; one file per recording session.
        val stamp = Utm.mobAutoName(System.currentTimeMillis())
        ok = writeMapFile("${layerName}_$stamp.gpx", gpxBytes("${layerName}_$stamp")) && ok
        return if (ok) {
            runOnUiThread { refreshLayerFromDisk(layerName) }
            "Track saved: ${pts.size} points → $layerName.shp + ${layerName}_$stamp.gpx" +
                if (sidecarsOk) "" else " (WARNING: $layerName.prj could not be written)"
        } else {
            "Track save failed - check folder write permission"
        }
    }

    /** GPX 1.1 track from the recorded fixes (working zone -> WGS84). */
    private fun gpxBytes(name: String): ByteArray {
        val zone = workingZone()
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"ForestPointCollector\" ")
            append("xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("  <trk><name>").append(name).append("</name><trkseg>\n")
            for (p in trackPts) {
                val (lat, lon) = Utm.utmToLatLon(p[0], p[1], zone)
                append("    <trkpt lat=\"").append(lat).append("\" lon=\"").append(lon).append("\"/>\n")
            }
            append("  </trkseg></trk>\n</gpx>\n")
        }.toByteArray(Charsets.UTF_8)
    }

    /** Re-read a shapefile from disk into its canvas layer (after a write). */
    private fun refreshLayerFromDisk(name: String) {
        val file = shpFileFor(name) ?: return
        val layer = mapView.layers.firstOrNull { it.name.equals(name, true) } ?: return
        val bytes = try { mapDir.readFile(file) } catch (e: Exception) { null } ?: return
        val shapes = try { ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18) } catch (e: Exception) { emptyList() }
        layer.shapes = projectShapes(shapes, layer.crs, file)
        loadViewport(true)
    }

    /** Editable layer: switch the Add_Point / Delete_Point target at runtime. */
    private fun editableLayerDialog() {
        if (!requireLayersReady()) return
        if (editing) { Toast.makeText(this, "Stop editing first", Toast.LENGTH_SHORT).show(); return }
        val points = mapView.layers.filter { it.kind == MapCanvasView.MapLayer.Kind.POINT }
        if (points.isEmpty()) { Toast.makeText(this, "No point layers loaded", Toast.LENGTH_SHORT).show(); return }
        val items = points.map {
            it.name + if (it.name.equals(editableName, true)) "  (current)" else ""
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Editable layer")
            .setItems(items) { _, i ->
                if (editing) { Toast.makeText(this, "Stop editing first", Toast.LENGTH_LONG).show(); return@setItems }
                editableName = points[i].name
                mapView.editableLayerName = editableName
                loadEditable()
                updateOverlays()
                Toast.makeText(this, "Editable layer: $editableName", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    /** Undo last edit: revert the newest add/delete in the edit buffer. */
    private fun undoLastEdit() {
        if (!editing) { Toast.makeText(this, "Start Editing first", Toast.LENGTH_SHORT).show(); return }
        if (undoStack.isEmpty()) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return }
        when (val op = undoStack.removeLast()) {
            is UndoOp.Added -> {
                val k = addedPoints.indexOfFirst { it == op.point }
                if (k >= 0) addedPoints.removeAt(k)
            }
            is UndoOp.DeletedOrig -> deletedIdx.remove(op.index)
            is UndoOp.DeletedAdded -> addedPoints.add(op.point)
        }
        dirty = true
        mapView.selected = null
        reloadEditableView()
        Toast.makeText(this, "Last edit undone", Toast.LENGTH_SHORT).show()
    }

    // ---- map overlays (the 17 texts of point_collector_ODS.ods) ------------

    /**
     * Zone readout with cross-zone banner: when the live fix sits in a
     * different UTM zone than the working zone, the crew is across the line
     * (positions still convert correctly — this is awareness, not an error).
     */
    private fun zoneLabel(zone: Int, loc: android.location.Location?): String {
        val fixZone = loc?.let { Utm.meridianZone(it.longitude) }
        return if (fixZone != null && fixZone != zone) "${zone}N (fix ${fixZone}N)"
        else "${zone}N"
    }

    private fun updateOverlays() {
        if (!::mapView.isInitialized || !mapStarted) return
        val loc = latestLoc
        val zone = workingZone()
        val now = Date()
        val (lat, lon) = Utm.utmToLatLon(mapView.centerE, mapView.centerN, zone)
        mapView.overlayLeft = listOf(
            "Mapsheet" to (NtsSheets.mapsheetFor(mapView.centerE, mapView.centerN, zone) ?: "-"),
            "Satellites" to (if (satCount >= 0) "$satCount" else "-"),
            "Precision" to (loc?.let { if (it.hasAccuracy()) "${it.accuracy.toInt()} m" else "-" } ?: "-"),
            "Date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now),
            "Time" to SimpleDateFormat("HH:mm:ss", Locale.US).format(now),
            "UTM Zone" to zoneLabel(zone, loc),
            "Easting" to Utm.fmt(mapView.centerE),
            "Northing" to Utm.fmt(mapView.centerN),
            "Elevation" to (loc?.let { if (it.hasAltitude()) "${it.altitude.toInt()} m" else "-" } ?: "-"),
            "Scale" to "1:${mapView.scaleDenominator}",
            "Latitude" to String.format(Locale.US, "%.5f", lat),
            "Longitude" to String.format(Locale.US, "%.5f", lon),
        )
        val right = mutableListOf("Go to:" to goToLabel.ifEmpty { "-" })
        val target = goToPoint
        if (target != null && loc != null) {
            val (fe, fn) = Utm.wgs84ToUtmZone(loc.latitude, loc.longitude, zone)
            val de = target[0] - fe
            val dn = target[1] - fn
            val dist = kotlin.math.hypot(de, dn)
            val bearing = (Math.toDegrees(kotlin.math.atan2(de, dn)) + 360.0) % 360.0
            right.add("Bearing" to "${bearing.toInt()}°")
            right.add("Distance" to if (dist >= 1000.0) String.format(Locale.US, "%.2f km", dist / 1000.0)
            else "${dist.toInt()} m")
        } else {
            right.add("Bearing" to "-")
            right.add("Distance" to "-")
        }
        mapView.overlayRight = right
        mapView.overlayProject = "Project: ${app.activeProject}"
        mapView.showNorthArrow = true
        mapView.invalidate()
    }

    /**
     * Remove layer (ODS: "Remove layer from map"): the layer stops drawing and
     * its ON/OFF box goes off in Layer settings — the same state, persisted to
     * project.json, so it stays hidden after Close_MAP/Start_MAP until turned
     * on there. Files are never touched; the editable layer is never offered.
     */
    private fun removeLayer() {
        if (!requireLayersReady()) return
        val names = mapView.layers
            .filter { !it.name.equals(editableName, true) && it.visible }
            .map { it.name }.toTypedArray()
        if (names.isEmpty()) { Toast.makeText(this, "No removable layers", Toast.LENGTH_SHORT).show(); return }
        AlertDialog.Builder(this)
            .setTitle("Remove layer")
            .setItems(names) { _, i ->
                if (editing) { Toast.makeText(this, "Stop editing first", Toast.LENGTH_LONG).show(); return@setItems }
                hideLayer(names[i])
            }
            .show()
    }

    private fun hideLayer(name: String) {
        mapView.layers.firstOrNull { it.name == name }?.visible = false
        mapView.invalidate()
        val ok = persistLayerOff(name)
        Toast.makeText(
            this,
            if (ok) "$name hidden (files kept — Layer settings shows OFF)"
            else "$name hidden for this map — project.json not updated",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /** Tick the layer's row OFF in the project model (creating the row if the
     *  file has none yet) and save it, so Layer settings and the map agree. */
    private fun persistLayerOff(name: String): Boolean = try {
        val specs = loadSpecs().toMutableList()
        // Match by normalized row name, not object identity: if project.json
        // ever holds the same row twice, update the first instead of adding
        // a third.
        val norm = name.trim().trimEnd('.').lowercase()
        val i = specs.indexOfFirst { it.name.trim().trimEnd('.').lowercase() == norm }
        if (i >= 0) {
            specs[i] = specs[i].copy(visible = false)
        } else {
            specs.add(LayerStyle.LayerSpec(name = name, visible = false))
        }
        app.writeProject(app.activeProject, LayerStyle.docWithLayers(app.projectText(), specs))
    } catch (e: Exception) {
        false
    }

    private fun pickLayer() {
        openLayers.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        })
    }

    private class Picked(val name: String, val bytes: ByteArray, val uri: Uri)

    private fun readPicked(uri: Uri): Picked? = try {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val name = (uri.lastPathSegment?.substringAfterLast('/') ?: "layer").substringBefore('?')
        Picked(name, bytes, uri)
    } catch (e: Exception) {
        null
    }

    /**
     * Add layer: copy every picked `.shp` and the sidecars travelling with it
     * (same stem in the selection, else next to the picked file where a grant
     * allows) into the Layers folder, so the layer outlives the session and
     * shows up in Layer settings like any other layer.
     */
    private fun importLayers(uris: List<Uri>) {
        val picked = uris.mapNotNull { readPicked(it) }
        if (picked.isEmpty()) {
            Toast.makeText(this, "Cannot read the selected file(s)", Toast.LENGTH_LONG).show()
            return
        }
        val shps = picked.filter { LayerImport.isShp(it.name) }
        if (shps.isEmpty()) {
            val msg = if (picked.any { LayerImport.isSidecar(it.name) })
                "Pick the .shp file (dbf/shx travel with it)"
            else "Not a shapefile (.shp): ${picked.first().name}"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            return
        }
        for (shp in shps) confirmImport(shp, picked)
    }

    private fun confirmImport(shp: Picked, picked: List<Picked>) {
        val name = LayerImport.stemOf(shp.name)
        val clash = mapDir.listFiles()
            .firstOrNull { LayerImport.stemOf(it).equals(name, true) && it.endsWith(".shp", true) }
        if (clash == null) { copyIntoFolder(shp, picked); return }
        // Already in the folder: identical bytes mean just show it, no copy.
        val existingBytes = try { mapDir.readFile(clash) } catch (e: Exception) { null }
        if (existingBytes != null && existingBytes.contentEquals(shp.bytes)) {
            Toast.makeText(this, "$name is already in the Layers folder", Toast.LENGTH_SHORT).show()
            if (mapStarted) showImportedLayer(name)
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add layer")
            .setMessage("$name is already in the Layers folder. Replace it with the picked file?")
            .setPositiveButton("Replace") { _, _ -> copyIntoFolder(shp, picked) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyIntoFolder(shp: Picked, picked: List<Picked>) {
        val existing = mapDir.listFiles()
        // Lowercased to the folder's one-stem-per-layer convention; an existing
        // file's own casing still wins, so re-adding never makes a case-twin.
        val shpTarget = existingName(existing, shp.name) ?: shp.name.lowercase()
        if (!mapDir.createFile(shpTarget, shp.bytes)) {
            Toast.makeText(
                this,
                "Cannot write ${shp.name} — is GPS_MAP present in the picked folder?",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val copied = mutableListOf(shpTarget)
        val missing = mutableListOf<String>()
        // .cpg is optional; losing .shx/.dbf/.prj is what deserves a warning.
        val essential = setOf("shx", "dbf", "prj")
        for (cands in LayerImport.sidecarCandidates(shp.name)) {
            val inSet = cands.firstNotNullOfOrNull { n -> picked.firstOrNull { it.name.equals(n, true) } }
            val bytes = inSet?.bytes ?: cands.firstNotNullOfOrNull { n -> readSibling(shp.uri, n) }
            val ext = cands.first().substringAfterLast('.')
            if (bytes == null) {
                if (ext in essential) missing += ext
                continue
            }
            val preferred = inSet?.name ?: cands.first()
            val target = existingName(existing, preferred) ?: preferred.lowercase()
            if (mapDir.createFile(target, bytes)) copied += target
            else missing += ext
        }
        // Toasts ellipsize past ~2 lines: keep both facts inside that budget.
        val message = if (missing.isEmpty()) {
            "Layer ${LayerImport.stemOf(shp.name)} copied to Layers folder (${copied.size} files)"
        } else {
            "Layer ${LayerImport.stemOf(shp.name)} copied (${copied.size} file) — missing " +
                "${missing.joinToString()}: labels/CRS limited"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        if (mapStarted) showImportedLayer(LayerImport.stemOf(shp.name))
    }

    /** Name already used in the folder for `preferred` (same stem + ext), else null. */
    private fun existingName(existing: List<String>, preferred: String): String? {
        val stem = LayerImport.stemOf(preferred)
        val ext = preferred.substringAfterLast('.', "")
        return existing.firstOrNull {
            LayerImport.stemOf(it).equals(stem, true) &&
                it.substringAfterLast('.', "").equals(ext, true)
        }
    }

    /**
     * Bytes of `name` sitting beside a picked file. A plain document URI is
     * not covered by the Layers-folder tree grant, so files under that tree
     * are read through `buildDocumentUriUsingTree` first, then the plain form.
     */
    private fun readSibling(picked: Uri, name: String): ByteArray? {
        val docId = LayerImport.siblingDocId(picked.toString(), name) ?: return null
        val authority = picked.authority ?: return null
        val candidates = ArrayList<Uri>(2)
        val tree = app.safTreeUri
        if (tree != null && tree.authority == authority) {
            val treeId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { null }
            if (treeId != null && (docId == treeId || docId.startsWith("$treeId/"))) {
                candidates.add(DocumentsContract.buildDocumentUriUsingTree(tree, docId))
            }
        }
        candidates.add(DocumentsContract.buildDocumentUri(authority, docId))
        for (uri in candidates) {
            try {
                contentResolver.openInputStream(uri)?.use { return it.readBytes() }
            } catch (e: Exception) {
                // try the next URI form
            }
        }
        return null
    }

    /** Draw a layer that was just copied into the Layers folder. */
    private fun showImportedLayer(name: String) {
        invalidateFirstZone()
        if (mapView.layers.any { it.name.equals(name, true) }) {
            refreshLayerFromDisk(name)
            return
        }
        val f = shpFileFor(name) ?: return
        val bytes = try { mapDir.readFile(f) } catch (e: Exception) { null } ?: return
        val crs = Utm.classifyCrs(prjFor(f))
        if (crs == Utm.Crs.OTHER) {
            Toast.makeText(this, "Skipped $name (not UTM Zone 10N)", Toast.LENGTH_LONG).show()
            return
        }
        val kind = try {
            kindFor(ShpFormat.shpHeader(bytes)?.shapeType)
        } catch (e: Exception) { MapCanvasView.MapLayer.Kind.LINE }
        val spec = try {
            LayerStyle.match(listOf(f), loadSpecs()).ordered.firstOrNull()?.spec
        } catch (e: Exception) { null }
        val color = if (spec?.colorRaw?.isNotBlank() == true) LayerStyle.colorOf(spec.colorRaw)
        else LAYER_COLORS[mapView.layers.size % LAYER_COLORS.size]
        mapView.addLayer(MapCanvasView.MapLayer(LayerImport.stemOf(f), color, 2f, kind, false).apply {
            file = f
            this.crs = crs
            applySpec(this, spec, kind)
        })
        loadViewport(true)
    }
}
