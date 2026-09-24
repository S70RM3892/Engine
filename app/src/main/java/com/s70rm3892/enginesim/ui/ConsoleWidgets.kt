package com.s70rm3892.enginesim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 計器盤風の配色 */
object Pal {
    val BG = Color.rgb(12, 14, 17)
    val PANEL = Color.rgb(24, 27, 33)
    val PANEL_HI = Color.rgb(34, 38, 46)
    val BORDER = Color.rgb(52, 58, 70)
    val TEXT = Color.rgb(222, 226, 232)
    val DIM = Color.rgb(128, 136, 150)
    val AMBER = Color.rgb(255, 176, 32)
    val CYAN = Color.rgb(64, 200, 230)
    val RED = Color.rgb(235, 64, 52)
    val GREEN = Color.rgb(80, 210, 120)
    val VIOLET = Color.rgb(170, 120, 255)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun View.dp(v: Float): Float = context.dp(v)

private fun textPaint(view: View, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) =
    Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, sizeSp, view.resources.displayMetrics)
        this.color = color
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
    }

/** 無段階リニアスライダー (レバー型)。レッドゾーン表示付き。 */
@SuppressLint("ClickableViewAccessibility")
class ConsoleSlider(context: Context) : View(context) {
    var label = ""
    var min = 0f
    var max = 1f
    var redFrom: Float? = null
    var markers: List<Float> = emptyList()
    var accent = Pal.AMBER
    var format: (Float) -> String = { "%.2f".format(it) }
    var onChange: ((Float) -> Unit)? = null
    var secondary: Float? = null  // 実測値などを細線で重ねる
        set(v) { field = v; invalidate() }
    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }

    private val lab = textPaint(this, 10f, Pal.DIM)
    private val valP = textPaint(this, 11f, Pal.TEXT, bold = true, mono = true)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init { minimumHeight = dp(42f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    private fun frac(v: Float) = if (max > min) (v - min) / (max - min) else 0f
    private fun trackLeft() = dp(10f)
    private fun trackRight() = width - dp(10f)

    override fun onDraw(c: Canvas) {
        val alpha = if (isEnabled) 255 else 90
        val y = height * 0.66f
        c.drawText(label, dp(4f), dp(13f), lab)
        val vt = format(value)
        c.drawText(vt, width - valP.measureText(vt) - dp(4f), dp(13f), valP)
        val l = trackLeft(); val rr = trackRight()
        // トラック
        p.style = Paint.Style.FILL
        p.color = Pal.PANEL_HI; p.alpha = alpha
        r.set(l, y - dp(4f), rr, y + dp(4f)); c.drawRoundRect(r, dp(3f), dp(3f), p)
        redFrom?.let {
            p.color = Pal.RED; p.alpha = (alpha * 0.8f).toInt()
            r.set(l + (rr - l) * frac(it), y - dp(4f), rr, y + dp(4f)); c.drawRoundRect(r, dp(3f), dp(3f), p)
        }
        p.color = accent; p.alpha = alpha
        r.set(l, y - dp(2f), l + (rr - l) * frac(value), y + dp(2f)); c.drawRect(r, p)
        // 目盛り
        p.color = Pal.BORDER; p.strokeWidth = dp(1f)
        for (i in 0..10) {
            val x = l + (rr - l) * i / 10f
            c.drawLine(x, y + dp(6f), x, y + dp(if (i % 5 == 0) 12f else 9f), p)
        }
        p.color = Pal.CYAN
        for (m in markers) { val x = l + (rr - l) * frac(m); c.drawLine(x, y - dp(8f), x, y + dp(8f), p) }
        secondary?.let {
            p.color = Pal.GREEN; p.strokeWidth = dp(2f)
            val x = l + (rr - l) * frac(it.coerceIn(min, max)); c.drawLine(x, y - dp(9f), x, y + dp(9f), p)
        }
        // レバーつまみ
        val tx = l + (rr - l) * frac(value)
        p.color = Pal.TEXT; p.alpha = alpha
        r.set(tx - dp(6f), y - dp(11f), tx + dp(6f), y + dp(11f)); c.drawRoundRect(r, dp(2f), dp(2f), p)
        p.color = accent; p.alpha = alpha
        c.drawRect(tx - dp(5f), y - dp(1f), tx + dp(5f), y + dp(1f), p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = ((e.x - trackLeft()) / (trackRight() - trackLeft())).coerceIn(0f, 1f)
                value = min + f * (max - min)
                onChange?.invoke(value)
            }
        }
        return true
    }
}

/** ロータリーノブ (上下/左右ドラッグで回す)。ダブルタップで既定値に戻す。 */
@SuppressLint("ClickableViewAccessibility")
class RotaryKnob(context: Context) : View(context) {
    var label = ""
    var min = 0f
    var max = 1f
    var defaultValue = 0f
    var accent = Pal.CYAN
    var format: (Float) -> String = { "%.2f".format(it) }
    var onChange: ((Float) -> Unit)? = null
    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }

    private val lab = textPaint(this, 9f, Pal.DIM)
    private val valP = textPaint(this, 10f, Pal.TEXT, bold = true, mono = true)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private var lastTap = 0L

    init { minimumHeight = dp(66f).toInt(); minimumWidth = dp(54f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val rad = min(width, height - dp(26f).toInt()) / 2f - dp(4f)
        val cy = dp(12f) + rad + dp(2f)
        val f = (value - min) / (max - min)
        arc.set(cx - rad, cy - rad, cx + rad, cy + rad)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(3f); p.strokeCap = Paint.Cap.ROUND
        p.color = Pal.PANEL_HI; c.drawArc(arc, 135f, 270f, false, p)
        p.color = accent; c.drawArc(arc, 135f, 270f * f, false, p)
        p.style = Paint.Style.FILL
        p.color = Pal.PANEL_HI; c.drawCircle(cx, cy, rad * 0.72f, p)
        p.color = Pal.BORDER; p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); c.drawCircle(cx, cy, rad * 0.72f, p)
        val a = Math.toRadians((135.0 + 270.0 * f))
        p.color = Pal.TEXT; p.strokeWidth = dp(2.5f)
        c.drawLine(cx + (rad * 0.25f * cos(a)).toFloat(), cy + (rad * 0.25f * sin(a)).toFloat(),
            cx + (rad * 0.68f * cos(a)).toFloat(), cy + (rad * 0.68f * sin(a)).toFloat(), p)
        c.drawText(label, cx - lab.measureText(label) / 2, dp(10f), lab)
        val vt = format(value)
        c.drawText(vt, cx - valP.measureText(vt) / 2, height - dp(3f), valP)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val now = System.currentTimeMillis()
                if (now - lastTap < 300) { value = defaultValue; onChange?.invoke(value) }
                lastTap = now
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val d = (e.x - lastX) - (e.y - lastY)
                lastX = e.x; lastY = e.y
                value += d / dp(160f) * (max - min)
                onChange?.invoke(value)
            }
        }
        return true
    }
}

/** パン/チルト用ジョイスティック (離すと中立に戻る)。 */
@SuppressLint("ClickableViewAccessibility")
class Joystick(context: Context) : View(context) {
    var onMove: ((Float, Float) -> Unit)? = null
    var label = "PAN / TILT"
    private var sx = 0f
    private var sy = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lab = textPaint(this, 9f, Pal.DIM)

    init { minimumHeight = dp(72f).toInt(); minimumWidth = dp(72f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val rad = min(width, height - dp(12f).toInt()) / 2f - dp(4f)
        val cy = dp(12f) + rad
        p.style = Paint.Style.FILL; p.color = Pal.PANEL_HI; c.drawCircle(cx, cy, rad, p)
        p.style = Paint.Style.STROKE; p.color = Pal.BORDER; p.strokeWidth = dp(1f)
        c.drawCircle(cx, cy, rad, p); c.drawCircle(cx, cy, rad * 0.5f, p)
        c.drawLine(cx - rad, cy, cx + rad, cy, p); c.drawLine(cx, cy - rad, cx, cy + rad, p)
        p.style = Paint.Style.FILL; p.color = Pal.AMBER
        c.drawCircle(cx + sx * rad * 0.7f, cy + sy * rad * 0.7f, rad * 0.28f, p)
        c.drawText(label, cx - lab.measureText(label) / 2, dp(10f), lab)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f
        val rad = min(width, height - dp(12f).toInt()) / 2f - dp(4f)
        val cy = dp(12f) + rad
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                var x = (e.x - cx) / (rad * 0.7f)
                var y = (e.y - cy) / (rad * 0.7f)
                val m = hypot(x, y)
                if (m > 1f) { x /= m; y /= m }
                sx = x; sy = y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { sx = 0f; sy = 0f }
        }
        onMove?.invoke(sx, sy)
        invalidate()
        return true
    }

    @Suppress("unused")
    private fun angle() = atan2(sy, sx)
}

/** セグメント切替 (排他選択)。 */
@SuppressLint("ClickableViewAccessibility")
class SegmentedSelector(context: Context, items: List<String>) : View(context) {
    var items: List<String> = items
        set(v) { field = v; invalidate() }
    var label = ""
    var accent = Pal.AMBER
    var onSelect: ((Int) -> Unit)? = null
    var selected = 0
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = textPaint(this, 10f, Pal.TEXT, bold = true)
    private val baseText = txt.textSize
    private val lab = textPaint(this, 9f, Pal.DIM)
    private val r = RectF()

    init { minimumHeight = dp(40f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    private fun top() = if (label.isEmpty()) dp(2f) else dp(14f)

    override fun onDraw(c: Canvas) {
        if (label.isNotEmpty()) c.drawText(label, dp(2f), dp(10f), lab)
        val n = items.size.coerceAtLeast(1)
        val w = (width - dp(2f)) / n
        val t = top()
        for (i in items.indices) {
            r.set(dp(1f) + i * w + dp(1f), t, dp(1f) + (i + 1) * w - dp(1f), height - dp(2f))
            p.style = Paint.Style.FILL
            p.color = if (i == selected) accent else Pal.PANEL_HI
            c.drawRoundRect(r, dp(3f), dp(3f), p)
            txt.color = if (i == selected) Color.BLACK else Pal.TEXT
            val s = items[i]
            txt.textSize = baseText
            while (txt.measureText(s) > r.width() - dp(4f) && txt.textSize > dp(6f)) txt.textSize = txt.textSize - 1f
            c.drawText(s, r.centerX() - txt.measureText(s) / 2, r.centerY() + txt.textSize * 0.36f, txt)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val n = items.size.coerceAtLeast(1)
            val i = ((e.x / width) * n).toInt().coerceIn(0, n - 1)
            selected = i
            onSelect?.invoke(i)
        }
        return true
    }
}

/** 照光式キー (トグル or モーメンタリ)。 */
@SuppressLint("ClickableViewAccessibility")
class KeyButton(context: Context, var text: String, private val toggle: Boolean = false) : View(context) {
    var accent = Pal.AMBER
    var onPress: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null
    var onToggle: ((Boolean) -> Unit)? = null
    var on = false
        set(v) { field = v; invalidate() }
    private var down = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = textPaint(this, 10f, Pal.TEXT, bold = true)
    private val r = RectF()

    init { minimumHeight = dp(30f).toInt(); minimumWidth = dp(40f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        r.set(dp(1.5f), dp(1.5f), width - dp(1.5f), height - dp(1.5f))
        p.style = Paint.Style.FILL
        p.color = if (down) Pal.BORDER else Pal.PANEL_HI
        c.drawRoundRect(r, dp(4f), dp(4f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.2f)
        p.color = if (on || down) accent else Pal.BORDER
        c.drawRoundRect(r, dp(4f), dp(4f), p)
        if (toggle) {
            p.style = Paint.Style.FILL
            p.color = if (on) accent else Pal.BORDER
            c.drawCircle(dp(8f), height / 2f, dp(3f), p)
        }
        txt.color = if (isEnabled) Pal.TEXT else Pal.DIM
        var size = txt.textSize
        while (txt.measureText(text) > width - dp(if (toggle) 18f else 6f) && size > dp(6f)) { size -= 1f; txt.textSize = size }
        val x = if (toggle) dp(14f) + (width - dp(14f) - txt.measureText(text)) / 2 else (width - txt.measureText(text)) / 2
        c.drawText(text, x, height / 2f + txt.textSize * 0.36f, txt)
        txt.textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; onPress?.invoke(); invalidate() }
            MotionEvent.ACTION_UP -> {
                down = false
                if (toggle) { on = !on; onToggle?.invoke(on) }
                onRelease?.invoke(); invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = false; onRelease?.invoke(); invalidate() }
        }
        return true
    }
}

/** パネル見出し付きの枠 (背景描画のみ)。 */
class PanelFrame(context: Context, private val title: String) : android.widget.LinearLayout(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = textPaint(this, 9f, Pal.AMBER, bold = true)
    private val r = RectF()

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        val pad = dp(6f).toInt()
        setPadding(pad, dp(16f).toInt(), pad, pad)
    }

    override fun onDraw(c: Canvas) {
        r.set(dp(1f), dp(6f), width - dp(1f), height - dp(1f))
        p.style = Paint.Style.FILL; p.color = Pal.PANEL
        c.drawRoundRect(r, dp(6f), dp(6f), p)
        p.style = Paint.Style.STROKE; p.color = Pal.BORDER; p.strokeWidth = dp(1f)
        c.drawRoundRect(r, dp(6f), dp(6f), p)
        val tw = t.measureText(title)
        p.style = Paint.Style.FILL; p.color = Pal.BG
        c.drawRect(dp(8f), 0f, dp(14f) + tw, dp(12f), p)
        c.drawText(title, dp(11f), dp(10f), t)
    }
}

fun Float.fmtRpm(): String = "%,d".format(roundToInt())

/**
 * アクセルペダル。押している間は踏み込み量 (押した位置: 下ほど深い) まで開度が上がり、離すと戻る。
 * 実車のペダル同様に開閉速度を持たせる (tick で [advance] を呼ぶ)。
 */
@SuppressLint("ClickableViewAccessibility")
class PedalButton(context: Context) : View(context) {
    var label = "ACCEL"
    var accent = Pal.GREEN
    var onChange: ((Float) -> Unit)? = null
    var pressed = false
        private set
    /** 現在の開度 0..1 */
    var value = 0f
        private set
    private var target = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = textPaint(this, 11f, Pal.TEXT, bold = true)
    private val r = RectF()

    init { minimumHeight = dp(78f).toInt(); minimumWidth = dp(60f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    /** 開度を目標へ近づける (踏み込み 3/s, 戻し 5/s) */
    fun advance(dt: Float) {
        val rate = if (target > value) 3f else 5f
        val nv = if (target > value) minOf(target, value + rate * dt) else maxOf(target, value - rate * dt)
        if (nv != value) { value = nv; onChange?.invoke(value); invalidate() }
    }

    override fun onDraw(c: Canvas) {
        r.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        p.style = Paint.Style.FILL; p.color = Pal.PANEL_HI
        c.drawRoundRect(r, dp(8f), dp(8f), p)
        // 踏み込み量バー
        p.color = accent; p.alpha = 150
        val fillTop = r.bottom - r.height() * value
        c.drawRoundRect(r.left, fillTop, r.right, r.bottom, dp(8f), dp(8f), p)
        p.alpha = 255
        // 滑り止めの溝
        p.color = Pal.BORDER; p.strokeWidth = dp(2f)
        for (i in 1..5) {
            val y = r.top + r.height() * i / 6f
            c.drawLine(r.left + dp(10f), y, r.right - dp(10f), y, p)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f); p.color = if (pressed) accent else Pal.BORDER
        c.drawRoundRect(r, dp(8f), dp(8f), p)
        val t = "$label ${(value * 100).toInt()}%"
        c.drawText(t, (width - txt.measureText(t)) / 2, dp(16f), txt)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                pressed = true
                target = (0.3f + 0.85f * (e.y / height)).coerceIn(0.3f, 1f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { pressed = false; target = 0f }
        }
        invalidate()
        return true
    }
}

/** − 値 + の数値ステッパ */
@SuppressLint("ClickableViewAccessibility")
class NumberStepper(context: Context) : View(context) {
    var label = ""
    var min = 0f
    var max = 100f
    var step = 1f
    var format: (Float) -> String = { "%.0f".format(it) }
    var onChange: ((Float) -> Unit)? = null
    var value = 0f
        set(v) { field = v.coerceIn(min, max); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lab = textPaint(this, 9f, Pal.DIM)
    private val txt = textPaint(this, 13f, Pal.TEXT, bold = true, mono = true)
    private val btn = textPaint(this, 16f, Pal.AMBER, bold = true)
    private val r = RectF()
    private var repeatJob: Runnable? = null

    init { minimumHeight = dp(52f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        c.drawText(label, dp(4f), dp(11f), lab)
        val top = dp(15f)
        val bw = height - top
        r.set(0f, top, width.toFloat(), height.toFloat())
        p.color = Pal.PANEL_HI; c.drawRoundRect(r, dp(4f), dp(4f), p)
        p.color = Pal.BORDER
        c.drawRect(bw, top, bw + dp(1f), height.toFloat(), p)
        c.drawRect(width - bw, top, width - bw + dp(1f), height.toFloat(), p)
        c.drawText("−", bw / 2 - btn.measureText("−") / 2, top + bw * 0.68f, btn)
        c.drawText("+", width - bw / 2 - btn.measureText("+") / 2, top + bw * 0.68f, btn)
        val t = format(value)
        c.drawText(t, width / 2f - txt.measureText(t) / 2, top + bw * 0.68f, txt)
    }

    private fun bump(dir: Int) {
        value += dir * step
        onChange?.invoke(value)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val top = dp(15f)
        val bw = height - top
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val dir = when { e.x < bw -> -1; e.x > width - bw -> 1; else -> 0 }
                if (dir != 0) {
                    bump(dir)
                    // 長押しで連続変化
                    val job = object : Runnable {
                        override fun run() { bump(dir); postDelayed(this, 70) }
                    }
                    repeatJob = job
                    postDelayed(job, 400)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { repeatJob?.let { removeCallbacks(it) }; repeatJob = null }
        }
        return true
    }
}
