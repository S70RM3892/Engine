package com.s70rm3892.enginesim.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * スキルツリー型のノードグラフ (強化ボードとエンジンツリーで共用)。列 = 深さ、行 = 系統。
 * ドラッグでパン、タップでノード選択。
 */
@SuppressLint("ClickableViewAccessibility")
class NodeGraphView(context: Context) : View(context) {
    enum class State { OWNED, MAXED, AVAILABLE, LOCKED }

    data class Item(
        val id: String,
        val title: String,
        val sub: String,
        val lane: Int,
        val depth: Int,
        val parents: List<String>,
        val state: State,
        val affordable: Boolean,
        val current: Boolean = false,
        val accent: Int = Pal.AMBER,
        val progress: Float = -1f,   // 0..1 でレベルバー
    )

    var lanes: List<String> = emptyList()
    var laneLocked: (Int) -> String? = { null }     // 閉じている系統の理由 (工房名など)
    var items: List<Item> = emptyList()
        set(v) { field = v; byId = v.associateBy { it.id }; invalidate() }
    private var byId: Map<String, Item> = emptyMap()
    var selectedId: String? = null
        set(v) { field = v; invalidate() }
    var onSelect: ((Item) -> Unit)? = null

    private val colW = dp(150f)
    private val rowH = dp(52f)
    private val nodeW = dp(128f)
    private val nodeH = dp(42f)
    private val left = dp(96f)
    private val top = dp(6f)

    private var panX = 0f
    private var panY = 0f
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var time = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tName = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = dp(12f) }
    private val tSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = dp(10f) }
    private val tLane = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = dp(12f); color = Pal.DIM }
    private val r = RectF()
    private val path = Path()

    val contentW get() = left + colW * ((items.maxOfOrNull { it.depth } ?: 0) + 1) + dp(24f)
    val contentH get() = top * 2 + rowH * lanes.size

    private fun cx(d: Item) = left + colW * d.depth + nodeW / 2
    private fun cy(d: Item) = top + rowH * d.lane + rowH / 2

    fun centerOn(id: String) {
        val d = byId[id] ?: return
        if (width == 0) { post { centerOn(id) }; return }
        panX = width / 2f - cx(d)
        panY = height / 2f - cy(d)
        clampPan()
        invalidate()
    }

    private fun clampPan() {
        panX = panX.coerceIn(min(0f, width - contentW), 0f)
        panY = panY.coerceIn(min(0f, height - contentH), 0f)
    }

    fun advance(dt: Float) {
        time += dt
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(10, 12, 16))
        c.save()
        c.translate(panX, panY)
        for (i in lanes.indices) {
            val y0 = top + rowH * i
            p.style = Paint.Style.FILL
            p.color = when {
                laneLocked(i) != null -> Color.argb(90, 50, 20, 20)
                i % 2 == 0 -> Color.argb(40, 60, 70, 90)
                else -> Color.TRANSPARENT
            }
            c.drawRect(0f, y0, contentW, y0 + rowH, p)
        }
        for (d in items) for (pk in d.parents) {
            val parent = byId[pk] ?: continue
            val ps = parent.state
            val owned = ps == State.OWNED || ps == State.MAXED
            line.strokeWidth = dp(if (owned && d.state != State.LOCKED) 3f else 2f)
            line.color = when {
                owned && (d.state == State.OWNED || d.state == State.MAXED) -> Pal.GREEN
                owned -> Pal.AMBER
                else -> Color.argb(110, 90, 96, 110)
            }
            val x0 = cx(parent) + nodeW / 2
            val y0 = cy(parent)
            val x1 = cx(d) - nodeW / 2
            val y1 = cy(d)
            path.reset()
            path.moveTo(x0, y0)
            val mx = (x0 + x1) / 2
            path.cubicTo(max(mx, x0 + dp(20f)), y0, min(mx, x1 - dp(20f)), y1, x1, y1)
            c.drawPath(path, line)
        }
        for (d in items) {
            val x = cx(d) - nodeW / 2
            val y = cy(d) - nodeH / 2
            r.set(x, y, x + nodeW, y + nodeH)
            p.style = Paint.Style.FILL
            p.color = when (d.state) {
                State.MAXED -> Color.rgb(22, 44, 30)
                State.OWNED -> Color.rgb(28, 52, 36)
                State.AVAILABLE -> Color.rgb(48, 40, 22)
                State.LOCKED -> Color.rgb(26, 28, 34)
            }
            c.drawRoundRect(r, dp(8f), dp(8f), p)
            if (d.affordable) {
                val a = (0.35f + 0.35f * sin(time * 5f)).coerceIn(0f, 1f)
                p.color = (d.accent and 0xFFFFFF) or ((a * 110).toInt() shl 24)
                c.drawRoundRect(r, dp(8f), dp(8f), p)
            }
            if (d.progress >= 0f) {
                p.color = Color.argb(160, 80, 210, 120)
                c.drawRect(x + dp(6f), y + nodeH - dp(6f), x + dp(6f) + (nodeW - dp(12f)) * d.progress, y + nodeH - dp(3f), p)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(if (d.id == selectedId) 3f else 1.5f)
            p.color = when {
                d.id == selectedId -> Color.WHITE
                d.current -> Pal.CYAN
                d.state == State.OWNED || d.state == State.MAXED -> Pal.GREEN
                d.affordable -> d.accent
                d.state == State.AVAILABLE -> Color.rgb(150, 120, 60)
                else -> Pal.BORDER
            }
            c.drawRoundRect(r, dp(8f), dp(8f), p)
            tName.color = if (d.state == State.LOCKED) Pal.DIM else Pal.TEXT
            c.drawText(ellipsize(d.title, tName, nodeW - dp(12f)), x + dp(6f), y + dp(16f), tName)
            tSub.color = when {
                d.current -> Pal.CYAN
                d.state == State.MAXED -> Pal.GREEN
                d.affordable -> d.accent
                d.state == State.LOCKED -> Pal.DIM
                else -> Color.rgb(170, 140, 80)
            }
            c.drawText(ellipsize(d.sub, tSub, nodeW - dp(12f)), x + dp(6f), y + dp(31f), tSub)
        }
        c.restore()
        // 系統ラベル (左端に固定)
        for ((i, name) in lanes.withIndex()) {
            val yc = panY + top + rowH * i + rowH / 2
            if (yc < -rowH || yc > height + rowH) continue
            val lock = laneLocked(i)
            val label = if (lock != null) "🔒$name" else name
            val tw = tLane.measureText(label)
            p.style = Paint.Style.FILL
            p.color = Color.argb(210, 10, 12, 16)
            r.set(0f, yc - dp(10f), tw + dp(14f), yc + dp(10f))
            c.drawRoundRect(r, dp(4f), dp(4f), p)
            tLane.color = if (lock != null) Color.rgb(200, 110, 100) else Pal.DIM
            c.drawText(label, dp(7f), yc + dp(4f), tLane)
        }
    }

    private fun ellipsize(t: String, paint: Paint, w: Float): String {
        if (paint.measureText(t) <= w) return t
        var n = t.length
        while (n > 1 && paint.measureText(t.substring(0, n) + "…") > w) n--
        return t.substring(0, n) + "…"
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(e.x - downX) > dp(8f) || abs(e.y - downY) > dp(8f))) dragging = true
                if (dragging) {
                    panX += e.x - lastX
                    panY += e.y - lastY
                    clampPan()
                    invalidate()
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> if (!dragging) hit(e.x - panX, e.y - panY)?.let { selectedId = it.id; onSelect?.invoke(it) }
        }
        return true
    }

    private fun hit(x: Float, y: Float): Item? = items.firstOrNull { abs(x - cx(it)) <= nodeW / 2 && abs(y - cy(it)) <= nodeH / 2 }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }
}
