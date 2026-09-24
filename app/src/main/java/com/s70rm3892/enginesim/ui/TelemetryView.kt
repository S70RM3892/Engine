package com.s70rm3892.enginesim.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import com.s70rm3892.enginesim.EngineSummary
import com.s70rm3892.enginesim.Tel
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 右下のテレメトリモニタ: タコメータ / 数値 (トルク・出力・熱流) / トルク・出力履歴 / p-V 線図 / 熱収支バー。
 * Canvas 描画のみ (レイアウト計算は onDraw 内で寸法から決める)。
 */
class TelemetryView(context: Context) : View(context) {
    var summary: EngineSummary? = null
        set(v) { field = v; histLen = 0; histPos = 0; invalidate() }

    private val tel = FloatArray(Tel.COUNT)
    private val pvV = FloatArray(360)
    private val pvP = FloatArray(360)
    private var pvN = 0

    private val hist = 240  // 30Hz × 8s
    private val torqueHist = FloatArray(hist)
    private val powerHist = FloatArray(hist)
    private val rpmHist = FloatArray(hist)
    private var histPos = 0
    private var histLen = 0

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.TEXT; typeface = Typeface.MONOSPACE }
    private val lab = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Pal.DIM }
    private val path = Path()
    private val rf = RectF()

    fun update(t: FloatArray, v: FloatArray, pr: FloatArray, n: Int) {
        System.arraycopy(t, 0, tel, 0, min(t.size, tel.size))
        pvN = min(n, pvV.size)
        System.arraycopy(v, 0, pvV, 0, pvN)
        System.arraycopy(pr, 0, pvP, 0, pvN)
        torqueHist[histPos] = tel[Tel.TORQUE]
        powerHist[histPos] = tel[Tel.POWER_KW]
        rpmHist[histPos] = tel[Tel.RPM]
        histPos = (histPos + 1) % hist
        histLen = min(hist, histLen + 1)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(Pal.BG)
        val s = summary ?: return
        val pad = dp(6f)
        val h = height.toFloat()
        val w = width.toFloat()
        if (h > w * 0.8f) {
            drawTall(c, s, pad, w, h)
            return
        }
        val gaugeSize = min(h - 2 * pad, w * 0.34f)
        drawTach(c, s, pad, pad, gaugeSize)
        val colX = pad * 2 + gaugeSize
        val colW = (w - colX - pad) * 0.42f
        drawReadouts(c, s, colX, pad, colW, h - 2 * pad)
        val plotX = colX + colW + pad
        val plotW = w - plotX - pad
        val hTop = (h - 3 * pad) * 0.48f
        drawHistory(c, plotX, pad, plotW, hTop)
        if (s.isCombustion && pvN > 8) {
            drawPV(c, plotX, pad * 2 + hTop, plotW * 0.56f, h - hTop - 3 * pad)
            drawHeatBar(c, plotX + plotW * 0.56f + pad, pad * 2 + hTop, plotW * 0.44f - pad, h - hTop - 3 * pad)
        } else {
            drawHeatBar(c, plotX, pad * 2 + hTop, plotW, h - hTop - 3 * pad)
        }
    }

    /** 縦長領域: 上段 = タコメータ + 数値, 中段 = 履歴, 下段 = p-V + 熱収支 */
    private fun drawTall(c: Canvas, s: EngineSummary, pad: Float, w: Float, h: Float) {
        val topH = h * 0.42f
        val gauge = min(topH - pad, w * 0.48f)
        drawTach(c, s, pad, pad, gauge)
        drawReadouts(c, s, pad * 2 + gauge, pad, w - gauge - pad * 3, topH - pad)
        val midY = topH + pad
        val midH = h * 0.24f
        drawHistory(c, pad, midY, w - 2 * pad, midH)
        val botY = midY + midH + pad
        val botH = h - botY - pad
        if (s.isCombustion && pvN > 8) {
            drawPV(c, pad, botY, (w - 3 * pad) * 0.6f, botH)
            drawHeatBar(c, pad * 2 + (w - 3 * pad) * 0.6f, botY, (w - 3 * pad) * 0.4f, botH)
        } else {
            drawHeatBar(c, pad, botY, w - 2 * pad, botH)
        }
    }

    private fun frame(c: Canvas, x: Float, y: Float, w: Float, h: Float, title: String) {
        rf.set(x, y, x + w, y + h)
        p.style = Paint.Style.FILL; p.color = Pal.PANEL
        c.drawRoundRect(rf, dp(5f), dp(5f), p)
        p.style = Paint.Style.STROKE; p.color = Pal.BORDER; p.strokeWidth = dp(1f)
        c.drawRoundRect(rf, dp(5f), dp(5f), p)
        lab.textSize = dp(8.5f)
        lab.color = Pal.AMBER
        c.drawText(title, x + dp(5f), y + dp(10f), lab)
        lab.color = Pal.DIM
    }

    private fun drawTach(c: Canvas, s: EngineSummary, x: Float, y: Float, size: Float) {
        val cx = x + size / 2
        val cy = y + size / 2
        val r = size / 2 - dp(4f)
        p.style = Paint.Style.FILL; p.color = Pal.PANEL
        c.drawCircle(cx, cy, r + dp(3f), p)
        val maxRpm = if (s.isElectric) s.redlineRpm else s.limiterRpm * 1.12f
        val start = 135f
        val sweep = 270f
        fun ang(rpm: Float) = start + sweep * (rpm / maxRpm).coerceIn(0f, 1.05f)
        rf.set(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(4f)
        p.color = Pal.PANEL_HI
        c.drawArc(rf, start, sweep, false, p)
        if (!s.isElectric) {
            p.color = Pal.RED
            c.drawArc(rf, ang(s.redlineRpm), ang(maxRpm) - ang(s.redlineRpm), false, p)
        }
        // 目盛り
        p.strokeWidth = dp(1.2f); p.color = Pal.DIM
        val step = niceStep(maxRpm / 8f)
        lab.textSize = dp(7.5f)
        var v = 0f
        while (v <= maxRpm + 1) {
            val a = Math.toRadians(ang(v).toDouble())
            val ca = cos(a).toFloat(); val sa = sin(a).toFloat()
            c.drawLine(cx + ca * r * 0.84f, cy + sa * r * 0.84f, cx + ca * r * 0.97f, cy + sa * r * 0.97f, p)
            val t = if (step >= 1000) "%d".format((v / 1000).toInt()) else "%.1f".format(v / 1000)
            c.drawText(t, cx + ca * r * 0.7f - lab.measureText(t) / 2, cy + sa * r * 0.7f + dp(3f), lab)
            v += step
        }
        // 目標値マーカー
        val target = tel[Tel.TARGET_RPM]
        run {
            val a = Math.toRadians(ang(target).toDouble())
            p.color = Pal.CYAN; p.strokeWidth = dp(3f)
            c.drawLine(cx + cos(a).toFloat() * r * 0.9f, cy + sin(a).toFloat() * r * 0.9f,
                cx + cos(a).toFloat() * r * 1.02f, cy + sin(a).toFloat() * r * 1.02f, p)
        }
        // 針
        val rpm = tel[Tel.RPM]
        val a = Math.toRadians(ang(rpm).toDouble())
        p.color = if (tel[Tel.LIMITER] > 0.5f) Pal.RED else Pal.AMBER
        p.strokeWidth = dp(2.5f)
        c.drawLine(cx, cy, cx + cos(a).toFloat() * r * 0.88f, cy + sin(a).toFloat() * r * 0.88f, p)
        p.style = Paint.Style.FILL; c.drawCircle(cx, cy, dp(4f), p)
        txt.textSize = size * 0.13f
        txt.color = Pal.TEXT
        val rs = "%,d".format(rpm.toInt())
        c.drawText(rs, cx - txt.measureText(rs) / 2, cy + r * 0.52f, txt)
        lab.textSize = dp(7.5f)
        val unit = when { s.isTurbine -> "N2 rpm"; else -> "rpm ×1000" }
        c.drawText(unit, cx - lab.measureText(unit) / 2, cy + r * 0.72f, lab)
        if (tel[Tel.LIMITER] > 0.5f) {
            lab.color = Pal.RED
            c.drawText("LIMIT", cx - lab.measureText("LIMIT") / 2, cy - r * 0.35f, lab)
            lab.color = Pal.DIM
        }
    }

    private fun niceStep(raw: Float): Float {
        val cands = floatArrayOf(10f, 20f, 50f, 100f, 200f, 250f, 500f, 1000f, 2000f, 2500f, 5000f, 10000f)
        return cands.firstOrNull { it >= raw } ?: 10000f
    }

    private fun drawReadouts(c: Canvas, s: EngineSummary, x: Float, y: Float, w: Float, h: Float) {
        frame(c, x, y, w, h, "TELEMETRY")
        val rows = mutableListOf<Pair<String, String>>()
        fun k(v: Float) = v - 273.15f
        rows += "TORQUE" to "%6.1f Nm".format(tel[Tel.TORQUE])
        rows += "POWER" to "%6.1f kW".format(tel[Tel.POWER_KW])
        when {
            s.isTurbine -> {
                rows += "N1 / N2" to "%3.0f / %3.0f %%".format(tel[Tel.N1], tel[Tel.N2])
                rows += "THRUST" to "%6.1f kN".format(tel[Tel.THRUST_KN])
                rows += "EGT" to "%5.0f °C".format(k(tel[Tel.EGT_K]))
                rows += "FUEL" to "%6.0f kW".format(tel[Tel.FUEL_KW])
            }
            s.isElectric -> {
                rows += "ELEC f" to "%6.1f Hz".format(tel[Tel.ELEC_HZ])
                rows += "CURRENT" to "%6.0f A".format(tel[Tel.CURRENT_A])
                rows += "SLIP" to "%6.2f %%".format(tel[Tel.SLIP] * 100)
                rows += "LOSS" to "%6.1f kW".format(tel[Tel.FRICTION_KW])
            }
            else -> {
                rows += "BMEP" to "%6.2f bar".format(tel[Tel.BMEP])
                rows += if (tel[Tel.BOOST_BAR] > 0.01f) "BOOST" to "%6.2f bar".format(tel[Tel.BOOST_BAR])
                else "MAP" to "%6.1f kPa".format(tel[Tel.MAP_KPA])
                rows += "P max" to "%6.1f bar".format(tel[Tel.PEAK_BAR])
                rows += "EGT" to "%5.0f °C".format(k(tel[Tel.EGT_K]))
                rows += "COOLANT" to "%5.0f °C".format(k(tel[Tel.COOLANT_K]))
                rows += "η brake" to "%5.1f %%".format(tel[Tel.EFFICIENCY] * 100)
            }
        }
        if (tel[Tel.SPEED_KMH] > 0.05f || tel[Tel.DISTANCE_M] > 0.5f) rows += "SPEED" to "%5.1f km/h".format(tel[Tel.SPEED_KMH])
        rows += "THROTTLE" to "%5.1f %%".format(tel[Tel.THROTTLE] * 100)
        val g = tel[Tel.GEAR].toInt()
        rows += (if (s.propeller) "PROP" else "OUT " + (if (g == 0) "N" else "G$g")) to "%,6d rpm".format(tel[Tel.OUTPUT_RPM].toInt())
        val lineH = (h - dp(14f)) / rows.size
        val ts = min(dp(10f), lineH * 0.72f)
        txt.textSize = ts
        lab.textSize = ts * 0.85f
        rows.forEachIndexed { i, (l, v) ->
            val yy = y + dp(14f) + lineH * (i + 0.75f)
            c.drawText(l, x + dp(5f), yy, lab)
            txt.color = Pal.TEXT
            c.drawText(v, x + w - dp(5f) - txt.measureText(v), yy, txt)
        }
    }

    private fun drawHistory(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        frame(c, x, y, w, h, "TORQUE / POWER (8s)")
        if (histLen < 2) return
        val top = y + dp(13f)
        val bottom = y + h - dp(4f)
        fun series(data: FloatArray, color: Int, labelTxt: String, row: Int) {
            var mx = 1f
            for (i in 0 until histLen) mx = max(mx, data[i])
            mx *= 1.15f
            path.reset()
            for (i in 0 until histLen) {
                val idx = (histPos - histLen + i + hist) % hist
                val px = x + dp(4f) + (w - dp(8f)) * i / (hist - 1)
                val py = bottom - (bottom - top) * (data[idx] / mx).coerceIn(-0.1f, 1f)
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(1.5f); p.color = color
            c.drawPath(path, p)
            lab.textSize = dp(7.5f); lab.color = color
            val t = "$labelTxt max ${"%.0f".format(mx / 1.15f)}"
            c.drawText(t, x + w - lab.measureText(t) - dp(5f), y + dp(20f) + row * dp(9f), lab)
            lab.color = Pal.DIM
        }
        series(torqueHist, Pal.AMBER, "Nm", 0)
        series(powerHist, Pal.CYAN, "kW", 1)
    }

    private fun drawPV(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        frame(c, x, y, w, h, "p-V (CYL 1)")
        var vMin = Float.MAX_VALUE; var vMax = 0f; var pMax = 1f
        for (i in 0 until pvN) { vMin = min(vMin, pvV[i]); vMax = max(vMax, pvV[i]); pMax = max(pMax, pvP[i]) }
        if (vMax <= vMin) return
        pMax *= 1.1f
        val l = x + dp(6f); val r = x + w - dp(6f); val t = y + dp(14f); val b = y + h - dp(6f)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(0.8f); p.color = Pal.BORDER
        c.drawLine(l, b, r, b, p); c.drawLine(l, t, l, b, p)
        // 大気圧線
        val yAtm = b - (b - t) * (1.013f / pMax)
        c.drawLine(l, yAtm, r, yAtm, p)
        path.reset()
        for (i in 0 until pvN) {
            val px = l + (r - l) * (pvV[i] - vMin) / (vMax - vMin)
            val py = b - (b - t) * (pvP[i] / pMax).coerceIn(0f, 1f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        p.color = Pal.VIOLET; p.strokeWidth = dp(1.4f)
        c.drawPath(path, p)
        lab.textSize = dp(7.5f)
        c.drawText("%.0f bar".format(pMax / 1.1f), l + dp(2f), t + dp(8f), lab)
        c.drawText("%.2f L".format(vMax), r - lab.measureText("%.2f L".format(vMax)), b - dp(2f), lab)
    }

    private fun drawHeatBar(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        frame(c, x, y, w, h, "HEAT FLOW kW")
        val fuel = tel[Tel.FUEL_KW]
        val parts = listOf(
            Triple("BRAKE", tel[Tel.BRAKE_KW], Pal.GREEN),
            Triple("FRIC", tel[Tel.FRICTION_KW], Pal.AMBER),
            Triple("COOL", tel[Tel.COOLANT_KW], Pal.CYAN),
            Triple("EXH", tel[Tel.EXHAUST_KW], Pal.RED),
        )
        val total = max(fuel, parts.sumOf { it.second.toDouble() }.toFloat()).coerceAtLeast(0.001f)
        val l = x + dp(6f); val r = x + w - dp(6f)
        val barTop = y + dp(14f); val barH = min(dp(12f), h * 0.2f)
        var cx = l
        for ((_, v, col) in parts) {
            val ww = (r - l) * (v.coerceAtLeast(0f) / total)
            p.style = Paint.Style.FILL; p.color = col
            c.drawRect(cx, barTop, cx + ww, barTop + barH, p)
            cx += ww
        }
        val lines = listOf(Triple("FUEL", fuel, Pal.TEXT)) + parts
        val avail = h - (barTop - y) - barH - dp(4f)
        lab.textSize = min(dp(8.5f), avail / (lines.size * 1.2f))
        txt.textSize = lab.textSize
        lines.forEachIndexed { i, (n, v, col) ->
            val yy = barTop + barH + dp(3f) + (i + 1) * lab.textSize * 1.2f
            lab.color = col
            c.drawText(n, l, yy, lab)
            val vs = "%.0f".format(v)
            txt.color = col
            c.drawText(vs, r - txt.measureText(vs), yy, txt)
        }
        txt.color = Pal.TEXT
        lab.color = Pal.DIM
    }
}
