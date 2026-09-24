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
        r.set(dp(2f), dp(2f), width - dp(2f), height - dp(2f))
        p.style = Paint.Style.FILL
        p.color = when {
            down -> Pal.BORDER
            current -> Color.rgb(40, 52, 44)
            else -> Pal.PANEL_HI
        }
        c.drawRoundRect(r, dp(6f), dp(6f), p)
        if (glow > 0) {
            p.color = (accent and 0xFFFFFF) or ((glow * 140).toInt() shl 24)
            c.drawRoundRect(r, dp(6f), dp(6f), p)
            glow = max(0f, glow - 0.05f)
            postInvalidateOnAnimation()
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(1.5f)
        p.color = when {
            current -> Pal.GREEN
            affordable -> accent
            else -> Pal.BORDER
        }
        c.drawRoundRect(r, dp(6f), dp(6f), p)

        val left = dp(10f)
        tTitle.color = if (locked && !affordable) Pal.DIM else Pal.TEXT
        c.drawText(title, left, dp(21f), tTitle)
        tSub.color = Pal.DIM
        c.drawText(subtitle, left, dp(37f), tSub)
        tPrice.color = if (affordable) accent else Pal.DIM
        c.drawText(price, width - dp(10f), dp(21f), tPrice)
        if (maxLevel > 0) {
            val bw = (width - left * 2)
            val seg = bw / maxLevel
            p.style = Paint.Style.FILL
            for (i in 0 until maxLevel) {
                p.color = if (i < level) accent else Color.argb(90, 90, 96, 110)
                c.drawRect(left + i * seg + dp(1f), height - dp(11f), left + (i + 1) * seg - dp(1f), height - dp(7f), p)
            }
        }
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
