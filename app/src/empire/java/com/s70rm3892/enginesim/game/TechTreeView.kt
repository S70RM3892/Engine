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
 * エンジン解放ツリー (スキルツリー型)。列 = 深さ、行 = 系統 (直列/V型/星型…)。
 * ドラッグでパン、タップでノード選択。どの系統から伸ばすかはプレイヤーが決める。
 */
@SuppressLint("ClickableViewAccessibility")
class TechTreeView(context: Context) : View(context) {
    enum class NodeState { OWNED, AVAILABLE, LOCKED }

    var state: GameState? = null
    var currentKey = ""
    var selectedKey: String? = null
        set(v) { field = v; invalidate() }
    var onSelect: ((GameEngineDef) -> Unit)? = null

    private val colW = dp(150f)
    private val rowH = dp(50f)
    private val nodeW = dp(124f)
    private val nodeH = dp(40f)
    private val left = dp(96f)     // 系統ラベル列
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

    private val maxDepth = GameRoster.engines.maxOf { it.depth }
    val contentW get() = left + colW * (maxDepth + 1) + dp(24f)
    val contentH get() = top * 2 + rowH * GameRoster.lanes.size

    fun nodeState(d: GameEngineDef): NodeState {
        val s = state ?: return NodeState.LOCKED
        return when {
            d.key in s.unlocked -> NodeState.OWNED
            s.canUnlock(d) -> NodeState.AVAILABLE
            else -> NodeState.LOCKED
        }
    }

    private fun cx(d: GameEngineDef) = left + colW * d.depth + nodeW / 2
    private fun cy(d: GameEngineDef) = top + rowH * d.lane + rowH / 2

    /** 指定ノードが画面中央に来るようにスクロール */
    fun centerOn(key: String) {
        val d = GameRoster.byKey(key)
        if (width == 0) { post { centerOn(key) }; return }
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
        c.drawColor(Color.argb(235, 10, 12, 16))
        c.save()
        c.translate(panX, panY)
        // 系統の帯
        for ((i, name) in GameRoster.lanes.withIndex()) {
            val y0 = top + rowH * i
            if (i % 2 == 0) {
                p.style = Paint.Style.FILL
                p.color = Color.argb(40, 60, 70, 90)
                c.drawRect(0f, y0, contentW, y0 + rowH, p)
            }
        }
        // 接続線 (親 → 子)
        for (d in GameRoster.engines) for (pk in d.parents) {
            val parent = GameRoster.byKey(pk)
            val ps = nodeState(parent)
            val cs = nodeState(d)
            line.strokeWidth = dp(if (ps == NodeState.OWNED && cs != NodeState.LOCKED) 3f else 2f)
            line.color = when {
                ps == NodeState.OWNED && cs == NodeState.OWNED -> Pal.GREEN
                ps == NodeState.OWNED -> Pal.AMBER
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
        // ノード
        val s = state
        for (d in GameRoster.engines) {
            val st = nodeState(d)
            val x = cx(d) - nodeW / 2
            val y = cy(d) - nodeH / 2
            r.set(x, y, x + nodeW, y + nodeH)
            val affordable = st == NodeState.AVAILABLE && s != null && s.money >= d.price
            p.style = Paint.Style.FILL
            p.color = when (st) {
                NodeState.OWNED -> Color.rgb(28, 52, 36)
                NodeState.AVAILABLE -> Color.rgb(48, 40, 22)
                NodeState.LOCKED -> Color.rgb(26, 28, 34)
            }
            c.drawRoundRect(r, dp(8f), dp(8f), p)
            if (affordable) {
                // 買えるノードは脈動するグロー
                val a = (0.35f + 0.35f * sin(time * 5f)).coerceIn(0f, 1f)
                p.color = (Pal.AMBER and 0xFFFFFF) or ((a * 110).toInt() shl 24)
                c.drawRoundRect(r, dp(8f), dp(8f), p)
            }
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(if (d.key == selectedKey) 3f else 1.5f)
            p.color = when {
                d.key == selectedKey -> Color.WHITE
                d.key == currentKey -> Pal.CYAN
                st == NodeState.OWNED -> Pal.GREEN
                affordable -> Pal.AMBER
                st == NodeState.AVAILABLE -> Color.rgb(150, 120, 60)
                else -> Pal.BORDER
            }
            c.drawRoundRect(r, dp(8f), dp(8f), p)
            tName.color = if (st == NodeState.LOCKED) Pal.DIM else Pal.TEXT
            c.drawText(ellipsize(d.name, tName, nodeW - dp(12f)), x + dp(6f), y + dp(16f), tName)
            tSub.color = when (st) {
                NodeState.OWNED -> if (d.key == currentKey) Pal.CYAN else Pal.GREEN
                NodeState.AVAILABLE -> if (affordable) Pal.AMBER else Color.rgb(150, 120, 60)
                NodeState.LOCKED -> Pal.DIM
            }
            val sub = when {
                d.key == currentKey -> "稼働中"
                st == NodeState.OWNED -> "所持"
                else -> "¥" + fmtMoney(d.price)
            }
            c.drawText(sub, x + dp(6f), y + dp(32f), tSub)
        }
        c.restore()
        // 系統ラベルは左端に固定表示 (ノードの上に半透明の帯)
        for ((i, name) in GameRoster.lanes.withIndex()) {
            val yc = panY + top + rowH * i + rowH / 2
            if (yc < -rowH || yc > height + rowH) continue
            val tw = tLane.measureText(name)
            p.style = Paint.Style.FILL
            p.color = Color.argb(200, 10, 12, 16)
            r.set(0f, yc - dp(10f), tw + dp(14f), yc + dp(10f))
            c.drawRoundRect(r, dp(4f), dp(4f), p)
            c.drawText(name, dp(7f), yc + dp(4f), tLane)
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
            MotionEvent.ACTION_UP -> if (!dragging) hit(e.x - panX, e.y - panY)?.let { selectedKey = it.key; onSelect?.invoke(it) }
        }
        return true
    }

    private fun hit(x: Float, y: Float): GameEngineDef? = GameRoster.engines.firstOrNull {
        abs(x - cx(it)) <= nodeW / 2 && abs(y - cy(it)) <= nodeH / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }
}
