package com.s70rm3892.enginesim

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.s70rm3892.enginesim.ui.ConsoleSlider
import com.s70rm3892.enginesim.ui.Joystick
import com.s70rm3892.enginesim.ui.KeyButton
import com.s70rm3892.enginesim.ui.PedalButton
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.PanelFrame
import com.s70rm3892.enginesim.ui.RotaryKnob
import com.s70rm3892.enginesim.ui.SegmentedSelector
import com.s70rm3892.enginesim.ui.TelemetryView
import com.s70rm3892.enginesim.ui.dp
import com.s70rm3892.enginesim.ui.fmtRpm
import kotlin.math.roundToInt

/**
 * 操作コンソール。画面左側のボタン/スライダー/ノブのイベントだけを受け付け、
 * エンジン状態とカメラ状態をステートマシンで管理してネイティブへ送る。
 * 3D ビューポートは入力を一切受け取らない (EngineGLView 側で遮断)。
 */
class ConsoleUIController(
    private val activity: Activity,
    private val telemetry: TelemetryView,
    private val catalog: EngineCatalog,
    private val backend: ConsoleBackend = NativeBridge,
) {
    /** エンジン運転状態 (テレメトリから遷移) */
    enum class RunState(val label: String, val color: Int) {
        STOPPED("STOP", Pal.DIM), CRANKING("CRANK", Pal.AMBER), RUNNING("RUN", Pal.GREEN), LIMITER("LIMIT", Pal.RED)
    }

    /** カメラ状態: プリセット選択 → 手動 (パン/チルト/ズーム) オフセット */
    enum class CameraPreset(val label: String) { OVERVIEW("全体"), SECTION("断面"), CRANK("クランク"), VALVE("バルブ"), OUTPUT("出力軸") }

    enum class RenderMode(val label: String) { SOLID("SOLID"), XRAY("X-RAY"), SECTION("SECTION"), THERMAL("THERMAL"), STRESS("STRESS") }

    private val timeScales = floatArrayOf(1f, 0.25f, 1f / 16, 1f / 64, 1f / 256)
    private val timeScaleLabels = listOf("×1", "×1/4", "×1/16", "×1/64", "×1/256")

    // --- 状態 ---
    private var summary: EngineSummary? = null
    private var currentEntry: EngineCatalog.Entry? = null
    private var targetRpm = 800f
    private var throttle = 0f
    private var throttleLink = true
    private var load = 0f
    private var spark = 0f
    private var ignition = true
    private var starter = false
    private var autoStart = true
    private var gear = 0
    private var timeScaleIdx = 0
    private var cutCylinder = 0
    private var volume = 0.9f
    private var preset = CameraPreset.OVERVIEW
    private var presetSerial = 0
    private var mode = RenderMode.SOLID
    private var yawRate = 0f
    private var pitchRate = 0f
    private var zoom = 1f
    private var sectionOffset = 0f
    private var sectionAxis = 0
    private var runState = RunState.STOPPED
    private var driveMode = DriveMode.DYNO
    private var brake = false

    /** 駆動モード: 台上 (ダイナモ) / 車両 MT / 車両 AT */
    enum class DriveMode(val label: String) { DYNO("台上"), MT("MT"), AT("AT オートマ") }

    // --- ウィジェット ---
    private lateinit var engineButton: KeyButton
    private lateinit var specText: TextView
    private lateinit var statusText: TextView
    private lateinit var rpmSlider: ConsoleSlider
    private lateinit var throttleSlider: ConsoleSlider
    private lateinit var loadSlider: ConsoleSlider
    private lateinit var gearSel: SegmentedSelector
    private lateinit var modeSel: SegmentedSelector
    private lateinit var presetSel: SegmentedSelector
    private lateinit var ignKey: KeyButton
    private lateinit var linkKey: KeyButton
    private lateinit var cutKnob: RotaryKnob
    private lateinit var pedal: PedalButton
    private lateinit var driveSel: SegmentedSelector
    private lateinit var driveInfo: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val telBuf = FloatArray(Tel.COUNT)
    private val pvV = FloatArray(360)
    private val pvP = FloatArray(360)
    private var running = false

    fun buildInto(root: ViewGroup) {
        val scroll = ScrollView(activity).apply { isFillViewport = true; setBackgroundColor(Pal.BG) }
        val cols = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        val colA = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val colB = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        cols.addView(colA, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = activity.dp(4f).toInt() })
        cols.addView(colB, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val pad = activity.dp(4f).toInt()
        cols.setPadding(pad, pad, pad, pad)
        scroll.addView(cols)
        root.addView(scroll, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        colA.addView(enginePanel(), lp())
        colA.addView(drivePanel(), lp())
        colA.addView(rpmPanel(), lp())
        colB.addView(ignitionPanel(), lp())
        colB.addView(cameraPanel(), lp())
    }

    private fun lp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = activity.dp(4f).toInt()
    }

    private fun row(vararg views: View, weights: FloatArray? = null): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weights?.get(i) ?: 1f))
            }
        }

    private fun label(sizeSp: Float, color: Int, mono: Boolean = false) = TextView(activity).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (mono) typeface = Typeface.MONOSPACE
    }

    // ---------------- パネル構築 ----------------

    private fun enginePanel(): View = PanelFrame(activity, "ENGINE SELECT & SPEC").apply {
        engineButton = KeyButton(activity, "エンジン選択 ▾").apply { onRelease = { showEngineDialog() } }
        val designKey = KeyButton(activity, "n気筒 設計").apply {
            accent = Pal.CYAN
            onRelease = { CustomEngineDialog(activity, catalog, backend) { loadEngine(it) }.show() }
        }
        statusText = label(10f, Pal.GREEN, mono = true).apply { gravity = Gravity.CENTER }
        addView(row(engineButton, designKey, statusText, weights = floatArrayOf(3f, 1.6f, 1f)))
        specText = label(8.5f, Pal.TEXT, mono = true).apply {
            setPadding(0, activity.dp(2f).toInt(), 0, activity.dp(2f).toInt())
            maxLines = 4
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        addView(specText)
        ignKey = KeyButton(activity, "IGN", toggle = true).apply {
            on = true; accent = Pal.GREEN
            onToggle = { ignition = it; pushControls() }
        }
        val startKey = KeyButton(activity, "START").apply {
            accent = Pal.RED
            onPress = { starter = true; pushControls() }
            onRelease = { starter = false; pushControls() }
        }
        val autoKey = KeyButton(activity, "AUTO START", toggle = true).apply {
            on = true
            onToggle = { autoStart = it; pushControls() }
        }
        addView(row(ignKey, startKey, autoKey))
    }

    private fun drivePanel(): View = PanelFrame(activity, "DRIVE / ACCEL PEDAL").apply {
        driveSel = SegmentedSelector(activity, DriveMode.entries.map { it.label }).apply {
            label = "駆動モード"
            accent = Pal.GREEN
            onSelect = { setDriveMode(DriveMode.entries[it]) }
        }
        addView(driveSel)
        pedal = PedalButton(activity).apply { label = "アクセル" }
        val brakeKey = KeyButton(activity, "ブレーキ").apply {
            accent = Pal.RED
            onPress = { brake = true; pushControls() }
            onRelease = { brake = false; pushControls() }
            minimumHeight = activity.dp(78f).toInt()
        }
        driveInfo = label(10f, Pal.TEXT, mono = true).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(activity.dp(4f).toInt(), 0, 0, 0) }
        addView(row(pedal, brakeKey, driveInfo, weights = floatArrayOf(1.4f, 0.9f, 1.3f)))
    }

    private fun setDriveMode(m: DriveMode) {
        val s = summary
        if (m != DriveMode.DYNO && s != null && (s.propeller || s.isTurbine)) {
            driveSel.selected = DriveMode.DYNO.ordinal
            driveInfo.text = "この機種は\n車両モード非対応"
            return
        }
        driveMode = m
        // 車両モードではスロットルはペダル (または手動スライダ) で操作する
        if (m != DriveMode.DYNO && throttleLink) {
            throttleLink = false
            linkKey.on = false
            throttleSlider.isEnabled = true
        }
        refreshGearItems()
        loadSlider.label = if (m == DriveMode.DYNO) dynoLabel() else "路面勾配 (0〜15%)"
        loadSlider.format = if (m == DriveMode.DYNO) { v -> "%3.0f %%".format(v * 100) } else { v -> "%.1f %%".format(v * 15) }
        loadSlider.invalidate()
        pushControls()
    }

    private fun dynoLabel(): String {
        val s = summary ?: return "DYNO LOAD (負荷)"
        return if (s.propeller) "PROP PITCH (プロペラ吸収)" else "DYNO LOAD (負荷 ${"%.0f".format(s.peakTorque)} Nm 比)"
    }

    private fun refreshGearItems() {
        val s = summary ?: return
        val items = when {
            s.propeller -> listOf("PROP")
            driveMode == DriveMode.AT || s.gears <= 1 -> listOf("N", "D")
            else -> listOf("N") + (1..s.gears).map { "$it" }
        }
        gearSel.items = items
        gear = gear.coerceAtMost(items.size - 1)
        if (driveMode == DriveMode.AT && gear > 1) gear = 1
        gearSel.selected = gear
    }

    private fun updateDriveInfo() {
        if (driveMode == DriveMode.DYNO) {
            driveInfo.text = "台上運転\nペダルで空ぶかし"
            return
        }
        val g = telBuf[Tel.EFF_GEAR].toInt()
        val gs = when {
            g == 0 -> "N"
            driveMode == DriveMode.AT -> "D$g" + if (telBuf[Tel.LOCKUP] > 0.5f) " L/U" else ""
            else -> "$g 速"
        }
        driveInfo.text = "%5.1f km/h\n%s%s".format(telBuf[Tel.SPEED_KMH], gs, if (telBuf[Tel.SHIFTING] > 0.5f) " ⇅" else "")
    }

    private fun rpmPanel(): View = PanelFrame(activity, "RPM / THROTTLE / LOAD").apply {
        rpmSlider = ConsoleSlider(activity).apply {
            label = "TARGET RPM (Idle〜Redline)"
            format = { it.fmtRpm() }
            onChange = { targetRpm = it; pushControls() }
        }
        addView(rpmSlider)
        fun step(txt: String, d: Float) = KeyButton(activity, txt).apply {
            onRelease = { rpmSlider.value = rpmSlider.value + d; targetRpm = rpmSlider.value; pushControls() }
        }
        addView(row(step("−1000", -1000f), step("−100", -100f), step("+100", 100f), step("+1000", 1000f)))
        linkKey = KeyButton(activity, "THROTTLE ⇔ RPM 連動", toggle = true).apply {
            on = true; accent = Pal.CYAN
            onToggle = { throttleLink = it; throttleSlider.isEnabled = !it; throttleSlider.invalidate(); pushControls() }
        }
        addView(linkKey)
        throttleSlider = ConsoleSlider(activity).apply {
            label = "THROTTLE 開度"
            min = 0f; max = 1f; value = 0f
            isEnabled = false
            format = { "%3.0f %%".format(it * 100) }
            onChange = { throttle = it; pushControls() }
        }
        addView(throttleSlider)
        loadSlider = ConsoleSlider(activity).apply {
            label = "DYNO LOAD (負荷)"
            min = 0f; max = 1f; value = 0f
            accent = Pal.RED
            format = { "%3.0f %%".format(it * 100) }
            onChange = { load = it; pushControls() }
        }
        addView(loadSlider)
    }

    private fun ignitionPanel(): View = PanelFrame(activity, "IGNITION / TRANSMISSION / TIME").apply {
        val sparkKnob = RotaryKnob(activity).apply {
            label = "点火時期"; min = -15f; max = 15f; value = 0f; defaultValue = 0f
            format = { "%+.0f°".format(it) }
            onChange = { spark = it.roundToInt().toFloat(); pushControls() }
        }
        cutKnob = RotaryKnob(activity).apply {
            label = "失火気筒"; min = 0f; max = 1f; value = 0f; accent = Pal.RED
            format = { if (it.roundToInt() == 0) "なし" else "#${it.roundToInt()}" }
            onChange = { cutCylinder = it.roundToInt(); pushControls() }
        }
        val volKnob = RotaryKnob(activity).apply {
            label = "音量"; min = 0f; max = 2f; value = volume; defaultValue = 0.9f; accent = Pal.GREEN
            format = { "%.0f%%".format(it * 50) }
            onChange = { volume = it; backend.setVolume(it) }
        }
        addView(row(sparkKnob, cutKnob, volKnob))
        gearSel = SegmentedSelector(activity, listOf("N")).apply {
            label = "GEAR (トランスミッション)"
            onSelect = { gear = it; pushControls() }
        }
        addView(gearSel)
        addView(SegmentedSelector(activity, timeScaleLabels).apply {
            label = "映像タイムスケール (音は実時間)"
            accent = Pal.CYAN
            onSelect = { timeScaleIdx = it; pushControls() }
        })
    }

    private fun cameraPanel(): View = PanelFrame(activity, "CAMERA / RENDER").apply {
        presetSel = SegmentedSelector(activity, CameraPreset.entries.map { it.label }).apply {
            label = "視点 (全体俯瞰/シリンダー断面/クランク正対/バルブ追従/駆動出力軸)"
            accent = Pal.CYAN
            onSelect = { preset = CameraPreset.entries[it]; presetSerial++; zoom = 1f; zoomKnob?.value = 1f; pushView() }
        }
        addView(presetSel)
        modeSel = SegmentedSelector(activity, RenderMode.entries.map { it.label }).apply {
            label = "表示モード (シェーダー)"
            accent = Pal.VIOLET
            onSelect = { mode = RenderMode.entries[it]; lastModeChange = System.currentTimeMillis(); pushView() }
        }
        addView(modeSel)
        val joy = Joystick(activity).apply {
            onMove = { x, y -> yawRate = -x * 1.4f; pitchRate = y * 1.0f; pushView() }
        }
        val zk = RotaryKnob(activity).apply {
            label = "ZOOM"; min = 0.3f; max = 4f; value = 1f; defaultValue = 1f
            format = { "×%.1f".format(it) }
            onChange = { zoom = it; pushView() }
        }
        zoomKnob = zk
        val secKnob = RotaryKnob(activity).apply {
            label = "断面位置"; min = -1f; max = 1f; value = 0f; defaultValue = 0f; accent = Pal.AMBER
            format = { "%+.2f".format(it) }
            onChange = { sectionOffset = it; pushView() }
        }
        val axisKey = KeyButton(activity, "断面軸 切替", toggle = true).apply {
            onToggle = { sectionAxis = if (it) 1 else 0; pushView() }
        }
        val right = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(zk, secKnob))
            addView(axisKey)
        }
        addView(row(joy, right, weights = floatArrayOf(1f, 2f)))
    }

    private var zoomKnob: RotaryKnob? = null
    private var lastModeChange = 0L

    // ---------------- エンジン選択 ----------------

    private fun showEngineDialog() {
        val entries = catalog.entries
        val labels = entries.map { "【${it.category}】 ${it.name}" }.toTypedArray()
        AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("エンジン・カタログ (${entries.size} 機種)")
            .setItems(labels) { _, which -> loadEngine(entries[which]) }
            .show()
    }

    fun loadEngine(entry: EngineCatalog.Entry) {
        val result = backend.loadEngine(catalog.json(entry))
        val s = EngineSummary.parse(result) ?: run {
            specText.text = "読み込み失敗: $result"
            return
        }
        currentEntry = entry
        summary = s
        telemetry.summary = s
        engineButton.text = s.name
        engineButton.invalidate()
        // スライダー範囲などを機種に合わせる
        val lo = if (s.isElectric) 0f else s.idleRpm
        rpmSlider.min = lo
        rpmSlider.max = s.redlineRpm
        rpmSlider.redFrom = if (s.isElectric) null else s.redlineRpm * 0.92f
        rpmSlider.value = lo
        targetRpm = lo
        loadSlider.label = if (s.propeller) "PROP PITCH (プロペラ吸収)" else "DYNO LOAD (負荷 ${"%.0f".format(s.peakTorque)} Nm 比)"
        loadSlider.value = 0f
        load = 0f
        throttle = 0f
        throttleSlider.value = 0f
        gear = 0
        if ((s.propeller || s.isTurbine) && driveMode != DriveMode.DYNO) {
            driveMode = DriveMode.DYNO
            driveSel.selected = 0
        }
        refreshGearItems()
        if (driveMode != DriveMode.DYNO) loadSlider.label = "路面勾配 (0〜15%)"
        cutKnob.max = s.cylinders.coerceAtLeast(1).toFloat()
        cutKnob.value = 0f
        cutCylinder = 0
        spark = 0f
        presetSerial++
        specText.text = buildSpecText(s)
        pushControls()
        pushView()
    }

    private fun buildSpecText(s: EngineSummary): String {
        val sb = StringBuilder()
        when {
            s.isTurbine -> sb.append("ガスタービン | N2 idle ${s.idleRpm.fmtRpm()} / max ${s.redlineRpm.fmtRpm()} rpm\n")
            s.isElectric -> sb.append("電動機 | max ${s.redlineRpm.fmtRpm()} rpm | 定格 ${"%.0f".format(s.peakTorque)} Nm\n")
            else -> {
                sb.append("${s.cylinders}気筒 ${"%.2f".format(s.displacementL)} L | ")
                if (s.family == "wankel") sb.append("ローター ${s.cylinders}\n")
                else sb.append("B×S ${"%.1f".format(s.boreMm)}×${"%.1f".format(s.strokeMm)} mm | ε ${"%.1f".format(s.compressionRatio)}\n")
                sb.append("${s.cycle.uppercase()} | idle ${s.idleRpm.fmtRpm()} / red ${s.redlineRpm.fmtRpm()} rpm")
                if (s.firingOrder.isNotEmpty()) sb.append(" | 点火 ${s.firingOrder}")
                sb.append('\n')
            }
        }
        sb.append(s.description)
        return sb.toString()
    }

    // ---------------- ネイティブへの送信 ----------------

    private fun pushControls() {
        val cut = if (cutCylinder in 1..63) 1L shl (cutCylinder - 1) else 0L
        val g = if (summary?.propeller == true) 0 else gear
        // アクセルペダルが踏まれている間はペダル開度が最優先 (ガバナ連動も一時解除)
        val pedalActive = ::pedal.isInitialized && (pedal.pressed || pedal.value > 0.001f)
        val vehicle = driveMode != DriveMode.DYNO
        val thr = if (pedalActive) pedal.value else throttle
        val link = throttleLink && !pedalActive && !vehicle
        backend.setControls(
            targetRpm, thr, link, if (vehicle) 0f else load, spark, ignition, starter, g,
            timeScales[timeScaleIdx], cut, autoStart,
            driveMode.ordinal, if (brake) 1f else 0f, if (vehicle) load else 0f,
        )
    }

    private fun pushView() {
        backend.setView(preset.ordinal, presetSerial, mode.ordinal, yawRate, pitchRate, zoom, sectionOffset, sectionAxis, 0)
    }

    // ---------------- 周期更新 (30Hz) ----------------

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val before = pedal.value
            pedal.advance(0.033f)
            if (pedal.value != before || pedal.pressed) pushControls()
            val n = backend.getTelemetry(telBuf)
            if (n >= Tel.COUNT && summary != null) {
                val pvn = backend.getPV(pvV, pvP)
                telemetry.update(telBuf, pvV, pvP, pvn)
                rpmSlider.secondary = telBuf[Tel.RPM]
                updateDriveInfo()
                if (throttleLink) throttleSlider.value = telBuf[Tel.THROTTLE]
                updateRunState()
                // 断面プリセットで描画モードが自動切替された場合、コンソール側の状態も合わせる
                val eff = telBuf[Tel.RENDER_MODE].toInt()
                if (eff != mode.ordinal && System.currentTimeMillis() - lastModeChange > 300 && eff in RenderMode.entries.indices) {
                    mode = RenderMode.entries[eff]
                    modeSel.selected = eff
                    pushView()
                }
            }
            handler.postDelayed(this, 33)
        }
    }

    private fun updateRunState() {
        val s = summary ?: return
        val rpm = telBuf[Tel.RPM]
        runState = when {
            telBuf[Tel.LIMITER] > 0.5f -> RunState.LIMITER
            telBuf[Tel.RUNNING] > 0.5f -> RunState.RUNNING
            rpm > 5f && (starter || autoStart) && !s.isElectric -> RunState.CRANKING
            else -> RunState.STOPPED
        }
        statusText.text = "● ${runState.label}"
        statusText.setTextColor(runState.color)
    }

    fun start() {
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    fun loadDefault() {
        val e = catalog.entries.firstOrNull { it.id == "i4_20" } ?: catalog.entries.first()
        loadEngine(e)
        backend.setVolume(volume)
    }
}
