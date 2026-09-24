package com.s70rm3892.enginesim.game

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 昼夜サイクルのペース確認。簡易エンジン (回転の一次遅れ + 出力 ∝ 回転²) を相手に、依頼を狙うボットが
 * RunSession で 1 日を遊び、夜は「買える候補の中から一番安いものを優先」で買い物をする。
 * 夜ごとの買い物回数・買える候補数・エンジン台数の推移を build/balance_report.txt に書き出す。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BalanceSimTest {

    /** 簡易エンジン */
    private class Surrogate(val prof: EngineProfile) {
        var rpm = prof.idle
        var af = 0
        private var lastThr = 0.0
        fun step(dt: Double, thr: Double, cut: Boolean): Sample {
            val red = prof.redline
            val target = if (cut) 0.0 else prof.idle + thr * (0.97 * red - prof.idle) + if (thr > 0.95) 0.06 * red else 0.0
            rpm += (target - rpm) * min(1.0, dt / 0.35)
            val frac = rpm / red
            val full = prof.refPowerKw / 0.85
            val out = if (cut) 0.0 else full * min(1.0, thr * 1.1) * min(1.0, frac / 0.85).let { it * it }
            val eff = if (prof.isElectric) 0.85 else 0.30 * (0.6 + 0.4 * min(1.0, thr / 0.6)) * (1.0 - 0.3 * max(0.0, thr - 0.7))
            if (prof.canAfterfire && lastThr > 0.7 && thr < 0.1 && frac > 0.7) af++
            lastThr = thr
            val load = if (rpm > 1) out * 1000 / (rpm * 2 * Math.PI / 60) else 0.0
            return Sample(rpm, load, out, 0.0, if (eff > 0) out / eff else 0.0, eff, if (prof.boostCapable) thr * 1.0 else 0.0,
                frac > 1.0, af)
        }
    }

    /** 依頼を狙うボット */
    private fun botThrottle(r: RunSession, rpm: Double, t: Double): Double {
        val red = r.profile.redline
        if (r.heat > 0.82) return 0.0
        val o = r.orders.firstOrNull { !it.done }
        fun toward(target: Double) = (0.5 + 3.0 * (target - rpm) / red).coerceIn(0.0, 1.0)
        return when (o?.kind) {
            OrderKind.HOLD_BAND, OrderKind.VIP_LIMIT -> toward((o.a + o.b) / 2)
            OrderKind.REDLINE -> toward(red * 0.975)
            OrderKind.AFTERFIRE -> if (((t * 1.5).toInt() % 2) == 0) 1.0 else 0.0
            OrderKind.IDLE_CALM -> 0.0
            OrderKind.EFFICIENCY -> 0.6
            OrderKind.COOL_POWER -> if (r.heat < 0.35) 0.75 else 0.0
            OrderKind.BLIP -> if (((t * 0.8).toInt() % 2) == 0) 0.0 else 1.0
            else -> toward(red * (r.sweetLo + r.sweetHi) / 2)
        }
    }

    private fun profileFor(g: GameState): EngineProfile {
        val d = GameRoster.byKey(g.current)
        val p = g.prog(d.key)
        val boost = 1.0 + 0.04 * p.levels.values.sum()
        val cat = d.catalogId ?: "tiller"
        val fam = when {
            cat.startsWith("turbo") -> "turbine"
            cat.startsWith("motor") -> "electric"
            cat.startsWith("wankel") -> "wankel"
            else -> "reciprocating"
        }
        val cyc = when {
            cat.startsWith("steam") -> "steam"
            cat.startsWith("stirling") -> "stirling"
            cat in setOf("i4_tdi", "jumo205", "deltic18") -> "diesel4"
            fam == "wankel" -> "wankel4"
            fam == "turbine" || fam == "electric" -> "none"
            else -> "otto4"
        }
        return EngineProfile(
            redline = if (fam == "turbine") 15000.0 else 7000.0, idle = if (fam == "electric") 0.0 else 800.0, family = fam, cycle = cyc,
            boostCapable = p.level(UpgradeKind.BOOST) > 0 || cat.endsWith("t") || cat.endsWith("sc"), propeller = cat.startsWith("r"),
            refPowerKw = if (p.bestPowerKw > 0) p.bestPowerKw else d.estNet / 0.6 * boost, refEff = if (p.bestEff > 0) p.bestEff else 0.28,
            refNet = d.estNet * boost, specialty = p.specialty,
        )
    }

    /** 夜の買い物 (安い順に買えるだけ)。戻り値: (買った数, 買える候補数) */
    private fun shop(g: GameState): Pair<Int, Int> {
        data class Opt(val cost: Double, val star: Boolean, val buy: () -> Boolean)
        fun options(): List<Opt> {
            val list = ArrayList<Opt>()
            for (n in GameBoard.nodes) if (g.canBuyNode(n)) list += Opt(g.boardCost(n), n.currency == Currency.STAR) { g.buyNode(n) }
            val d = GameRoster.byKey(g.current)
            for (u in UpgradeKind.entries) if (upgradeApplies(d, "reciprocating", u) && g.tuneAvailable(u) && g.prog(d.key).level(u) < u.maxLevel)
                list += Opt(g.upgradeCost(d, u), false) { g.buyTune(d, u) }
            for (e in GameRoster.engines) if (g.canUnlock(e) && g.stars >= e.starCost) list += Opt(e.price * 0.7, false) {   // エンジンは少し優先
                g.unlock(e).also { ok -> if (ok && e.estNet > GameRoster.byKey(g.current).estNet) g.current = e.key }
            }
            return list
        }
        val affordable = options().count { if (it.star) g.stars >= it.cost else g.money >= it.cost }
        var bought = 0
        while (true) {
            val o = options().filter { if (it.star) g.stars >= it.cost else g.money >= it.cost }.minByOrNull { if (it.star) it.cost * 50 else it.cost } ?: break
            if (!o.buy()) break
            bought++
            if (bought > 40) break
        }
        return bought to affordable
    }

    @Test
    fun dayNightPacing() {
        val g = GameState()
        val rnd = Random(42)
        val report = StringBuilder("day | earned | orders | ★ | blown | bought | choices | engines | money\n")
        var nightsWithPurchase = 0
        var nightsWithChoice = 0
        var firstEngineDay = -1
        val days = 120
        for (d in 1..days) {
            val prof = profileFor(g)
            val r = RunSession(prof, g.perks, g.nextEvent, g.multiplier, seed = rnd.nextLong())
            val eng = Surrogate(prof)
            val out = ArrayList<RunSession.Ev>()
            var s = eng.step(0.05, 0.0, false)
            while (!r.finished) {
                val thr = botThrottle(r, s.rpm, r.time)
                val cut = r.overheat > 0
                s = eng.step(0.05, if (cut) 0.0 else thr, cut)
                r.step(0.05, s, if (cut) 0.0 else thr, out)
            }
            val res = r.result()
            g.closeDay(res, g.current)
            Goals.evaluate(g)
            g.nextEvent = DayEvent.roll(g.day, rnd)
            val (bought, choices) = shop(g)
            if (bought > 0) nightsWithPurchase++
            if (choices >= 2) nightsWithChoice++
            if (firstEngineDay < 0 && g.unlocked.size > 1) firstEngineDay = d
            report.append("%3d | %9s | %2d | %3d | %s | %2d | %2d | %2d | %s\n".format(d, fmtMoney(res.earned), res.orders, g.stars,
                if (res.blown) "B" else "-", bought, choices, g.unlocked.size, fmtMoney(g.money)))
        }
        report.append("\nfirst engine: day $firstEngineDay, nights with purchase: $nightsWithPurchase/$days, nights with ≥2 choices: $nightsWithChoice/$days\n")
        report.append("board: ${g.board}\nengines: ${g.unlocked.size} ${g.unlocked}\ngoals: ${g.goalsDone.size}/${Goals.all.size}\n")
        File("build").mkdirs()
        File("build/balance_report.txt").writeText(report.toString())
        assertTrue("first engine early ($firstEngineDay)", firstEngineDay in 1..8)
        assertTrue("purchases most nights ($nightsWithPurchase)", nightsWithPurchase >= days * 0.7)
        assertTrue("choices most nights ($nightsWithChoice)", nightsWithChoice >= days * 0.6)
        assertTrue("progress through the tree (${g.unlocked.size})", g.unlocked.size >= 8)
    }
}
