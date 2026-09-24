package com.s70rm3892.enginesim

import android.app.Activity
import android.app.AlertDialog
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.s70rm3892.enginesim.ui.KeyButton
import com.s70rm3892.enginesim.ui.NumberStepper
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.SegmentedSelector
import com.s70rm3892.enginesim.ui.dp
import org.json.JSONObject

/**
 * n 気筒エンジンの設計ダイアログ。レイアウト/気筒数/寸法を決めると、
 * ネイティブ側が等間隔点火になるクランクピン配置を自動設計して JSON を生成する。
 */
class CustomEngineDialog(
    private val activity: Activity,
    private val catalog: EngineCatalog,
    private val backend: ConsoleBackend,
    private val onCreated: (EngineCatalog.Entry) -> Unit,
) {
    private data class LayoutDef(val key: String, val label: String, val min: Int, val max: Int, val step: Int, val def: Int)

    private val layouts = listOf(
        LayoutDef("inline", "直列", 1, 16, 1, 4),
        LayoutDef("v", "V型", 2, 24, 2, 8),
        LayoutDef("flat", "水平対向", 2, 16, 2, 6),
        LayoutDef("radial", "星型", 3, 11, 1, 9),
        LayoutDef("opposed", "対向ピストン", 1, 12, 1, 6),
        LayoutDef("wankel", "ロータリー", 1, 4, 1, 2),
    )
    private val cycles = listOf("otto4" to "ガソリン4スト", "diesel4" to "ディーゼル4スト", "otto2" to "2スト")
    private val inductions = listOf("natural" to "NA", "turbo" to "ターボ", "roots" to "スーパーチャージャー")

    private var layoutIdx = 0
    private var cycleIdx = 0
    private var inductionIdx = 0
    private var evenFire = true
    private var crossplane = false

    fun show() {
        val a = activity
        val col = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = a.dp(10f).toInt()
            setPadding(p, p, p, p)
            setBackgroundColor(Pal.BG)
        }
        fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = a.dp(6f).toInt()
        }
        fun row(vararg v: View) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = a.dp(4f).toInt() }) }
        }

        val nameEdit = EditText(a).apply {
            setText("マイエンジン")
            setTextColor(Pal.TEXT)
            setHintTextColor(Pal.DIM)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            hint = "名前"
        }
        val cylStep = NumberStepper(a).apply { label = "気筒数"; step = 1f }
        val rowsStep = NumberStepper(a).apply { label = "列数 (星型)"; min = 1f; max = 4f; value = 1f }
        val bankStep = NumberStepper(a).apply {
            label = "バンク角 (V型)"; min = 15f; max = 180f; step = 5f; value = 90f; format = { "%.0f°".format(it) }
        }
        val boreStep = NumberStepper(a).apply { label = "ボア mm"; min = 30f; max = 250f; step = 1f; value = 86f }
        val strokeStep = NumberStepper(a).apply { label = "ストローク mm"; min = 30f; max = 300f; step = 1f; value = 86f }
        val crStep = NumberStepper(a).apply { label = "圧縮比"; min = 6f; max = 22f; step = 0.5f; value = 10.5f; format = { "%.1f".format(it) } }
        val redStep = NumberStepper(a).apply { label = "レッドゾーン rpm"; min = 1000f; max = 20000f; step = 250f; value = 7000f }
        val idleStep = NumberStepper(a).apply { label = "アイドル rpm"; min = 300f; max = 2500f; step = 50f; value = 800f }
        val boostStep = NumberStepper(a).apply { label = "過給圧 bar"; min = 0.2f; max = 3f; step = 0.1f; value = 1f; format = { "%.1f".format(it) } }
        val info = TextView(a).apply { setTextColor(Pal.CYAN); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f) }

        fun applyLayoutLimits() {
            val d = layouts[layoutIdx]
            cylStep.min = d.min.toFloat(); cylStep.max = d.max.toFloat(); cylStep.step = d.step.toFloat()
            cylStep.label = if (d.key == "wankel") "ローター数" else if (d.key == "radial") "1 列の気筒数" else "気筒数"
            cylStep.value = d.def.toFloat()
            rowsStep.visibility = if (d.key == "radial") View.VISIBLE else View.GONE
            bankStep.visibility = if (d.key == "v") View.VISIBLE else View.GONE
        }

        fun updateInfo() {
            val d = layouts[layoutIdx]
            val n = cylStep.value.toInt() * (if (d.key == "radial") rowsStep.value.toInt() else 1)
            val volL = Math.PI / 4 * (boreStep.value / 1000.0).let { it * it } * (strokeStep.value / 1000.0) * 1000.0 *
                n * (if (d.key == "opposed") 2 else 1)
            info.text = if (d.key == "wankel") "約 ${"%.2f".format(0.654 * cylStep.value)} L (13B 級ローター)"
            else "総排気量 約 ${"%.2f".format(volL)} L / ${n} 気筒 — クランクは等間隔点火に自動設計"
        }

        val layoutSel = SegmentedSelector(a, layouts.map { it.label }).apply {
            label = "レイアウト"
            onSelect = { layoutIdx = it; applyLayoutLimits(); updateInfo() }
        }
        val cycleSel = SegmentedSelector(a, cycles.map { it.second }).apply {
            label = "サイクル"; accent = Pal.CYAN
            onSelect = { cycleIdx = it; if (it == 1) crStep.value = 17f }
        }
        val indSel = SegmentedSelector(a, inductions.map { it.second }).apply {
            label = "過給"; accent = Pal.VIOLET
            onSelect = { inductionIdx = it }
        }
        val evenKey = KeyButton(a, "等間隔点火 (スプリットピン)", toggle = true).apply { on = true; onToggle = { evenFire = it } }
        val crossKey = KeyButton(a, "クロスプレーン (V8)", toggle = true).apply { onToggle = { crossplane = it } }
        listOf(cylStep, rowsStep, boreStep, strokeStep).forEach { st -> st.onChange = { updateInfo() } }

        col.addView(nameEdit, lp())
        col.addView(layoutSel, lp())
        col.addView(row(cylStep, rowsStep, bankStep), lp())
        col.addView(row(evenKey, crossKey), lp())
        col.addView(cycleSel, lp())
        col.addView(row(boreStep, strokeStep, crStep), lp())
        col.addView(row(idleStep, redStep), lp())
        col.addView(indSel, lp())
        col.addView(row(boostStep, View(a)), lp())
        col.addView(info, lp())
        applyLayoutLimits()
        updateInfo()

        val scroll = ScrollView(a).apply { addView(col) }
        val dialog = AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("n 気筒エンジンを設計")
            .setView(scroll)
            .setPositiveButton("生成して運転", null)
            .setNegativeButton("キャンセル", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val d = layouts[layoutIdx]
                val params = JSONObject().apply {
                    put("name", nameEdit.text.toString().ifBlank { "カスタム" })
                    put("layout", d.key)
                    put("cylinders", cylStep.value.toInt())
                    put("rows", rowsStep.value.toInt())
                    put("bankAngle", bankStep.value.toDouble())
                    put("evenFire", evenFire)
                    put("crossplane", crossplane)
                    put("cycle", cycles[cycleIdx].first)
                    put("boreMm", boreStep.value.toDouble())
                    put("strokeMm", strokeStep.value.toDouble())
                    put("compressionRatio", crStep.value.toDouble())
                    put("idleRpm", idleStep.value.toDouble())
                    put("redlineRpm", redStep.value.toDouble())
                    put("induction", inductions[inductionIdx].first)
                    put("boostBar", boostStep.value.toDouble())
                }
                val json = backend.buildCustomEngine(params.toString())
                val o = JSONObject(json)
                if (o.has("error")) {
                    info.setTextColor(Pal.RED)
                    info.text = "生成できません: ${o.getString("error")}"
                    return@setOnClickListener
                }
                val entry = catalog.saveCustom(json)
                dialog.dismiss()
                onCreated(entry)
            }
        }
        dialog.show()
    }
}
