package com.s70rm3892.enginesim.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.dp
import kotlin.math.cos
import kotlin.math.sin

/** 丸いニトロボタン: ゲージが円周に溜まり、満タンで脈動。発動中は残り時間を表示 */
@SuppressLint("ClickableViewAccessibility")
class NitroButton(context: Context) : View(context) {
    var level = 0f          // 0..1
    var active = 0f         // 発動中の残り割合 (0 = 未発動)
    var onFire: (() -> Unit)? = null
    private var time = 0f
    private var down = false
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val r = RectF()

    fun advance(dt: Float) { time += dt; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rad = minOf(cx, cy) - dp(6f)
        val ready = level >= 1f && active <= 0f
        if (ready || active > 0f) {
            // 外側のグロー
            val g = if (active > 0f) 1f else 0.55f + 0.45f * sin(time * 7f)
            p.shader = RadialGradient(cx, cy, rad * 1.35f, intArrayOf(Color.argb((g * 170).toInt(), 90, 160, 255), 0),
                floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP)
            p.style = Paint.Style.FILL
            c.drawCircle(cx, cy, rad * 1.35f, p)
            p.shader = null
        }
        p.style = Paint.Style.FILL
        p.color = when {
            down -> Color.rgb(40, 60, 110)
            active > 0f -> Color.rgb(40, 80, 170)
            ready -> Color.rgb(34, 60, 130)
            else -> Color.rgb(24, 28, 38)
        }
        c.drawCircle(cx, cy, rad, p)
        // ゲージ (円周)
        p.style = Paint.Style.STROKE
        p.strokeWidth = dp(6f)
        p.strokeCap = Paint.Cap.ROUND
        p.color = Color.argb(120, 60, 70, 90)
        r.set(cx - rad + dp(4f), cy - rad + dp(4f), cx + rad - dp(4f), cy + rad - dp(4f))
        c.drawArc(r, 0f, 360f, false, p)
        p.color = if (active > 0f) Color.rgb(170, 210, 255) else Color.rgb(90, 160, 255)
        c.drawArc(r, -90f, 360f * (if (active > 0f) active else level).coerceIn(0f, 1f), false, p)
        t.color = if (ready || active > 0f) Color.WHITE else Pal.DIM
        t.textSize = rad * 0.34f
        c.drawText("NITRO", cx, cy + t.textSize * 0.1f, t)
        t.textSize = rad * 0.24f
        t.color = if (ready) Color.rgb(170, 220, 255) else Pal.DIM
        c.drawText(if (active > 0f) "ON" else if (ready) "READY!" else "${(level * 100).toInt()}%", cx, cy + rad * 0.45f, t)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { if (down) onFire?.invoke(); down = false; invalidate() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }
}

/** 金のボルト: ふわふわ浮いて回る。タップで回収 */
@SuppressLint("ClickableViewAccessibility")
class GoldenBoltView(context: Context) : View(context) {
    var life = 0f           // 残り割合 (点滅に使う)
    var onTap: (() -> Unit)? = null
    private var time = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    fun advance(dt: Float) { time += dt; invalidate() }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f + dp(4f) * sin(time * 4f)
        val rad = minOf(width, height) / 2f - dp(10f)
        // 残り少ないと点滅
        if (life < 0.3f && (time * 10).toInt() % 2 == 0) return
        p.shader = RadialGradient(cx, cy, rad * 1.6f, intArrayOf(Color.argb(170, 255, 210, 60), 0), floatArrayOf(0.4f, 1f), Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawCircle(cx, cy, rad * 1.6f, p)
        p.shader = null
        // 光線
        p.color = Color.argb(90, 255, 240, 170)
        for (i in 0 until 8) {
            val a = time * 1.5f + i * Math.PI.toFloat() / 4
            path.reset()
            path.moveTo(cx, cy)
            path.lineTo(cx + rad * 1.9f * cos(a - 0.08f), cy + rad * 1.9f * sin(a - 0.08f))
            path.lineTo(cx + rad * 1.9f * cos(a + 0.08f), cy + rad * 1.9f * sin(a + 0.08f))
            path.close()
            c.drawPath(path, p)
        }
        // 六角ボルトの頭
        path.reset()
        val rot = time * 2f
        for (i in 0 until 6) {
            val a = rot + i * Math.PI.toFloat() / 3
            val x = cx + rad * cos(a)
            val y = cy + rad * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.color = Color.rgb(255, 196, 40)
        c.drawPath(path, p)
        p.color = Color.rgb(200, 140, 20)
        c.drawCircle(cx, cy, rad * 0.45f, p)
        p.color = Color.rgb(255, 245, 190)
        c.drawCircle(cx - rad * 0.3f, cy - rad * 0.35f, rad * 0.18f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) onTap?.invoke()
        return true
    }
}
