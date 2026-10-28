package org.shppointcap.app.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import org.gcap.core.ShpFormat

/**
 * North-oriented UTM map canvas. The viewport always shows a fixed
 * ground rectangle whose extent doubles per zoom step:
 * scale 1:10000 -> 500m x 400m (default); 1:5000/2500 zoom in (halves),
 * 1:20000/40000 zoom out (doubles). Centred on the live GPS fix unless
 * [panning] is armed (finger drag translates the map, north stays up).
 *
 * Draws the 17 readout overlays of point_collector_ODS.ods over the map.
 */
class MapCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    class MapLayer(
        val name: String,
        val color: Int,
        val widthPx: Float,
        val kind: Kind,
        val editable: Boolean,
    ) {
        /** Working CRS; WGS84 layers are converted to UTM metres at load. */
        var crs: org.shppointcap.app.map.Utm.Crs = org.shppointcap.app.map.Utm.Crs.UTM_METERS
        enum class Kind { LINE, POINT, POLYGON }
        var file: String = name
        private var _visible: Boolean = true
        var visible: Boolean
            get() = _visible
            set(v) { if (_visible != v) { _visible = v; dirty = true } }
        private var _shapes: List<ShpFormat.Shape> = emptyList()
        var shapes: List<ShpFormat.Shape>
            get() = _shapes
            set(v) { _shapes = v; dirty = true }
        var showAsBusy: Boolean = false
        var labelField: String? = null
        var labelColor: Int = 0xFF1B1B1B.toInt()
        // Layer-settings styling (see LayerStyle): symbols, sizes in dp
        // (points: radius; lines/polygons: stroke width), label size in sp.
        var pointSymbol: PointSymbol = PointSymbol.CIRCLE
        var lineSymbol: LineSymbol = LineSymbol.SIMPLE
        var polySymbol: PolySymbol = PolySymbol.FILL
        var symbolSizeDp: Float = 2f
        var labelSizeSp: Float = 7f
        var borderColor: Int = color
        // Dirty-layer render cache (QField-style): only layers whose shapes,
        // style or viewport changed re-render; the rest blit their bitmap.
        var dirty: Boolean = true
        var cache: Bitmap? = null
        /** LRU stamp for [CACHE_BYTE_BUDGET] eviction. */
        var lastDrawn: Long = 0L
    }

    enum class PointSymbol { CIRCLE, TRIANGLE, SQUARE, DIAMOND }
    enum class LineSymbol { SIMPLE, DASH, DOT, DOUBLE_ROAD }
    enum class PolySymbol { FILL, HATCH_45, HATCH_NEG45, HATCH_V, HATCH_H, DOT, NOFILL }

    private val bgPaint = Paint().apply { color = 0xFFF7F5F0.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val polyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xFF444444.toInt(); strokeWidth = 2f
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF222222.toInt(); textSize = 26f }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF444444.toInt(); textSize = 18f }
    private val labelHalo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt(); textSize = 7f * resources.displayMetrics.scaledDensity
    }
    /** Per-frame allocations hoisted: onDraw runs at 1 Hz plus every fix and
     *  command, and allocating Paints/Paths there churns the GC on slow phones. */
    private val northStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt(); style = Paint.Style.STROKE
    }
    private val northFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1B1B1B.toInt() }
    private val northTri = Path()
    private val hlStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 6f
    }
    private val hlFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD700.toInt(); style = Paint.Style.FILL
    }
    private val markerPaint = Paint().apply { color = Color.BLUE }
    private val markerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLUE }
    private val gpsOvalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x2200CC00; style = Paint.Style.FILL
    }
    /** Go-to target marker (blue ring + label, distinct from the red fix cross). */
    private val goToPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val goToFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x220000FF; style = Paint.Style.FILL
    }
    var goToShow: Boolean = false
    var goToE: Double = 0.0
    var goToN: Double = 0.0
    var goToText: String = ""

    fun setGoToMarker(e: Double, n: Double, label: String, show: Boolean) {
        goToE = e; goToN = n; goToText = label; goToShow = show; invalidate()
    }
    /** Point symbols: circle (dot), triangle, square, diamond. Size = radius dp. */
    private fun drawPointSymbol(canvas: Canvas, x: Float, y: Float, layer: MapLayer) {
        val r = maxOf(2f, layer.symbolSizeDp * resources.displayMetrics.density)
        when (layer.pointSymbol) {
            PointSymbol.CIRCLE -> canvas.drawCircle(x, y, r, pointPaint)
            PointSymbol.SQUARE -> canvas.drawRect(x - r, y - r, x + r, y + r, pointPaint)
            PointSymbol.DIAMOND -> {
                val path = Path().apply {
                    moveTo(x, y - r); lineTo(x + r, y); lineTo(x, y + r); lineTo(x - r, y); close()
                }
                canvas.drawPath(path, pointPaint)
            }
            PointSymbol.TRIANGLE -> {
                val path = Path().apply {
                    moveTo(x, y - r); lineTo(x + r, y + r); lineTo(x - r, y + r); close()
                }
                canvas.drawPath(path, pointPaint)
            }
        }
    }
    private val shaderCache = mutableMapOf<String, Shader>()

    /** Repeating hatch/dot tile shader, cached per (pattern, color, width). */
    private fun hatchShader(pattern: PolySymbol, color: Int, widthPx: Float): Shader {
        val d = resources.displayMetrics.density
        val key = "$pattern|$color|$widthPx|$d"
        return shaderCache.getOrPut(key) {
            val s = (8 * d).toInt().coerceAtLeast(8)
            val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color
                style = Paint.Style.STROKE
                strokeWidth = maxOf(1f, widthPx)
                strokeCap = Paint.Cap.ROUND
            }
            val f = s.toFloat()
            when (pattern) {
                PolySymbol.HATCH_45 -> c.drawLine(0f, f, f, 0f, p)
                PolySymbol.HATCH_NEG45 -> c.drawLine(0f, 0f, f, f, p)
                PolySymbol.HATCH_V -> c.drawLine(f / 2f, 0f, f / 2f, f, p)
                PolySymbol.HATCH_H -> c.drawLine(0f, f / 2f, f, f / 2f, p)
                PolySymbol.DOT -> {
                    p.style = Paint.Style.FILL
                    c.drawCircle(f / 2f, f / 2f, maxOf(1.5f, widthPx), p)
                }
                else -> { }
            }
            BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
    }
    private val gpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 4f }
    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }

    var started: Boolean = false
        set(v) { field = v; invalidate() }
    var editing: Boolean = false
    var editableLayerName: String? = null
        set(v) { field = v; invalidate() }

    var centerE: Double = 0.0
    var centerN: Double = 0.0
    var scaleDenominator: Int = 10000
        set(v) { field = v; invalidate() }

    var gpsE: Double = 0.0
    var gpsN: Double = 0.0
    var gpsAcc: Double = 0.0
    var haveGps: Boolean = false

    private val pMarkers = mutableListOf<Triple<String, Double, Double>>()
    var showP1P2P3: Boolean = true

    var selected: Pair<Int, ShpFormat.Shape>? = null

    /**
     * Map readouts drawn over the canvas (point_collector_ODS.ods rows 7-18).
     * [overlayLeft] runs down the left edge, [overlayRight] down the right edge
     * under [overlayProject]; both are label/value pairs.
     */
    var overlayLeft: List<Pair<String, String>> = emptyList()
    var overlayRight: List<Pair<String, String>> = emptyList()
    var overlayProject: String = ""
    var showNorthArrow: Boolean = true

    /** Pan mode (Pan button): finger drag translates the map, north stays up. */
    var panning: Boolean = false

    /** "Loading layers… 7/23" during the initial Start_MAP batch, else "". */
    var loadProgress: String = ""

    /** Fired after each pan step so the host can reload the viewport. */
    var onPan: (() -> Unit)? = null
        set(v) { field = v; invalidate() }

    var onMapTap: ((e: Double, n: Double) -> Unit)? = null

    val layers = mutableListOf<MapLayer>()

    /** Layers with at least this many shapes get a cached bitmap (bounds memory). */
    private val CACHE_MIN_SHAPES = 30

    /**
     * Global cap on cached layer bitmaps. A full-screen ARGB_8888 layer is
     * ~5 MB at 1080x1200, so an unbounded per-layer cache OOM-kills a 3 GB
     * phone mid-pan (the process dies and the map comes back "not started").
     * Over budget, the least-recently-drawn caches are recycled; they simply
     * re-render on the next frame.
     */
    private val CACHE_BYTE_BUDGET = 40L * 1024 * 1024
    private var drawTick = 0L

    private fun cachedBytes(): Long =
        layers.sumOf { (it.cache?.allocationByteCount ?: 0).toLong() }

    private fun recycleLayer(l: MapLayer) {
        l.cache?.recycle()
        l.cache = null
    }

    /** Free cached bitmaps until the budget is met; never touches [keep]. */
    private fun trimCaches(keep: MapLayer? = null) {
        var total = cachedBytes()
        if (total <= CACHE_BYTE_BUDGET) return
        for (l in layers.sortedBy { it.lastDrawn }) {
            if (total <= CACHE_BYTE_BUDGET) break
            if (l === keep) continue
            val b = l.cache ?: continue
            total -= b.allocationByteCount.toLong()
            recycleLayer(l)
        }
    }

    /** Release every cached bitmap (Close_MAP, or leaving the map). */
    fun releaseCaches() {
        for (l in layers) recycleLayer(l)
    }

    private var vpE = Double.NaN
    private var vpN = Double.NaN
    private var vpScale = 0

    fun addLayer(layer: MapLayer) { layers.add(layer); invalidate() }
    fun removeLayer(name: String) {
        val doomed = layers.filter { it.name == name }
        layers.removeAll(doomed)
        for (l in doomed) recycleLayer(l)
        invalidate()
    }
    fun setP1P2P3(markers: List<Triple<String, Double, Double>>) { pMarkers.clear(); pMarkers.addAll(markers); invalidate() }
    fun setGpsMarker(e: Double, n: Double, acc: Double, have: Boolean) {
        gpsE = e; gpsN = n; gpsAcc = acc; haveGps = have; invalidate()
    }

    fun windowMeters(): Pair<Double, Double> {
        val k = scaleDenominator / 10000.0
        return (500.0 * k) to (400.0 * k)
    }

    private fun worldToScreen(e: Double, n: Double, out: FloatArray) {
        val (w, h) = windowMeters()
        val mppxE = w / width
        val mppxN = h / height
        out[0] = (width / 2f) + ((e - centerE) / mppxE).toFloat()
        out[1] = (height / 2f) - ((n - centerN) / mppxN).toFloat()
    }

    fun screenToWorld(x: Float, y: Float): DoubleArray {
        val (w, h) = windowMeters()
        val e = centerE + ((x - width / 2f) * (w / width))
        val n = centerN - ((y - height / 2f) * (h / height))
        return doubleArrayOf(e, n)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, bgPaint)
        if (!started) {
            canvas.drawText("  GPS_MAP  (Start_MAP)", 16f, h / 2 - 30f, textPaint)
            canvas.drawText("  Press Start_MAP to load the layers", 16f, h / 2 + 20f, smallText)
            return
        }

        // north-oriented frame
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, framePaint)

        // Shared screen-point scratch for the overlay sections below.
        val p1 = FloatArray(2)

        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        // View rect in ground metres (+10 m for stroke widths); shapes fully
        // outside are skipped — at 1:10000 the viewport holds a fraction of a
        // big layer, and pathing everything every second saturates a slow phone.
        val (we, hn) = windowMeters()
        val vx0 = centerE - we / 2 - 10.0; val vx1 = centerE + we / 2 + 10.0
        val vy0 = centerN - hn / 2 - 10.0; val vy1 = centerN + hn / 2 + 10.0
        // QField-style dirty-layer cache: only layers whose shapes, style or
        // viewport changed re-render; the rest blit their bitmap. A viewport
        // change invalidates all. Small layers (< CACHE_MIN_SHAPES) skip the
        // cache (cheap to redraw) to bound memory on 3 GB phones.
        if (centerE != vpE || centerN != vpN || scaleDenominator != vpScale) {
            vpE = centerE; vpN = centerN; vpScale = scaleDenominator
            for (l in layers) l.dirty = true
        }
        for (layer in layers) {
            if (!layer.visible) continue
            val bmp = layer.cache
            val big = layer.shapes.size >= CACHE_MIN_SHAPES
            layer.lastDrawn = ++drawTick
            if (!big || layer.dirty || bmp == null || bmp.width != width || bmp.height != height) {
                if (big) {
                    // Free room before allocating a new full-screen bitmap.
                    trimCaches(keep = layer)
                    val b = if (bmp != null && bmp.width == width && bmp.height == height) bmp
                    else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    val bc = Canvas(b)
                    // Clear the cache bitmap before re-rendering: otherwise a
                    // pan/zoom draws the new shapes over the old ones and they
                    // trail across the screen. Only ever on the cache bitmap —
                    // clearing the real canvas would erase the whole map.
                    bc.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
                    renderLayer(bc, layer)
                    layer.cache = b
                    layer.dirty = false
                    canvas.drawBitmap(b, 0f, 0f, null)
                } else {
                    renderLayer(canvas, layer)
                }
            } else {
                canvas.drawBitmap(bmp, 0f, 0f, null)
            }
        }

        // selected feature highlight
        selected?.let { (li, s) ->
            if (li in layers.indices) {
                for (part in s.parts) for (pt in part) {
                    worldToScreen(pt[0], pt[1], p1)
                    canvas.drawCircle(p1[0], p1[1], 8f, hlFill)
                    canvas.drawCircle(p1[0], p1[1], 8f, hlStroke)
                }
            }
        }

        // P1/P2/P3 markers
        if (showP1P2P3) {
            for ((label, e, n) in pMarkers) {
                worldToScreen(e, n, p1)
                canvas.drawCircle(p1[0], p1[1], 10f, markerPaint)
                canvas.drawText(label, p1[0] + 14f, p1[1] - 14f, markerText)
            }
        }

        // GPS marker + accuracy
        if (haveGps) {
            worldToScreen(gpsE, gpsN, p1)
            if (gpsAcc > 0) {
                val (we, hn) = windowMeters()
                val rE = ((gpsAcc) / (we / width)).toFloat()
                val rN = ((gpsAcc) / (hn / height)).toFloat()
                canvas.drawOval(RectF(p1[0] - rE, p1[1] - rN, p1[0] + rE, p1[1] + rN), gpsOvalPaint)
            }
            canvas.drawLine(p1[0] - 16f, p1[1], p1[0] + 16f, p1[1], gpsPaint)
            canvas.drawLine(p1[0], p1[1] - 16f, p1[0], p1[1] + 16f, gpsPaint)
        }

        // Go-to target marker
        if (goToShow) {
            worldToScreen(goToE, goToN, p1)
            canvas.drawCircle(p1[0], p1[1], 12f, goToFill)
            canvas.drawCircle(p1[0], p1[1], 12f, goToPaint)
            if (goToText.isNotEmpty()) {
                canvas.drawText(goToText, p1[0] + 16f, p1[1] - 12f, labelHalo)
                canvas.drawText(goToText, p1[0] + 16f, p1[1] - 12f, labelPaint)
            }
        }

        drawOverlays(canvas)
    }

    /**
     * Render one layer's shapes onto [c] — draw-time simplification (sub-pixel
     * vertices dropped) and label gating (on-screen only). Called for dirty
     * layers; clean cached layers blit their bitmap instead.
     */
    private fun renderLayer(c: Canvas, layer: MapLayer) {
        val w = width.toFloat(); val h = height.toFloat()
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        val (we, hn) = windowMeters()
        val vx0 = centerE - we / 2 - 10.0; val vx1 = centerE + we / 2 + 10.0
        val vy0 = centerN - hn / 2 - 10.0; val vy1 = centerN + hn / 2 + 10.0
        val dashFx = DashPathEffect(floatArrayOf(8 * d, 6 * d), 0f)
        val dotFx = DashPathEffect(floatArrayOf(1f, 7 * d), 0f)
        val path = Path()
        val p1 = FloatArray(2); val p2 = FloatArray(2)
        val simplifyPx = 1.0f * d
        val strokePx = maxOf(1f, layer.symbolSizeDp * d)
        linePaint.color = layer.borderColor
        linePaint.strokeWidth = strokePx
        linePaint.pathEffect = when (layer.lineSymbol) {
            LineSymbol.DASH -> dashFx
            LineSymbol.DOT -> dotFx
            else -> null
        }
        linePaint.strokeCap = Paint.Cap.ROUND
        pointPaint.color = layer.color
        labelPaint.color = layer.labelColor
        labelPaint.textSize = layer.labelSizeSp * sp
        for (s in layer.shapes) {
            if (s.xmax < vx0 || s.xmin > vx1 || s.ymax < vy0 || s.ymin > vy1) continue
            for (part in s.parts) {
                path.rewind()
                var first = true
                var prevX = 0f; var prevY = 0f
                for (i in part.indices) {
                    worldToScreen(part[i][0], part[i][1], if (first) p1 else p2)
                    val px = if (first) p1[0] else p2[0]
                    val py = if (first) p1[1] else p2[1]
                    if (first) {
                        path.moveTo(px, py)
                        prevX = px; prevY = py
                        first = false
                    } else {
                        val dx = px - prevX; val dy = py - prevY
                        if (dx * dx + dy * dy >= simplifyPx * simplifyPx) {
                            path.lineTo(px, py)
                            prevX = px; prevY = py
                        }
                    }
                }
                if (layer.kind == MapLayer.Kind.LINE) {
                    if (layer.lineSymbol == LineSymbol.DOUBLE_ROAD) {
                        linePaint.color = layer.borderColor
                        linePaint.strokeWidth = strokePx + 2.5f * d
                        linePaint.pathEffect = null
                        c.drawPath(path, linePaint)
                        linePaint.color = 0xFFF7F5F0.toInt()
                        linePaint.strokeWidth = strokePx
                        c.drawPath(path, linePaint)
                    } else {
                        c.drawPath(path, linePaint)
                    }
                } else if (layer.kind == MapLayer.Kind.POLYGON) {
                    path.close()
                    when (layer.polySymbol) {
                        PolySymbol.NOFILL -> { /* border only below */ }
                        PolySymbol.FILL -> {
                            polyFillPaint.shader = null
                            polyFillPaint.color = (layer.color and 0x00FFFFFF.toInt()) or 0x80000000.toInt()
                            c.drawPath(path, polyFillPaint)
                        }
                        else -> {
                            polyFillPaint.shader = hatchShader(layer.polySymbol, layer.color, strokePx)
                            c.drawPath(path, polyFillPaint)
                            polyFillPaint.shader = null
                        }
                    }
                    linePaint.color = layer.borderColor
                    linePaint.strokeWidth = strokePx
                    linePaint.pathEffect = null
                    c.drawPath(path, linePaint)
                } else {
                    if (part.isNotEmpty()) {
                        worldToScreen(part[0][0], part[0][1], p1)
                        drawPointSymbol(c, p1[0], p1[1], layer)
                        if (s.label.isNotEmpty() && p1[0] in 0f..w && p1[1] in 0f..h) {
                            labelPaint.color = layer.labelColor
                            c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelHalo)
                            c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelPaint)
                        }
                    }
                }
            }
            if (layer.kind == MapLayer.Kind.POLYGON && s.label.isNotEmpty()) {
                worldToScreen((s.xmin + s.xmax) / 2.0, (s.ymin + s.ymax) / 2.0, p1)
                if (p1[0] in 0f..w && p1[1] in 0f..h) {
                    labelPaint.color = layer.labelColor
                    c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelHalo)
                    c.drawText(s.label, p1[0] + 7f, p1[1] - 9f, labelPaint)
                }
            }
        }
    }

    /** Right-aligned twin of [smallText] for the right-hand readout column. */
    private val rightText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1B1B1B.toInt()
        textAlign = Paint.Align.RIGHT
    }

    /** Label/value columns + north arrow (the 17 map texts of the spec sheet). */
    private fun drawOverlays(canvas: Canvas) {
        if (!started) return
        val d = resources.displayMetrics.density
        val sp = resources.displayMetrics.scaledDensity
        smallText.textSize = 11f * sp
        smallText.color = 0xFF1B1B1B.toInt()
        rightText.textSize = 11f * sp
        rightText.color = 0xFF1B1B1B.toInt()
        val pad = 6f * d
        val lineH = smallText.textSize * 1.3f
        val w = width.toFloat()

        // Load progress banner, centred near the top — a 60 s load with no
        // feedback looks like a hang.
        if (loadProgress.isNotEmpty()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 15f * sp; color = 0xFF1B1B1B.toInt()
                textAlign = Paint.Align.CENTER
            }
            val tw = tp.measureText(loadProgress)
            val th = 22f * d
            val bx = (w - tw) / 2f - 8f * d
            val by = 26f * d
            canvas.drawRoundRect(bx, by, bx + tw + 16f * d, by + th, 6f * d, 6f * d, barFill)
            canvas.drawText(loadProgress, w / 2f, by + th * 0.72f, tp)
        }

        var y = pad + smallText.textSize
        for ((k, v) in overlayLeft) {
            canvas.drawText("$k  $v", pad, y, smallText)
            y += lineH
        }

        var yr = pad + smallText.textSize
        if (overlayProject.isNotEmpty()) {
            canvas.drawText(overlayProject, w - pad, yr, rightText)
            yr += lineH
        }
        if (showNorthArrow) {
            drawNorthArrow(canvas, w - pad - 12f * d, yr + 14f * d, 11f * d)
            // Keep Go to / Bearing / Distance clear of the arrow graphic.
            yr += 34f * d + 2 * lineH
        }
        for ((k, v) in overlayRight) {
            canvas.drawText("$k  $v", w - pad, yr, rightText)
            yr += lineH
        }

        // Editing state only while an edit session is open (not one of the 17).
        if (editing) {
            smallText.color = 0xFF1B6B1B.toInt()
            canvas.drawText("EDIT ${editableLayerName ?: "none"}", pad, height - 10f * d, smallText)
            smallText.color = 0xFF1B1B1B.toInt()
        }
    }

    /** North stays up: a small arrow with the letter N, under the project name. */
    private fun drawNorthArrow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        northStroke.strokeWidth = 2f * resources.displayMetrics.density
        rightText.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - r - 6f, rightText)
        rightText.textAlign = Paint.Align.RIGHT
        northTri.rewind()
        northTri.moveTo(cx, cy - r)
        northTri.lineTo(cx - r * 0.55f, cy + r * 0.35f)
        northTri.lineTo(cx + r * 0.55f, cy + r * 0.35f)
        northTri.close()
        canvas.drawPath(northTri, northFill)
        canvas.drawLine(cx, cy + r * 0.45f, cx, cy + r * 1.5f, northStroke)
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f

    /**
     * Tap (quick touch, no pan mode) selects/adds; in [panning] mode a drag
     * translates the map and never taps. Follow mode ignores drags — the map
     * stays centred on the fix / Go-to point until Pan is armed.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!started) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                lastX = event.x; lastY = event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!panning) return true
                val (we, hn) = windowMeters()
                centerE -= (event.x - lastX) * (we / width)
                centerN += (event.y - lastY) * (hn / height)
                lastX = event.x; lastY = event.y
                invalidate()
                onPan?.invoke()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!panning) {
                    val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
                    if (kotlin.math.hypot(event.x - downX, event.y - downY) <= slop) {
                        val w = screenToWorld(event.x, event.y)
                        onMapTap?.invoke(w[0], w[1])
                    }
                }
                return true
            }
        }
        return true
    }
}