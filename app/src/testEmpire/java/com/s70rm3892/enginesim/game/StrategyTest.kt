package com.s70rm3892.enginesim.game

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * 戦略の比較: 「全開でオーバーヒートを繰り返す」が「帯を追って熱を管理する」より強くならないこと。
 * 同じ日 (同じ乱数) を 3 つの戦略で遊ばせ、平均の稼ぎを比べる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StrategyTest {
    /** 簡易エンジン (回転の一次遅れ + 出力 ∝ 回転²。全開は自動ダイナモで約 0.92×レッドライン) */
    class Surrogate(val prof: EngineProfile) {
        var rpm = prof.idle
        private var af = 0
        private var lastThr = 0.0
        fun step(dt: Double, thr: Double, cut: Boolean): Sample {
            val red = prof.redline
            // 実機と同じく自動ダイナモが全開を約 0.92×レッドラインで受け止める (リミッターには当たらない)
            val target = if (cut) prof.idle * 0.5 else prof.idle + thr * (0.92 * red - prof.idle)
            rpm += (target - rpm) * min(1.0, dt / 0.35)
            val frac = rpm / red
            val full = prof.refPowerKw / 0.85
            val out = if (cut) 0.0 else full * min(1.0, thr * 1.1) * min(1.0, frac / 0.85).let { it * it }
            val eff = 0.30 * (0.6 + 0.4 * min(1.0, thr / 0.6)) * (1.0 - 0.3 * max(0.0, thr - 0.7))
            if (lastThr > 0.7 && thr < 0.1 && frac > 0.7) af++
            lastThr = thr
            val load = if (rpm > 1) out * 1000 / (rpm * 2 * Math.PI / 60) else 0.0
            return Sample(rpm, load, out, 0.0, out / eff, eff, 0.0, frac > 1.0, af)
        }
    }

    enum class Strat { SPAM, SLOPPY, CAREFUL, EDGE }

    private fun play(strat: Strat, seed: Long, g: GameState): Pair<Double, Int> {
        val prof = EngineProfile(7000.0, 800.0, "reciprocating", "otto4", false, false, 40.0, 0.3, 30.0)
        val r = RunSession(prof, g.perks, DayEvent.NONE, 1.0, seed = seed)
        r.orders.clear()
        val eng = Surrogate(prof)
        val ev = ArrayList<RunSession.Ev>()
        var s = eng.step(0.05, 0.0, false)
        var overheats = 0
        val noise = kotlin.random.Random(seed * 31)
        var nextLook = 0.0
        var seenBand = r.bandCenter
        var lagBand = r.bandCenter
        var err = 0.0
        val history = ArrayDeque<Double>()
        while (!r.finished) {
            history.addLast(r.bandCenter)
            if (history.size > 10) lagBand = history.removeFirst()   // 0.5 秒前の帯
            val red = prof.redline
            // 上手いプレイヤー: 帯に対応するペダル位置 (フィードフォワード) + 誤差の微修正
            val ff = (red * r.bandCenter - prof.idle) / (0.92 * red - prof.idle)
            val toward = (ff + 2.0 * (red * r.bandCenter - s.rpm) / red).coerceIn(0.0, 1.0)
            val thr = when (strat) {
                Strat.SPAM -> 1.0
                // 雑なプレイヤー: 0.5 秒遅れで帯を見て、ペダル位置は ±15% ずれる (0.6 秒ごとに持ち替え)
                Strat.SLOPPY -> if (r.heat > 0.8) 0.0 else {
                    if (r.time >= nextLook) {
                        nextLook = r.time + 0.6
                        seenBand = lagBand
                        err = (noise.nextDouble() * 2 - 1) * 0.15
                    }
                    ((red * seenBand - prof.idle) / (0.92 * red - prof.idle) + err).coerceIn(0.0, 1.0)
                }
                Strat.CAREFUL -> if (r.heat > 0.6) 0.0 else toward      // 熱を低めに保つ
                Strat.EDGE -> if (r.heat > 0.88) 0.0 else toward         // 熱ボーナス帯 (72〜92%) を攻める
            }
            val cut = r.overheat > 0
            s = eng.step(0.05, if (cut) 0.0 else thr, cut)
            ev.clear()
            r.step(0.05, s, if (cut) 0.0 else thr, ev)
            overheats += ev.count { it is RunSession.Ev.Overheat }
        }
        return r.earned to overheats
    }

    @Test
    fun skillBeatsOverheatSpam() {
        val report = StringBuilder()
        for ((label, g) in listOf("初期" to GameState(), "強化後" to GameState().apply {
            board["radiator"] = 3; board["block"] = 3; board["mechanic"] = 2; board["comboCap"] = 2
        })) {
            val res = Strat.entries.associateWith { st ->
                val runs = (1L..20L).map { play(st, it, g) }
                runs.map { it.first }.average() to runs.map { it.second }.average()
            }
            report.append("[$label] ").append(res.entries.joinToString("  ") { (k, v) -> "$k ¥${"%.0f".format(v.first)} (OH ${"%.1f".format(v.second)})" }).append('\n')
            File("build").mkdirs()
            File("build/strategy_report.txt").writeText(report.toString())
            val spam = res.getValue(Strat.SPAM).first
            val best = max(res.getValue(Strat.CAREFUL).first, res.getValue(Strat.EDGE).first)
            assertTrue("[$label] skill ${"%.0f".format(best)} should beat spam ${"%.0f".format(spam)} by 25%", best > spam * 1.25)
            val sloppy = res.getValue(Strat.SLOPPY).first
            assertTrue("[$label] even sloppy band play beats spam ($sloppy vs $spam)", sloppy > spam)
            assertTrue("[$label] skill matters but isn't brutal (best $best vs sloppy $sloppy)", best > sloppy * 1.3 && best < sloppy * 5)
            assertTrue("[$label] riding the edge pays (${res.getValue(Strat.EDGE).first} vs ${res.getValue(Strat.CAREFUL).first})",
                res.getValue(Strat.EDGE).first >= res.getValue(Strat.CAREFUL).first)
        }
        File("build").mkdirs()
        File("build/strategy_report.txt").writeText(report.toString())
    }
}
