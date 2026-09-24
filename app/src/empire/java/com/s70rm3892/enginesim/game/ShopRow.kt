package com.s70rm3892.enginesim.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.dp
import kotlin.math.max

/** ショップの 1 行 (タイトル / 説明 / 価格 / レベルバー)。購入時に光る。 */
@SuppressLint("ClickableViewAccessibility")
class ShopRow(context: Context) : View(context) {
    var title = ""
    var subtitle = ""
    var price = ""
    var accent = Pal.AMBER
    var level = 0
    var maxLevel = 0
    var affordable = false
    var current = false
    var locked = false
    var onClick: (() -> Unit)? = null

    private var down = false
    private var glow = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = dp(13f) }
    private val tSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10.5f) }
    private val tPrice = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textSize = dp(13f); textAlign = Paint.Align.RIGHT }
    private val r = RectF()

    init { minimumHeight = dp(54f).toInt() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), resolveSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    fun celebrate() { glow = 1f; invalidate() }

    fun refresh() = invalidate()

    override fun onDraw(c: Canvas) {
        val sc = if (down) 0.97f else 1f
        c.save()
        c.scale(sc, sc, width / 2f, height / 2f)
        r.set(dp(3f), dp(3f), width - dp(3f), height - dp(3f))
        val base = when {
            current -> Pop.darker(Pop.MINT, 0.55f)
            affordable -> Pop.CARD_HI
            else -> Pop.CARD
        }
        // カード (下に厚み)
        p.style = Paint.Style.FILL
        p.shader = null
        p.color = Pop.darker(base, 0.45f)
        c.drawRoundRect(r.left, r.top + dp(3f), r.right, r.bottom, dp(14f), dp(14f), p)
        p.shader = android.graphics.LinearGradient(0f, r.top, 0f, r.bottom, Pop.lighter(base, 0.12f), base, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(r.left, r.top, r.right, r.bottom - dp(3f), dp(14f), dp(14f), p)
        p.shader = null
        if (glow > 0) {
            p.color = (accent and 0xFFFFFF) or ((glow * 150).toInt() shl 24)
            c.drawRoundRect(r, dp(14f), dp(14f), p)
            glow = max(0f, glow - 0.05f)
            postInvalidateOnAnimation()
        }
        // 左の色帯
        p.color = when { current -> Pop.MINT; affordable -> accent; else -> Pop.darker(accent, 0.5f) }
        c.drawRoundRect(r.left, r.top, r.left + dp(7f), r.bottom - dp(3f), dp(4f), dp(4f), p)

        val left = dp(16f)
        tTitle.color = if (locked && !affordable) Pop.SUB else Color.WHITE
        c.drawText(title, left, dp(22f), tTitle)
        tSub.color = Pop.SUB
        var sub = subtitle
        val maxW = width - left - dp(110f)
        while (sub.length > 2 && tSub.measureText(sub) > maxW) sub = sub.dropLast(2)
        if (sub != subtitle) sub += "…"
        c.drawText(sub, left, dp(38f), tSub)
        // 価格のカプセル
        val pw = tPrice.measureText(price) + dp(18f)
        val pr = android.graphics.RectF(width - dp(10f) - pw, dp(9f), width - dp(10f), dp(33f))
        p.color = if (affordable) accent else Pop.darker(Pop.CARD, 0.3f)
        c.drawRoundRect(pr, dp(12f), dp(12f), p)
        tPrice.color = if (affordable) Color.WHITE else Pop.SUB
        c.drawText(price, pr.right - dp(9f), pr.bottom - dp(7f), tPrice)
        if (maxLevel > 0) {
            val bw = (width - left * 2)
            val seg = bw / maxLevel
            for (i in 0 until maxLevel) {
                p.color = if (i < level) Pop.YELLOW else Color.argb(90, 200, 190, 240)
                c.drawRoundRect(left + i * seg + dp(1.5f), height - dp(13f), left + (i + 1) * seg - dp(1.5f), height - dp(8f), dp(3f), dp(3f), p)
            }
        }
        c.restore()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (down && e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) onClick?.invoke()
                down = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}
