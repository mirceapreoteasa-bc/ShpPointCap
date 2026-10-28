package org.shppointcap.app

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.gcap.core.ShpFormat
import org.shppointcap.app.map.FileMapDir
import org.shppointcap.app.map.LayerStyle
import org.shppointcap.app.map.MapCanvasView
import org.shppointcap.app.map.MapFileDir
import org.shppointcap.app.map.SafMapDir
import java.io.File

/**
 * Layer settings page: edits the same project model the map runs on (see
 * layer_settings.ods). Lists every .shp found in GPS_MAP in display order
 * (sheet order top->bottom, unmatched files last), each with ON/OFF, editable
 * flag, symbol/color/size pickers, label field + size, and Move up/down.
 * Save writes data-folder project.json (no rebuild to retune); the map picks
 * it up on the next Start_MAP.
 */
class LayerSettingsActivity : AppCompatActivity() {

    private data class Row(
        val file: String,
        val specName: String,
        val kind: MapCanvasView.MapLayer.Kind,
        var visible: Boolean,
        var editable: Boolean,
        var symbol: String,
        var color: String,
        var borderOnly: Boolean,
        var size: String,
        var label: String,
        var labelSize: String,
    )

    private lateinit var app: CollectorApp
    private lateinit var mapDir: MapFileDir
    private lateinit var scroller: ScrollView
    private lateinit var list: LinearLayout
    private var rows = mutableListOf<Row>()

    private val pointSymbols = listOf("circle", "triangle", "square", "diamond")
    private val lineSymbols = listOf("single line", "dash", "dotted line", "double line topo road")
    private val polySymbols = listOf(
        "simple fill", "Hashed /", "Hashed \\", "hashed vertically",
        "hashed horizontally", "pattern dot", "no fill",
    )
    private val colors = listOf(
        "orange", "blue", "green", "violet", "red",
        "brown", "yellow", "black", "pink", "white", "gray",
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = applicationContext as CollectorApp
        mapDir = app.safTreeUri?.let { SafMapDir(this, it) }
            ?: FileMapDir(File(cacheDir, "no-folder-picked"))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 12, 12, 12)
        }
        root.addView(TextView(this).apply {
            text = "Layers Settings"
            textSize = 22f
        })
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(commandButton("Save").apply { setOnClickListener { saveAndClose() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(actionButton("Cancel").apply { setOnClickListener { finish() } },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)

        // No column header: the controls sit on three rows per layer, with the checkboxes
        // on the first, so a row of column names lined up with nothing and read as a
        // table that was not one. Every control below carries its own caption instead.

        scroller = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(list)
        root.addView(
            scroller,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            ),
        )
        setContentView(root)

        loadRows()
        rebuild()
    }

    private fun stem(name: String): String = name.substringBeforeLast('.')

    private fun kindOf(file: String): MapCanvasView.MapLayer.Kind {
        val bytes = try { mapDir.readHeader(file, 100) } catch (e: Exception) { null }
        return try {
            LayerStyle.kindFor(bytes?.let { ShpFormat.shpHeader(it)?.shapeType })
        } catch (e: Exception) { MapCanvasView.MapLayer.Kind.LINE }
    }

    private fun canonicalSymbol(kind: MapCanvasView.MapLayer.Kind, raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) {
            return when (kind) {
                MapCanvasView.MapLayer.Kind.POINT -> "circle"
                MapCanvasView.MapLayer.Kind.LINE -> "single line"
                MapCanvasView.MapLayer.Kind.POLYGON -> "simple fill"
            }
        }
        return when (kind) {
            MapCanvasView.MapLayer.Kind.POINT -> when (LayerStyle.pointSymbolOf(t)) {
                MapCanvasView.PointSymbol.TRIANGLE -> "triangle"
                MapCanvasView.PointSymbol.SQUARE -> "square"
                MapCanvasView.PointSymbol.DIAMOND -> "diamond"
                else -> "circle"
            }
            MapCanvasView.MapLayer.Kind.LINE -> when (LayerStyle.lineSymbolOf(t)) {
                MapCanvasView.LineSymbol.DASH -> "dash"
                MapCanvasView.LineSymbol.DOT -> "dotted line"
                MapCanvasView.LineSymbol.DOUBLE_ROAD -> "double line topo road"
                else -> "single line"
            }
            MapCanvasView.MapLayer.Kind.POLYGON -> when (LayerStyle.polySymbolOf(t)) {
                MapCanvasView.PolySymbol.HATCH_45 -> "Hashed /"
                MapCanvasView.PolySymbol.HATCH_NEG45 -> "Hashed \\"
                MapCanvasView.PolySymbol.HATCH_V -> "hashed vertically"
                MapCanvasView.PolySymbol.HATCH_H -> "hashed horizontally"
                MapCanvasView.PolySymbol.DOT -> "pattern dot"
                MapCanvasView.PolySymbol.NOFILL -> "no fill"
                else -> "simple fill"
            }
        }
    }

    private fun projectDocText(): String? =
        app.projectText() ?: try {
            assets.open("mirshp.json").readBytes().toString(Charsets.UTF_8)
        } catch (e: Exception) { null }

    private fun loadRows() {
        val specs = try {
            LayerStyle.parseProject(projectDocText() ?: "{\"layers\":[]}")
        } catch (e: Exception) { emptyList() }
        val files = mapDir.listFiles().filter { it.endsWith(".shp", true) }
        rows = LayerStyle.match(files, specs).ordered.map { entry ->
            val kind = kindOf(entry.file)
            val spec = entry.spec
            val colorRaw = spec?.colorRaw.orEmpty()
            Row(
                file = entry.file,
                specName = spec?.name ?: stem(entry.file),
                kind = kind,
                visible = spec?.visible ?: true,
                editable = spec?.editable ?: false,
                symbol = canonicalSymbol(kind, spec?.symbolRaw.orEmpty()),
                color = colorRaw.removeSuffix("border").trim().ifEmpty { "gray" }.lowercase()
                    .takeIf { it in colors } ?: "gray",
                borderOnly = colorRaw.trim().lowercase().endsWith("border"),
                size = spec?.sizeDp?.toString().orEmpty(),
                label = spec?.labelField.orEmpty(),
                labelSize = spec?.labelSizeSp?.toString().orEmpty(),
            )
        }.toMutableList()
    }

    private fun rebuild() {
        val y = scroller.scrollY
        list.removeAllViews()
        rows.forEachIndexed { idx, row -> list.addView(rowView(idx, row)) }
        scroller.post { scroller.scrollTo(0, y) }
    }

    /** A captioned control on its own row, with the caption always visible. */
    private fun captionRow(caption: String, control: android.view.View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@LayerSettingsActivity).apply {
                text = caption
                textSize = 13f
                setTextColor(0xFF333333.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    (118 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT,
                )
            })
            addView(control, LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f,
            ))
        }

    /**
     * Tappable colour swatches for one layer.
     *
     * A dropdown said "orange" or "brown", which is no guide at all to how a layer will
     * look drawn over the basemap. The chip shows the colour itself. Tapping the colour
     * already set clears it, returning the layer to the app's default.
     */
    private fun colourSwatches(row: Row): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val label = TextView(this).apply {
            text = if (row.color.isBlank()) "default" else row.color
            textSize = 11f
            setTextColor(0xFF4A4A4A.toInt())
        }
        val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val size = (24 * resources.displayMetrics.density).toInt().coerceAtLeast(24)
        fun paint(name: String): android.graphics.drawable.Drawable =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(LayerStyle.colorOf(name))
                setStroke(
                    if (name.equals(row.color, true)) 4 else 1,
                    if (name.equals(row.color, true)) 0xFF444444.toInt() else 0xFFBBBBBB.toInt(),
                )
            }
        for (name in colors) {
            val b = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                background = paint(name)
                contentDescription = name
                setOnClickListener {
                    row.color = if (name.equals(row.color, true)) "" else name
                    for (i in 0 until strip.childCount) {
                        val v = strip.getChildAt(i)
                        val nm = colors.getOrNull(i) ?: continue
                        v.background = paint(nm)
                    }
                    label.text = if (row.color.isBlank()) "default" else row.color
                }
            }
            strip.addView(b)
        }
        wrap.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(strip)
        })
        wrap.addView(label)
        return wrap
    }

    private fun lbl(t: String): TextView = TextView(this).apply {
        text = t
        textSize = 11f
        setTextColor(0xFF4A4A4A.toInt())
    }

    private fun rowView(idx: Int, row: Row): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(4, 8, 4, 8)
        }
        box.addView(TextView(this).apply {
            text = "${idx + 1}. ${row.file}"
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val line1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val visBox = CheckBox(this).apply {
            text = if (row.visible) "ON" else "OFF"
            isChecked = row.visible
            setOnCheckedChangeListener { _, v ->
                row.visible = v
                text = if (v) "ON" else "OFF"
            }
        }
        line1.addView(visBox)
        val edBox = CheckBox(this).apply {
            text = "Editable"
            isChecked = row.editable
            setOnCheckedChangeListener { _, v -> row.editable = v }
        }
        line1.addView(edBox)
        val upBtn = Button(this).apply {
            text = "Up"
            isAllCaps = false
            setOnClickListener { move(idx, -1) }
        }
        line1.addView(upBtn)
        val downBtn = Button(this).apply {
            text = "Down"
            isAllCaps = false
            setOnClickListener { move(idx, 1) }
        }
        line1.addView(downBtn)
        box.addView(line1)

        val line2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val symbols = when (row.kind) {
            MapCanvasView.MapLayer.Kind.POINT -> pointSymbols
            MapCanvasView.MapLayer.Kind.LINE -> lineSymbols
            MapCanvasView.MapLayer.Kind.POLYGON -> polySymbols
        }
        val symSpin = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@LayerSettingsActivity,
                android.R.layout.simple_spinner_item,
                symbols,
            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(symbols.indexOf(row.symbol).takeIf { it >= 0 } ?: 0)
            onItemSelectedListener = SimpleSelected { pos -> row.symbol = symbols[pos] }
        }
        line2.addView(symSpin, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(captionRow("Symbol", line2))
        box.addView(captionRow("Colour", colourSwatches(row)))
        if (row.kind == MapCanvasView.MapLayer.Kind.POLYGON) {
            val borderBtn = Button(this).apply {
                text = if (row.borderOnly) "Border only ✓" else "Border only"
                isAllCaps = false
                setOnClickListener {
                    row.borderOnly = !row.borderOnly
                    text = if (row.borderOnly) "Border only ✓" else "Border only"
                    if (row.borderOnly) row.symbol = "no fill"
                }
            }
            line2.addView(borderBtn)
        }

        fun numEdit(hint: String, value: String, set: (String) -> Unit): EditText =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                addTextChangedListener(SimpleWatcher { s -> set(s) })
            }
        // One control per row. Three to a line left no room for the value, so field
        // names came out as "GCALL_I" and "Call_Nur" and could not be read.
        box.addView(captionRow("Size (dp)", numEdit("2", row.size) { row.size = it }))
        val labelEdit = EditText(this).apply {
            hint = "field"
            setText(row.label)
            setSingleLine(true)
            addTextChangedListener(SimpleWatcher { s -> row.label = s })
        }
        box.addView(captionRow("Label field", labelEdit))
        box.addView(captionRow("Label size (sp)", numEdit("7", row.labelSize) { row.labelSize = it }))

        val sep = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (6 * resources.displayMetrics.density).toInt(),
            )
            setBackgroundColor(0xFFE0E0E0.toInt())
        }
        box.addView(sep)
        return box
    }

    private fun move(idx: Int, delta: Int) {
        val j = idx + delta
        if (j !in rows.indices) return
        val tmp = rows[idx]
        rows[idx] = rows[j]
        rows[j] = tmp
        rebuild()
    }

    private fun saveAndClose() {
        val specs = rows.map { row ->
            val colorRaw = if (row.borderOnly) "${row.color} border" else row.color
            LayerStyle.LayerSpec(
                name = row.specName,
                visible = row.visible,
                editable = row.editable,
                symbolRaw = row.symbol,
                colorRaw = colorRaw,
                sizeDp = row.size.toFloatOrNull(),
                labelField = row.label.takeIf { it.isNotBlank() },
                labelSizeSp = row.labelSize.toFloatOrNull(),
            )
        }
        // Merge into the active project doc so the settings half survives.
        val ok = try {
            val doc = LayerStyle.docWithLayers(app.projectText(), specs)
            app.writeProject(app.activeProject, doc)
        } catch (e: Exception) {
            false
        }
        Toast.makeText(
            this,
            if (ok) "Saved ${specs.size} layers to ${app.activeProject}" else "Save failed",
            Toast.LENGTH_LONG,
        ).show()
        if (ok) finish()
    }

}
