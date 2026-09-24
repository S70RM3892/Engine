package com.s70rm3892.enginesim.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * ゲーム HUD (3D ビューポートの上に重ねる描画専用ビュー。タッチは下のボタンへ素通しする)。
 * 所持金カウンタ・収入/秒・タコメータ (スイートゾーン帯)・コンボ・熱ゲージ・浮遊テキスト・紙吹雪・
 * ビネット・中央ポップアップ・ドラッグレース表示を描く。
 */
class GameHudView(context: Context) : View(context) {
    // --- 表示データ (コントローラが毎フレーム設定) ---
    var money = 0.0
    var incomePerSec = 0.0
    var multiplier = 1.0
    var engineName = ""
    var traits: List<Pair<String, Int>> = emptyList()
    var rpm = 0f
    var redline = 7000f
    var idle = 800f
    var sweetLo = GameRules.SWEET_LO.toFloat()
    var sweetHi = GameRules.SWEET_HI.toFloat()
    var combo = 1f
    var heat = 0f
    var overheat = 0f
    var limiter = false
    var powerKw = 0f
    var fuelKw = 0f
    var efficiency = 0f
    var boostBar = 0f
    var isTurbine = false
    var thrustKn = 0f
    var autoLevel = 0
    // 1 日 (シフト)
    var dayActive = false
    var day = 1
    var timeLeft = 0f
    var duration = 1f
    var eventLabel = ""
    var todayEarned = 0.0
    var stars = 0
    var hp = 100f
    var maxHp = 100f
    var comboCap = GameRules.COMBO_MAX.toFloat()
    /** 依頼カード (タイトル, 詳細, 進捗 0..1, 報酬 ¥, ★, 完了) */
    data class OrderCard(val title: String, val detail: String, val progress: Float, val reward: Double, val stars: Int, val done: Boolean)
    var orders: List<OrderCard> = emptyList()
    /** 内部表示中: 計器を右上の 1 行にまとめてエンジンを見やすくする */
    var compact = false

    // ドラッグレース
    var raceActive = false
    var raceStage = ""
    var raceDistance = 0f
    var raceTime = 0f
    var raceSpeed = 0f
    var raceGear = ""
    var raceBest = 0f

    private class FloatText(var x: Float, var y: Float, val text: String, val color: Int, val size: Float, var life: Float, val max: Float)
    private class Confetti(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float, val color: Int, var life: Float)

    private val floats = ArrayList<FloatText>()
    private val confetti = ArrayList<Confetti>()
    private var popupText = ""
    private var popupColor = Pal.AMBER
    private var popupLife = 0f
    private var flashLife = 0f
    private var flashColor = Color.WHITE
    private var shownMoney = 0.0
    private var time = 0f

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val rnd = Random(7)

    /** 右側パネルの幅 (HUD はその左側に配置) */
    var rightInset = 0f
    /** 下側の操作ボタン領域 */
    var bottomInset = 0f

    init {
        isClickable = false
        isFocusable = false
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    fun floatText(text: String, color: Int, big: Boolean = false, x: Float? = null, y: Float? = null) {
        val w = width - rightInset
        val fx = x ?: (w * (0.30f + rnd.nextFloat() * 0.4f))
        val fy = y ?: (height * (0.42f + rnd.nextFloat() * 0.12f))
        floats += FloatText(fx, fy, text, color, if (big) dp(30f) else dp(17f), if (big) 1.6f else 1.1f, if (big) 1.6f else 1.1f)
        if (floats.size > 40) floats.removeAt(0)
    }

    fun popup(text: String, color: Int) {
        popupText = text
        popupColor = color
        popupLife = 1.6f
    }

    fun flash(color: Int) {
        flashColor = color
        flashLife = 0.5f
    }

    fun burstConfetti(n: Int = 120) {
        val cx = (width - rightInset) * 0.5f
        val cy = height * 0.35f
        val colors = intArrayOf(Pal.AMBER, Pal.CYAN, Pal.GREEN, Pal.RED, Pal.VIOLET, Color.WHITE)
        repeat(n) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            val s = dp(200f + rnd.nextFloat() * 500f)
            confetti += Confetti(cx, cy, cos(a) * s, sin(a) * s - dp(300f), rnd.nextFloat() * 360f, rnd.nextFloat() * 720f - 360f,
                colors[rnd.nextInt(colors.size)], 2.2f + rnd.nextFloat())
        }
        if (confetti.size > 600) confetti.subList(0, confetti.size - 600).clear()
    }

    fun advance(dt: Float) {
        time += dt
        // 所持金はカウントアップ表示
        val d = money - shownMoney
        shownMoney = if (kotlin.math.abs(d) < 1.0 || d < 0) money else shownMoney + d * min(1.0, dt * 8.0)
        floats.forEach { it.life -= dt; it.y -= dp(60f) * dt }
        floats.removeAll { it.life <= 0 }
        confetti.forEach {
            it.life -= dt
            it.vy += dp(900f) * dt
            it.vx *= 1 - 1.2f * dt
            it.vy *= 1 - 1.2f * dt
            it.x += it.vx * dt
            it.y += it.vy * dt
            it.rot += it.vr * dt
        }
        confetti.removeAll { it.life <= 0 || it.y > height + 50 }
        popupLife = max(0f, popupLife - dt)
        flashLife = max(0f, flashLife - dt)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width - rightInset
        val h = height.toFloat()
        drawVignette(c, w, h)
        // 夜 (ガレージ画面の裏) は演出 (紙吹雪・浮遊テキスト・ポップアップ) だけ描く
        val showGauges = dayActive || raceActive
        if (showGauges) {
            drawMoney(c, w)
            drawTraits(c)
        }
        if (dayActive && !raceActive) { drawDayTop(c, w); drawOrders(c, w, h) }
        if (raceActive) drawRace(c, w, h) else if (!showGauges) Unit else if (compact) drawCompact(c, w) else drawGauges(c, w, h)
        // 浮遊テキスト
        tp.textAlign = Paint.Align.CENTER
        for (f in floats) {
            val a = (f.life / f.max).coerceIn(0f, 1f)
            val pop = 1f + 0.35f * max(0f, (f.life - f.max + 0.15f) / 0.15f)
            tp.textSize = f.size * pop
            tp.color = Color.argb((a * 255).toInt(), 0, 0, 0)
            c.drawText(f.text, f.x + dp(2f), f.y + dp(2f), tp)
            tp.color = (f.color and 0xFFFFFF) or ((a * 255).toInt() shl 24)
            c.drawText(f.text, f.x, f.y, tp)
        }
        // 紙吹雪
        p.style = Paint.Style.FILL
        for (k in confetti) {
            p.color = (k.color and 0xFFFFFF) or ((min(1f, k.life) * 255).toInt() shl 24)
            c.save()
            c.translate(k.x, k.y)
            c.rotate(k.rot)
            c.drawRect(-dp(4f), -dp(2f), dp(4f), dp(2f), p)
            c.restore()
        }
        // 中央ポップアップ
        if (popupLife > 0) {
            val t = 1.6f - popupLife
            val scale = if (t < 0.18f) 0.4f + 0.8f * (t / 0.18f) else 1.2f - 0.2f * min(1f, (t - 0.18f) / 0.2f)
            val a = min(1f, popupLife / 0.4f)
            tp.textAlign = Paint.Align.CENTER
            tp.textSize = dp(40f) * scale
            tp.color = Color.argb((a * 200).toInt(), 0, 0, 0)
            c.drawText(popupText, w / 2 + dp(3f), h * 0.3f + dp(3f), tp)
            tp.color = (popupColor and 0xFFFFFF) or ((a * 255).toInt() shl 24)
            c.drawText(popupText, w / 2, h * 0.3f, tp)
        }
        if (flashLife > 0) {
            p.style = Paint.Style.FILL
            p.shader = null
            p.color = (flashColor and 0xFFFFFF) or (((flashLife / 0.5f) * 90).toInt() shl 24)
            c.drawRect(0f, 0f, w, h, p)
        }
    }

    private fun drawVignette(c: Canvas, w: Float, h: Float) {
        // 熱が高いほど赤い縁取りが脈動する
        val hv = max(heat - 0.55f, 0f) / 0.45f
        val pulse = 0.6f + 0.4f * sin(time * (6f + 10f * hv))
        val a = if (overheat > 0) 0.75f else hv * 0.6f * pulse
        if (a > 0.01f) {
            p.style = Paint.Style.FILL
            p.shader = RadialGradient(w / 2, h / 2, max(w, h) * 0.75f, intArrayOf(0x00000000, Color.argb((a * 255).toInt(), 220, 30, 10)),
                floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
        }
        // 上部の読みやすさ用グラデーション
        p.shader = LinearGradient(0f, 0f, 0f, dp(120f), Color.argb(170, 0, 0, 0), 0, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, dp(120f), p)
        p.shader = null
    }

    private fun drawMoney(c: Canvas, w: Float) {
        val x = dp(14f)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(34f)
        tp.color = Pal.AMBER
        c.drawText((if (dayActive) "今日 ¥ " else "¥ ") + fmtMoney(if (dayActive) todayEarned else shownMoney), x, dp(42f), tp)
        tp.textSize = dp(15f)
        tp.color = if (incomePerSec >= 0) Pal.GREEN else Pal.RED
        val inc = (if (incomePerSec >= 0) "+" else "") + fmtMoney(incomePerSec) + " /s"
        c.drawText(inc, x, dp(64f), tp)
        tp.color = Pal.DIM
        val auto = if (autoLevel > 0) "  AUTO Lv$autoLevel" else ""
        c.drawText("×%.2f  ★$stars".format(multiplier) + auto, x + tp.measureText(inc) + dp(12f), dp(64f), tp)
        tp.textAlign = Paint.Align.RIGHT
        tp.textSize = dp(16f)
        tp.color = Pal.TEXT
        c.drawText(engineName, w - dp(12f), dp(28f), tp)
        tp.textSize = dp(12f)
        tp.color = Pal.DIM
        val line = if (isTurbine) "%.0f kW  推力 %.1f kN  EGT".format(powerKw, thrustKn)
        else "%.0f kW  η %.0f%%%s".format(powerKw, efficiency * 100, if (boostBar > 0.05f) "  +%.2f bar".format(boostBar) else "")
        c.drawText(line, w - dp(12f), dp(46f), tp)
    }

    /** 上部中央: タイマーと日付・イベント */
    private fun drawDayTop(c: Canvas, w: Float) {
        val cx = w * 0.5f
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(30f)
        val t = timeLeft.coerceAtLeast(0f)
        tp.color = if (t < 10 && (time * 4).toInt() % 2 == 0) Pal.RED else Pal.TEXT
        c.drawText("%d:%02d".format(t.toInt() / 60, t.toInt() % 60), cx, dp(38f), tp)
        // 残り時間バー
        val bw = dp(140f)
        rect.set(cx - bw / 2, dp(46f), cx + bw / 2, dp(50f))
        p.style = Paint.Style.FILL
        p.color = Color.argb(150, 40, 44, 52)
        c.drawRect(rect, p)
        rect.right = rect.left + bw * (t / duration).coerceIn(0f, 1f)
        p.color = if (t < 10) Pal.RED else Pal.AMBER
        c.drawRect(rect, p)
        tp.textSize = dp(12f)
        tp.color = Pal.DIM
        c.drawText("DAY $day" + if (eventLabel.isNotEmpty()) "  ·  $eventLabel" else "", cx, dp(66f), tp)
    }

    /** 右側: 依頼カード */
    private fun drawOrders(c: Canvas, w: Float, h: Float) {
        val cw = dp(210f)
        val x0 = w - cw - dp(10f)
        var y = dp(56f)
        val ch = dp(38f)
        for (o in orders) {
            rect.set(x0, y, x0 + cw, y + ch)
            p.style = Paint.Style.FILL
            p.color = if (o.done) Color.argb(200, 30, 70, 40) else Color.argb(185, 16, 18, 24)
            c.drawRoundRect(rect, dp(6f), dp(6f), p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = dp(1f)
            p.color = if (o.done) Pal.GREEN else if (o.stars >= 3) Pal.VIOLET else Pal.BORDER
            c.drawRoundRect(rect, dp(6f), dp(6f), p)
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = dp(11.5f)
            tp.color = if (o.done) Pal.GREEN else Pal.TEXT
            c.drawText(if (o.done) "✓ ${o.title}" else o.title, x0 + dp(7f), y + dp(13f), tp)
            tp.textAlign = Paint.Align.RIGHT
            tp.color = Pal.AMBER
            c.drawText("¥${fmtMoney(o.reward)} ★${o.stars}", x0 + cw - dp(7f), y + dp(13f), tp)
            tp.textAlign = Paint.Align.LEFT
            tp.textSize = dp(9.5f)
            tp.color = Pal.DIM
            var d = o.detail
            while (d.length > 2 && tp.measureText(d) > cw - dp(14f)) d = d.dropLast(2)
            if (d != o.detail) d += "…"
            c.drawText(d, x0 + dp(7f), y + dp(25f), tp)
            p.style = Paint.Style.FILL
            p.color = Color.argb(150, 50, 54, 64)
            c.drawRect(x0 + dp(7f), y + ch - dp(9f), x0 + cw - dp(7f), y + ch - dp(5f), p)
            p.color = if (o.done) Pal.GREEN else Pal.CYAN
            c.drawRect(x0 + dp(7f), y + ch - dp(9f), x0 + dp(7f) + (cw - dp(14f)) * o.progress.coerceIn(0f, 1f), y + ch - dp(5f), p)
            y += ch + dp(4f)
        }
    }

    private fun drawTraits(c: Canvas) {
        var x = dp(14f)
        val y = dp(78f)
        tp.textAlign = Paint.Align.LEFT
        tp.textSize = dp(11f)
        for ((t, col) in traits) {
            val tw = tp.measureText(t) + dp(12f)
            rect.set(x, y, x + tw, y + dp(18f))
            p.style = Paint.Style.FILL
            p.color = (col and 0xFFFFFF) or 0x40000000
            c.drawRoundRect(rect, dp(9f), dp(9f), p)
            tp.color = col
            c.drawText(t, x + dp(6f), y + dp(13f), tp)
            x += tw + dp(6f)
        }
    }

    private fun drawGauges(c: Canvas, w: Float, h: Float) {
        // タコメータ (270° アーク)
        // シフト中は依頼カードと重ならないよう小さめ
        val r = (if (dayActive) min(w * 0.12f, (h - bottomInset) * 0.17f) else min(w * 0.16f, (h - bottomInset) * 0.30f)).coerceAtLeast(dp(40f))
        val cx = w - r - dp(24f)
        val cy = h - bottomInset - r - dp(12f)
        rect.set(cx - r, cy - r, cx + r, cy + r)
        val maxRpm = redline * 1.08f
        fun ang(v: Float) = 135f + 270f * (v / maxRpm).coerceIn(0f, 1f)
        sp.strokeWidth = dp(10f)
        sp.color = Color.argb(160, 30, 34, 40)
        c.drawArc(rect, 135f, 270f, false, sp)
        sp.color = Color.argb(200, 80, 210, 120)
        c.drawArc(rect, ang(redline * sweetLo), ang(redline * sweetHi) - ang(redline * sweetLo), false, sp)
        sp.color = Color.argb(200, 235, 64, 52)
        c.drawArc(rect, ang(redline), ang(maxRpm) - ang(redline), false, sp)
        val inSweet = rpm >= redline * sweetLo && rpm <= redline * sweetHi
        sp.strokeWidth = dp(4f)
        sp.color = if (limiter) Pal.RED else if (inSweet) Pal.GREEN else Pal.AMBER
        c.drawArc(RectF(rect).apply { inset(dp(12f), dp(12f)) }, 135f, ang(rpm) - 135f, false, sp)
        val a = Math.toRadians(ang(rpm).toDouble())
        sp.strokeWidth = dp(3f)
        sp.color = Color.WHITE
        c.drawLine(cx, cy, cx + (r - dp(6f)) * cos(a).toFloat(), cy + (r - dp(6f)) * sin(a).toFloat(), sp)
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = r * 0.32f
        tp.color = Pal.TEXT
        c.drawText("%,d".format(rpm.toInt()), cx, cy + r * 0.45f, tp)
        tp.textSize = r * 0.14f
        tp.color = Pal.DIM
        c.drawText(if (isTurbine) "N2 rpm" else "rpm", cx, cy + r * 0.64f, tp)
        if (limiter && (time * 10).toInt() % 2 == 0) {
            tp.color = Pal.RED
            tp.textSize = r * 0.2f
            c.drawText("LIMIT", cx, cy - r * 0.2f, tp)
        }

        // コンボ & 熱ゲージ (タコメータの左)
        val bx = cx - r - dp(34f)
        val bh = r * 1.7f
        val by = cy + r * 0.7f
        drawVBar(c, bx, by, bh, (combo - 1f) / (comboCap - 1f), if (combo > 2.5f) Pal.VIOLET else Pal.CYAN, "COMBO", "×%.1f".format(combo))
        val heatCol = if (overheat > 0) Pal.RED else if (heat > 0.75f) Color.rgb(255, 110, 40) else Pal.AMBER
        drawVBar(c, bx - dp(34f), by, bh, heat, heatCol, "HEAT", if (overheat > 0) "%.1fs".format(overheat) else "%d%%".format((heat * 100).toInt()))
        if (dayActive) {
            val hf = hp / maxHp
            val hpCol = if (hf < 0.3f && (time * 6).toInt() % 2 == 0) Pal.RED else if (hf < 0.5f) Color.rgb(255, 110, 40) else Pal.GREEN
            drawVBar(c, bx - dp(68f), by, bh, hf, hpCol, "耐久", "%d".format(hp.toInt()))
        }
    }

    private fun drawCompact(c: Canvas, w: Float) {
        val inSweet = rpm >= redline * sweetLo && rpm <= redline * sweetHi
        tp.textAlign = Paint.Align.RIGHT
        tp.textSize = dp(14f)
        tp.color = if (limiter) Pal.RED else if (inSweet) Pal.GREEN else Pal.TEXT
        c.drawText("%,d rpm".format(rpm.toInt()), w - dp(12f), dp(68f), tp)
        tp.textSize = dp(12f)
        tp.color = if (overheat > 0 || heat > 0.75f) Pal.RED else Pal.DIM
        c.drawText("COMBO ×%.1f  HEAT %d%%".format(combo, (heat * 100).toInt()), w - dp(12f), dp(86f), tp)
    }

    private fun drawVBar(c: Canvas, x: Float, bottom: Float, hgt: Float, frac: Float, col: Int, label: String, value: String) {
        val bw = dp(16f)
        rect.set(x - bw / 2, bottom - hgt, x + bw / 2, bottom)
        p.style = Paint.Style.FILL
        p.color = Color.argb(170, 20, 22, 28)
        c.drawRoundRect(rect, dp(4f), dp(4f), p)
        val f = frac.coerceIn(0f, 1f)
        rect.set(x - bw / 2 + dp(2f), bottom - dp(2f) - (hgt - dp(4f)) * f, x + bw / 2 - dp(2f), bottom - dp(2f))
        p.shader = LinearGradient(0f, bottom, 0f, bottom - hgt, (col and 0xFFFFFF) or 0x80000000.toInt(), col, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, dp(3f), dp(3f), p)
        p.shader = null
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(10f)
        tp.color = Pal.DIM
        c.drawText(label, x, bottom + dp(12f), tp)
        tp.color = col
        tp.textSize = dp(11f)
        c.drawText(value, x, bottom - hgt - dp(5f), tp)
    }

    private fun drawRace(c: Canvas, w: Float, h: Float) {
        val top = h * 0.2f
        tp.textAlign = Paint.Align.CENTER
        tp.textSize = dp(56f)
        tp.color = Pal.TEXT
        c.drawText("%.0f".format(raceSpeed), w / 2, top + dp(40f), tp)
        tp.textSize = dp(14f)
        tp.color = Pal.DIM
        c.drawText("km/h   GEAR $raceGear   %,d rpm".format(rpm.toInt()), w / 2, top + dp(62f), tp)
        tp.textSize = dp(30f)
        tp.color = Pal.AMBER
        c.drawText("%.3f s".format(raceTime), w / 2, top + dp(100f), tp)
        if (raceBest > 0) {
            tp.textSize = dp(12f)
            tp.color = Pal.DIM
            c.drawText("BEST %.3f s".format(raceBest), w / 2, top + dp(118f), tp)
        }
        // 距離バー (1/4 マイル)
        val bx0 = dp(24f)
        val bx1 = w - dp(24f)
        val by = h - bottomInset - dp(24f)
        rect.set(bx0, by - dp(6f), bx1, by + dp(6f))
        p.style = Paint.Style.FILL
        p.color = Color.argb(170, 20, 22, 28)
        c.drawRoundRect(rect, dp(6f), dp(6f), p)
        val f = (raceDistance / 402.336f).coerceIn(0f, 1f)
        rect.set(bx0, by - dp(6f), bx0 + (bx1 - bx0) * f, by + dp(6f))
        p.color = Pal.GREEN
        c.drawRoundRect(rect, dp(6f), dp(6f), p)
        tp.textSize = dp(11f)
        tp.color = Pal.TEXT
        c.drawText("%.0f / 402 m".format(raceDistance), w / 2, by - dp(10f), tp)
        // 変速タイミングのヒント: シフトランプ
        val frac = rpm / redline
        val lamps = 8
        for (i in 0 until lamps) {
            val on = frac >= 0.6f + 0.4f * i / lamps
            p.color = when {
                !on -> Color.argb(120, 40, 40, 40)
                i < 4 -> Pal.GREEN
                i < 7 -> Pal.AMBER
                else -> if ((time * 12).toInt() % 2 == 0) Pal.RED else Color.WHITE
            }
            c.drawCircle(w / 2 + (i - (lamps - 1) / 2f) * dp(22f), top - dp(22f), dp(7f), p)
        }
        if (raceStage.isNotEmpty()) {
            tp.textSize = dp(64f)
            tp.color = if (raceStage == "GO!") Pal.GREEN else Pal.RED
            c.drawText(raceStage, w / 2, h * 0.62f, tp)
        }
    }
}
