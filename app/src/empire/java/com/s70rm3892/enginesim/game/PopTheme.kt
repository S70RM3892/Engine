package com.s70rm3892.enginesim.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.s70rm3892.enginesim.ui.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** ポップな配色 (ゲーム専用) */
object Pop {
    val BG_TOP = Color.rgb(58, 30, 110)
    val BG_BOTTOM = Color.rgb(16, 26, 70)
    val CARD = Color.rgb(52, 38, 112)
    val CARD_HI = Color.rgb(74, 56, 150)
    val INK = Color.rgb(22, 12, 48)
    val PINK = Color.rgb(255, 95, 162)
    val YELLOW = Color.rgb(255, 210, 63)
    val CYAN = Color.rgb(61, 224, 255)
    val MINT = Color.rgb(75, 232, 168)
    val ORANGE = Color.rgb(255, 138, 61)
    val PURPLE = Color.rgb(155, 107, 255)
    val RED = Color.rgb(255, 84, 96)
    val TEXT = Color.WHITE
    val SUB = Color.rgb(200, 190, 240)

    fun lighter(c: Int, k: Float = 0.35f) = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * k).toInt(), (Color.green(c) + (255 - Color.green(c)) * k).toInt(),
        (Color.blue(c) + (255 - Color.blue(c)) * k).toInt())

    fun darker(c: Int, k: Float = 0.35f) = Color.rgb((Color.red(c) * (1 - k)).toInt(), (Color.green(c) * (1 - k)).toInt(), (Color.blue(c) * (1 - k)).toInt())

    /** 縁取り文字 (インクの太い縁 + 塗り) */
    fun outlined(c: Canvas, text: String, x: Float, y: Float, p: Paint, fill: Int, stroke: Int = INK, width: Float) {
        val st = p.style
        val col = p.color
        p.style = Paint.Style.STROKE
        p.strokeWidth = width
        p.strokeJoin = Paint.Join.ROUND
        p.color = stroke
        c.drawText(text, x, y, p)
        p.style = Paint.Style.FILL
        p.color = fill
        c.drawText(text, x, y, p)
        p.style = st
        p.color = col
    }

    /** グラデーションのカプセル (下に厚み) */
    fun pill(c: Canvas, r: RectF, color: Int, p: Paint, radius: Float, depth: Float, pressed: Boolean = false) {
        val d = if (pressed) depth * 0.3f else depth
        p.shader = null
        p.style = Paint.Style.FILL
        p.color = darker(color, 0.45f)
        c.drawRoundRect(r.left, r.top + d, r.right, r.bottom, radius, radius, p)
        val top = r.top + (depth - d)
        p.shader = LinearGradient(0f, top, 0f, r.bottom - d, lighter(color, 0.25f), color, Shader.TileMode.CLAMP)
        c.drawRoundRect(r.left, top, r.right, r.bottom - d, radius, radius, p)
        p.shader = null
        // ハイライト
        p.color = Color.argb(70, 255, 255, 255)
        c.drawRoundRect(r.left + radius * 0.4f, top + depth * 0.4f, r.right - radius * 0.4f, top + (r.height() - depth) * 0.38f, radius, radius, p)
    }
}

/** ポップなボタン (KeyButton と同じ使い方) */
@SuppressLint("ClickableViewAccessibility")
class PopButton(context: Context, var text: String, private val toggle: Boolean = false) : View(context) {
    var accent = Pop.PINK
    var onPress: (() -> Unit)? = null
    var onRelease: (() -> Unit)? = null
    var onToggle: ((Boolean) -> Unit)? = null
    var on = false
        set(v) { field = v; invalidate() }
    private var down = false
    private var bounce = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val r = RectF()

    init { minimumHeight = dp(34f).toInt(); minimumWidth = dp(48f).toInt() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), resolveSize(suggestedMinimumHeight, h))
    }

    override fun onDraw(c: Canvas) {
        val s = 1f - 0.06f * bounce
        c.save()
        c.scale(s, s, width / 2f, height / 2f)
        r.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        val col = if (toggle && !on) Pop.CARD_HI else accent
        Pop.pill(c, r, col, p, min(r.height() / 2, dp(18f)), dp(4f), down)
        t.textSize = min(dp(14f), r.height() * 0.42f)
        var label = text
        while (label.length > 2 && t.measureText(label) > r.width() - dp(12f)) label = label.dropLast(1)
        if (label != text) label = label.dropLast(1) + "…"
        val y = r.centerY() + t.textSize * 0.35f + (if (down) dp(1.5f) else -dp(1f))
        Pop.outlined(c, label, r.centerX(), y, t, if (toggle && !on) Pop.SUB else Color.WHITE, Pop.darker(col, 0.55f), dp(3.5f))
        c.restore()
        if (bounce > 0) { bounce = max(0f, bounce - 0.12f); postInvalidateOnAnimation() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; bounce = 1f; onPress?.invoke(); invalidate() }
            MotionEvent.ACTION_UP -> {
                if (down) {
                    if (toggle) { on = !on; onToggle?.invoke(on) }
                    onRelease?.invoke()
                }
                down = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}

/** ポップなタブ (SegmentedSelector と同じ使い方) */
@SuppressLint("ClickableViewAccessibility")
class PopTabs(context: Context, items: List<String>) : View(context) {
    var items: List<String> = items
        set(v) { field = v; invalidate() }
    var accent = Pop.PINK
    var label = ""
    var onSelect: ((Int) -> Unit)? = null
    var selected = 0
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val r = RectF()

    init { minimumHeight = dp(38f).toInt() }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, w), resolveSize(suggestedMinimumHeight, h))
    }

    override fun onDraw(c: Canvas) {
        if (items.isEmpty()) return
        r.set(0f, 0f, width.toFloat(), height.toFloat())
        p.style = Paint.Style.FILL
        p.color = Pop.darker(Pop.CARD, 0.35f)
        c.drawRoundRect(r, height / 2f, height / 2f, p)
        val w = width.toFloat() / items.size
        for ((i, s) in items.withIndex()) {
            r.set(i * w + dp(3f), dp(3f), (i + 1) * w - dp(3f), height - dp(3f))
            if (i == selected) Pop.pill(c, r, accent, p, r.height() / 2, dp(3f))
            t.textSize = min(dp(12.5f), r.height() * 0.42f)
            var label = s
            while (label.length > 2 && t.measureText(label) > r.width() - dp(8f)) label = label.dropLast(1)
            val y = r.centerY() + t.textSize * 0.35f
            if (i == selected) Pop.outlined(c, label, r.centerX(), y, t, Color.WHITE, Pop.darker(accent, 0.55f), dp(3f))
            else { t.color = Pop.SUB; c.drawText(label, r.centerX(), y, t) }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP && items.isNotEmpty()) {
            val i = (e.x / (width.toFloat() / items.size)).toInt().coerceIn(0, items.size - 1)
            if (i != selected) { selected = i; onSelect?.invoke(i) }
        }
        return true
    }
}

/** ポップなアクセルペダル (押した位置が深いほど踏み込み)。PedalButton と同じ使い方 */
@SuppressLint("ClickableViewAccessibility")
class PopPedal(context: Context) : View(context) {
    var label = "踏め!"
    var accent = Pop.ORANGE
    var pressed = false
        private set
    var value = 0f
        private set
    private var target = 0f
    private var time = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val path = Path()

    fun advance(dt: Float) {
        time += dt
        val rate = if (target > value) 3f else 5f
        value = if (target > value) min(target, value + rate * dt) else max(target, value - rate * dt)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        r.set(dp(3f), dp(3f), width - dp(3f), height - dp(3f))
        val rad = dp(22f)
        Pop.pill(c, r, Pop.CARD_HI, p, rad, dp(6f), pressed)
        // 踏み込み量 (下から満ちる)
        val inner = RectF(r.left + dp(6f), r.top + dp(8f), r.right - dp(6f), r.bottom - dp(12f))
        val top = inner.bottom - inner.height() * value
        if (value > 0.01f) {
            p.shader = LinearGradient(0f, inner.bottom, 0f, inner.top, Pop.YELLOW, accent, Shader.TileMode.CLAMP)
            c.drawRoundRect(inner.left, top, inner.right, inner.bottom, rad * 0.7f, rad * 0.7f, p)
            p.shader = null
        }
        // 上向きの矢印 (押すと脈動)
        val pulse = if (pressed) 0f else 0.5f + 0.5f * sin(time * 5f)
        p.color = Color.argb((120 + 100 * pulse).toInt(), 255, 255, 255)
        for (k in 0 until 3) {
            val cy = inner.centerY() + dp(18f) - k * dp(16f)
            path.reset()
            path.moveTo(inner.centerX() - dp(14f), cy)
            path.lineTo(inner.centerX(), cy - dp(10f))
            path.lineTo(inner.centerX() + dp(14f), cy)
            path.lineTo(inner.centerX() + dp(14f), cy + dp(5f))
            path.lineTo(inner.centerX(), cy - dp(5f))
            path.lineTo(inner.centerX() - dp(14f), cy + dp(5f))
            path.close()
            c.drawPath(path, p)
        }
        t.textSize = dp(15f)
        Pop.outlined(c, label, r.centerX(), r.top + dp(24f), t, Color.WHITE, Pop.INK, dp(4f))
        t.textSize = dp(13f)
        Pop.outlined(c, "${(value * 100).toInt()}%", r.centerX(), r.bottom - dp(18f), t, Pop.YELLOW, Pop.INK, dp(3.5f))
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

/** 夜空の背景 (紫 → 紺のグラデーション + またたく星 + ゆっくり昇る泡) */
class NightSkyView(context: Context) : View(context) {
    private class Star(val x: Float, val y: Float, val r: Float, val ph: Float)
    private class Bubble(var x: Float, var y: Float, val r: Float, val v: Float, val col: Int)
    private val stars = ArrayList<Star>()
    private val bubbles = ArrayList<Bubble>()
    private val rnd = Random(3)
    private var time = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun advance(dt: Float) {
        time += dt
        for (b in bubbles) { b.y -= b.v * dt; if (b.y < -b.r) b.y = height + b.r }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        stars.clear()
        bubbles.clear()
        repeat(70) { stars += Star(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.85f, dp(0.8f + rnd.nextFloat() * 1.6f), rnd.nextFloat() * 6.28f) }
        val cols = intArrayOf(Pop.PINK, Pop.CYAN, Pop.PURPLE, Pop.YELLOW)
        repeat(10) { bubbles += Bubble(rnd.nextFloat() * w, rnd.nextFloat() * h, dp(14f + rnd.nextFloat() * 40f), dp(6f + rnd.nextFloat() * 14f), cols[it % 4]) }
    }

    override fun onDraw(c: Canvas) {
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Pop.BG_TOP, Pop.BG_BOTTOM, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        p.shader = null
        for (b in bubbles) {
            p.color = (b.col and 0xFFFFFF) or 0x1E000000
            c.drawCircle(b.x, b.y, b.r, p)
        }
        for (s in stars) {
            val a = 0.35f + 0.65f * (0.5f + 0.5f * sin(time * 2f + s.ph))
            p.color = Color.argb((a * 255).toInt(), 255, 250, 220)
            c.drawCircle(s.x, s.y, s.r, p)
        }
    }
}
