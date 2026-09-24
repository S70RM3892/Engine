package com.s70rm3892.enginesim.game

import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 依頼の目標値や演出を決めるための、今のエンジンの特性 */
data class EngineProfile(
    val redline: Double,
    val idle: Double,
    val family: String,         // reciprocating / wankel / turbine / electric
    val cycle: String,          // otto4 / otto2 / diesel4 / diesel2 / steam / stirling / wankel4 / none
    val boostCapable: Boolean,  // ターボ/ルーツ付き
    val propeller: Boolean,
    val refPowerKw: Double,     // 依頼の基準出力 (実測の最高出力、無ければ目安)
    val refEff: Double,         // 依頼の基準効率
    val refNet: Double,         // 報酬の基準 [¥/s]
    val specialty: EngineSpecialty = EngineSpecialty.NONE,
) {
    val isTurbine get() = family == "turbine"
    val isElectric get() = family == "electric"
    val canAfterfire get() = !isTurbine && !isElectric && (cycle.startsWith("otto") || cycle == "wankel4")
    val canIdle get() = !isElectric && !isTurbine && cycle != "steam" && cycle != "stirling"
}

/** 1 フレーム分のテレメトリ (NativeBridge の値を詰め替えたもの) */
data class Sample(
    val rpm: Double,
    val loadNm: Double,
    val powerKw: Double,
    val thrustKn: Double,
    val fuelKw: Double,
    val efficiency: Double,
    val boostBar: Double,
    val limiter: Boolean,
    val afterfireCount: Int,
)

enum class OrderKind(val icon: String) {
    HOLD_BAND("回"), PEAK_POWER("力"), REDLINE("赤"), AFTERFIRE("炎"), IDLE_CALM("静"), EFFICIENCY("燃"),
    BOOST("過"), COMBO("連"), ENERGY("電"), BLIP("閃"), COOL_POWER("冷"), VIP_LIMIT("V"),
}

/** 依頼 1 件。progress は 0..1 */
class Order(
    val kind: OrderKind,
    val title: String,
    val detail: String,
    val difficulty: Double,
    val reward: Double,
    val stars: Int,
    val a: Double = 0.0,   // 種類ごとの目標値
    val b: Double = 0.0,
    val seconds: Double = 0.0,
) {
    var progress = 0.0
    var timer = 0.0
    var aux = 0.0           // 種類ごとの作業用 (ブリップの開始時刻など)
    val done get() = progress >= 1.0
}

/** 日替わりイベント */
enum class DayEvent(val label: String, val desc: String) {
    NONE("平常営業", "特になし"),
    HEATWAVE("猛暑", "発熱 ×1.4"),
    FUEL_SPIKE("燃料高騰", "燃料代 ×1.6"),
    PEAK_DEMAND("電力ピーク", "売電単価 ×1.5"),
    BIG_CLIENT("大口顧客", "依頼の報酬 ×2"),
    NOISE_COMPLAINT("近所の苦情", "アフターファイアは罰金、炎の依頼なし"),
    INSPECTION("抜き打ち検査", "耐久の減り ×2、依頼の ★ +1"),
    ;

    companion object {
        /** 3 日目以降、40% は平常営業 */
        fun roll(day: Int, rnd: Random): DayEvent {
            if (day < 3 || rnd.nextDouble() < 0.4) return NONE
            val list = entries.filter { it != NONE }
            return list[rnd.nextInt(list.size)]
        }
    }
}

object OrderFactory {
    fun make(p: EngineProfile, perks: Perks, event: DayEvent, rnd: Random, exclude: Set<OrderKind> = emptySet()): Order {
        val all = OrderKind.entries.filter { k ->
            when (k) {
                OrderKind.AFTERFIRE -> p.canAfterfire && event != DayEvent.NOISE_COMPLAINT
                OrderKind.IDLE_CALM -> p.canIdle
                OrderKind.BOOST -> p.boostCapable
                OrderKind.BLIP -> !p.isTurbine && !p.propeller
                OrderKind.VIP_LIMIT -> perks.vip
                else -> true
            }
        }
        // 同時に並ぶ依頼は別の種類にする
        val kinds = all.filter { it !in exclude }.ifEmpty { all }
        val kind = kinds[rnd.nextInt(kinds.size)]
        val red = p.redline
        val specMul = when (p.specialty) {
            EngineSpecialty.HIGH_REV -> if (kind in setOf(OrderKind.REDLINE, OrderKind.BLIP, OrderKind.VIP_LIMIT)) 2.0 else 1.0
            EngineSpecialty.TORQUE -> if (kind in setOf(OrderKind.PEAK_POWER, OrderKind.HOLD_BAND, OrderKind.ENERGY)) 2.0 else 1.0
            EngineSpecialty.ECO -> if (kind in setOf(OrderKind.EFFICIENCY, OrderKind.ENERGY, OrderKind.COOL_POWER)) 2.0 else 1.0
            EngineSpecialty.NONE -> 1.0
        }
        fun order(title: String, detail: String, diff: Double, a: Double = 0.0, b: Double = 0.0, sec: Double = 0.0, stars: Int = 1): Order {
            val eventMul = if (event == DayEvent.BIG_CLIENT) 2.0 else 1.0
            val reward = max(3.0, p.refNet) * 6.0 * diff * perks.orderRewardMul * specMul * eventMul
            // ★ は難しい依頼 (難度 1.5 以上) だけ。評判で 2 個目が出ることがある
            val base = if (diff >= 1.5) stars else 0
            val st = if (base == 0) 0 else base + (if (event == DayEvent.INSPECTION) 1 else 0) + (if (rnd.nextDouble() < perks.extraStarChance) 1 else 0)
            return Order(kind, title, detail, diff, reward, st, a, b, sec)
        }
        fun rpmStr(v: Double) = "%,d".format(v.toInt())
        return when (kind) {
            OrderKind.HOLD_BAND -> {
                val band = rnd.nextInt(3)
                val (lo, hi, name) = when (band) {
                    0 -> Triple(max(p.idle * 1.3, red * 0.25), red * 0.45, "低回転")
                    1 -> Triple(red * 0.45, red * 0.68, "中回転")
                    else -> Triple(red * 0.78, red * 0.94, "高回転")
                }
                val sec = 4.0 + rnd.nextInt(4)
                order("$name キープ", "${rpmStr(lo)}〜${rpmStr(hi)} rpm を ${sec.toInt()} 秒", 0.6 + 0.1 * sec, lo, hi, sec)
            }
            OrderKind.PEAK_POWER -> {
                val f = 0.75 + 0.05 * rnd.nextInt(5)
                order("出力テスト", "${"%.0f".format(p.refPowerKw * f)} kW を出す", 0.8 + f, p.refPowerKw * f)
            }
            OrderKind.REDLINE -> order("レッドライン", "${rpmStr(red * 0.97)} rpm に届かせる (リミッターは禁止)", 1.0, red * 0.97)
            OrderKind.AFTERFIRE -> {
                val n = 2 + rnd.nextInt(3)
                order("アフターファイア", "高回転からアクセルを抜いて $n 回鳴らす", 0.6 + 0.3 * n, n.toDouble())
            }
            OrderKind.IDLE_CALM -> order("暖機運転", "アクセルを離してアイドル ${rpmStr(p.idle)} rpm 付近を 5 秒", 0.6, sec = 5.0)
            OrderKind.EFFICIENCY -> {
                val e = max(0.05, p.refEff * 0.9)
                order("燃費計測", "効率 ${"%.0f".format(e * 100)}% 以上を 5 秒", 1.6, e, sec = 5.0)
            }
            OrderKind.BOOST -> order("ブースト計測", "過給圧を上げて 5 秒保つ", 1.3, sec = 5.0)
            OrderKind.COMBO -> {
                val c = min(perks.comboCap, 1.8 + 0.2 * rnd.nextInt(5))
                order("コンボ", "コンボ ×${"%.1f".format(c)} に到達", 0.7 + 0.5 * (c - 1.5), c)
            }
            OrderKind.ENERGY -> {
                val kj = p.refPowerKw * (12.0 + 4 * rnd.nextInt(3))
                order("納品", "${"%.0f".format(kj)} kJ を発電して納める", 1.4, kj)
            }
            OrderKind.BLIP -> order("ブリッピング", "${rpmStr(red * 0.35)} 以下から ${rpmStr(red * 0.8)} まで 1.2 秒以内に吹かす", 1.5,
                red * 0.35, red * 0.8, 1.2)
            OrderKind.COOL_POWER -> order("冷静な全力", "熱 40% 以下のまま 最高出力の 60% を 6 秒", 1.8, p.refPowerKw * 0.6, sec = 6.0)
            OrderKind.VIP_LIMIT -> order("VIP: 限界走行", "${rpmStr(red * 0.9)}〜${rpmStr(red * 0.97)} rpm を 10 秒 (リミッター禁止)", 4.0,
                red * 0.9, red * 0.97, 10.0, stars = 2)
        }
    }
}

/**
 * 1 日 (シフト) のゲーム進行。テレメトリを受け取り、収入・コンボ・熱・耐久・依頼を更新する。
 * Android にもネイティブにも依存しない (ユニットテストとバランス計算に使う)。
 */
class RunSession(
    val profile: EngineProfile,
    private val perks: Perks,
    val event: DayEvent,
    private val multiplier: Double,
    seed: Long = System.nanoTime(),
) {
    /** UI 向けの出来事 */
    sealed class Ev {
        data class Text(val text: String, val color: Int, val big: Boolean = false) : Ev()
        data class Popup(val text: String, val color: Int) : Ev()
        data class OrderDone(val order: Order) : Ev()
        object Overheat : Ev()
        object Blown : Ev()
        object SweetIn : Ev()
    }

    private val rnd = Random(seed)
    val duration = perks.shiftSeconds
    var time = 0.0
        private set
    var earned = 0.0          // 今日の純収入 (電力 − 燃料 + ボーナス)
        private set
    var orderEarned = 0.0
        private set
    var starsEarned = 0
        private set
    var ordersDone = 0
        private set
    var afterfires = 0
        private set
    var sweetSeconds = 0.0
        private set
    var combo = 1.0
        private set
    var heat = 0.0
        private set
    var hp = perks.maxHp
        private set
    var overheat = 0.0
        private set
    var blown = false
        private set
    var incomeRate = 0.0      // 平滑化した ¥/s
        private set
    var maxPowerKw = 0.0
        private set
    var maxEff = 0.0
        private set
    val orders = ArrayList<Order>()
    private var lastAfterfire = -1
    private var wasLimiter = false
    private var wasSweet = false
    private var energyKj = HashMap<Order, Double>()

    val sweetLo: Double
    val sweetHi: Double

    init {
        val base = sweetZone(profile.family, profile.cycle, profile.propeller)
        val shift = when (profile.specialty) {
            EngineSpecialty.HIGH_REV -> 0.04
            EngineSpecialty.TORQUE -> -0.08
            else -> 0.0
        }
        sweetLo = (base.first + shift - perks.sweetWiden).coerceAtLeast(0.1)
        sweetHi = base.second + shift + perks.sweetWiden
        repeat(perks.orderSlots) { orders += OrderFactory.make(profile, perks, event, rnd, orders.map { it.kind }.toSet()) }
    }

    val finished get() = blown || time >= duration
    val timeLeft get() = max(0.0, duration - time)

    /** ペダルを離しているときのアシスタント (スイートゾーン中央を狙う P 制御) */
    fun assistThrottle(rpm: Double): Double {
        val lv = perks.assistLevel
        if (lv <= 0) return 0.0
        val target = profile.redline * (sweetLo + sweetHi) / 2
        val err = (target - rpm) / profile.redline
        return (0.35 + 4.0 * err).coerceIn(0.0, min(1.0, 0.18 * lv))
    }

    /** 1 ステップ進める。throttle は実際にエンジンへ送った開度 */
    fun step(dt: Double, s: Sample, throttle: Double, out: MutableList<Ev>) {
        if (finished) return
        time += dt
        val red = profile.redline
        val frac = s.rpm / red
        val outKw = if (profile.isTurbine) s.powerKw + s.thrustKn * GameRules.THRUST_SPEED else s.loadNm * s.rpm * 2 * Math.PI / 60 / 1000
        val fuelKw = when (profile.cycle) {
            "steam" -> outKw / 0.10
            "stirling" -> 0.0
            else -> s.fuelKw
        }
        var fuelPrice = when {
            profile.isElectric -> GameRules.ELECTRIC_PRICE
            profile.cycle == "steam" -> GameRules.COAL_PRICE
            else -> GameRules.FUEL_PRICE
        } * perks.fuelMul
        if (profile.specialty == EngineSpecialty.ECO) fuelPrice *= 0.85
        if (event == DayEvent.FUEL_SPIKE) fuelPrice *= 1.6
        val powerPrice = GameRules.POWER_PRICE * perks.powerPriceMul * (if (event == DayEvent.PEAK_DEMAND) 1.5 else 1.0)
        maxPowerKw = max(maxPowerKw, outKw)
        if (outKw > 0.3 * maxPowerKw) maxEff = max(maxEff, s.efficiency)

        // コンボ
        val sweet = frac in sweetLo..sweetHi && outKw > 0.5
        if (s.limiter && !wasLimiter) {
            if (combo > 1.3) out += Ev.Text("COMBO BREAK", COLOR_RED)
            combo = 1.0
        }
        combo = if (sweet) min(perks.comboCap, combo + dt * 0.18 * perks.comboRate) else max(1.0, combo - dt * 0.6)
        if (sweet && !wasSweet) out += Ev.SweetIn
        if (sweet) sweetSeconds += dt
        wasLimiter = s.limiter
        wasSweet = sweet

        val income = (outKw * powerPrice * combo - fuelKw * fuelPrice) * multiplier
        earned += income * dt
        incomeRate += (income - incomeRate) * min(1.0, dt / 1.2)

        // アフターファイア
        if (lastAfterfire >= 0 && s.afterfireCount > lastAfterfire) {
            val n = s.afterfireCount - lastAfterfire
            afterfires += n
            if (event == DayEvent.NOISE_COMPLAINT) {
                val fine = max(1.0, incomeRate) * 1.0 * n
                earned -= fine
                out += Ev.Text("苦情! −¥${fmtMoney(fine)}", COLOR_RED)
            } else {
                val bonus = max(1.0, incomeRate) * 1.5 * perks.afterfireMul * n
                earned += bonus
                out += Ev.Text("BANG! +¥${fmtMoney(bonus)}", COLOR_ORANGE, big = true)
            }
        }
        lastAfterfire = s.afterfireCount

        // 熱と耐久
        var heatMul = perks.heatMul * (if (event == DayEvent.HEATWAVE) 1.4 else 1.0)
        if (profile.specialty == EngineSpecialty.HIGH_REV) heatMul *= 1.15
        if (profile.isElectric) heatMul *= 0.6 else if (profile.cycle.startsWith("diesel")) heatMul *= 0.8
        val dmgMul = perks.damageMul * (if (event == DayEvent.INSPECTION) 2.0 else 1.0)
        if (overheat > 0) {
            overheat -= dt
            heat = max(0.0, heat - dt * 0.2)
            if (overheat <= 0) { overheat = 0.0; out += Ev.Text("再始動", COLOR_GREEN) }
        } else {
            val cool = 0.035 + if (throttle < 0.3) 0.05 else 0.0
            heat = (heat + dt * (0.07 * throttle * throttle * (0.5 + frac) * heatMul - cool)).coerceIn(0.0, 1.0)
            if (heat >= 1.0) {
                overheat = perks.overheatStop
                combo = 1.0
                damage(20.0 * dmgMul, out)
                out += Ev.Overheat
            }
        }
        if (s.limiter) damage(6.0 * dt * dmgMul, out)
        if (heat > 0.9) damage(3.0 * dt * dmgMul, out)

        // 依頼
        updateOrders(dt, s, frac, outKw, out)
    }

    private fun damage(v: Double, out: MutableList<Ev>) {
        if (blown) return
        hp -= v
        if (hp <= 0) {
            hp = 0.0
            blown = true
            earned *= GameRules.BLOWN_KEEP
            out += Ev.Blown
        }
    }

    private fun updateOrders(dt: Double, s: Sample, frac: Double, outKw: Double, out: MutableList<Ev>) {
        val sp = perks.orderSpeed
        for (o in orders) {
            if (o.done) continue
            val rpm = s.rpm
            when (o.kind) {
                OrderKind.HOLD_BAND, OrderKind.VIP_LIMIT -> {
                    val ok = rpm in o.a..o.b && !(o.kind == OrderKind.VIP_LIMIT && s.limiter)
                    o.progress = if (ok) o.progress + dt * sp / o.seconds else max(0.0, o.progress - dt * 0.5 / o.seconds)
                }
                OrderKind.PEAK_POWER -> o.progress = max(o.progress, min(1.0, outKw / o.a))
                OrderKind.REDLINE -> {
                    if (s.limiter) o.progress = 0.0 else o.progress = max(o.progress, min(1.0, rpm / o.a))
                }
                OrderKind.AFTERFIRE -> o.progress = min(1.0, (afterfires - o.aux) / o.a)
                OrderKind.IDLE_CALM -> {
                    val ok = kotlin.math.abs(rpm - profile.idle) < profile.idle * 0.2 && s.loadNm >= 0
                    o.progress = if (ok) o.progress + dt * sp / o.seconds else o.progress
                }
                OrderKind.EFFICIENCY -> {
                    o.progress = if (s.efficiency >= o.a) o.progress + dt * sp / o.seconds else max(0.0, o.progress - dt * 0.3 / o.seconds)
                }
                OrderKind.BOOST -> {
                    o.progress = if (s.boostBar > 0.3) o.progress + dt * sp / o.seconds else max(0.0, o.progress - dt * 0.3 / o.seconds)
                }
                OrderKind.COMBO -> o.progress = max(o.progress, min(1.0, (combo - 1) / (o.a - 1)))
                OrderKind.ENERGY -> {
                    val e = (energyKj[o] ?: 0.0) + max(0.0, outKw) * dt * sp
                    energyKj[o] = e
                    o.progress = min(1.0, e / o.a)
                }
                OrderKind.BLIP -> {
                    // aux: 低回転から離れた時刻 (−1 = 待機)
                    if (rpm <= o.a) o.aux = time
                    else if (o.aux > 0 && rpm >= o.b) {
                        if (time - o.aux <= o.seconds / sp) o.progress = 1.0
                        o.aux = 0.0
                    }
                }
                OrderKind.COOL_POWER -> {
                    val ok = heat <= 0.4 && outKw >= o.a
                    o.progress = if (ok) o.progress + dt * sp / o.seconds else max(0.0, o.progress - dt * 0.3 / o.seconds)
                }
            }
            if (o.done) complete(o, out)
        }
        // 達成した依頼は 3 秒後に新しい依頼と入れ替える
        for (i in orders.indices) {
            if (orders[i].done) {
                orders[i].timer += dt
                if (orders[i].timer > 3.0) {
                    val n = OrderFactory.make(profile, perks, event, rnd, orders.map { it.kind }.toSet())
                    if (n.kind == OrderKind.AFTERFIRE) n.aux = afterfires.toDouble()
                    orders[i] = n
                }
            }
        }
    }

    private fun complete(o: Order, out: MutableList<Ev>) {
        o.progress = 1.0
        ordersDone++
        orderEarned += o.reward
        earned += o.reward
        starsEarned += o.stars
        out += Ev.OrderDone(o)
    }

    /** オートブリップ: アシスタントがブリッピング依頼を処理するための開度 (未対応なら null) */
    fun autoBlipThrottle(rpm: Double): Double? {
        if (!perks.autoBlip) return null
        val o = orders.firstOrNull { it.kind == OrderKind.BLIP && !it.done } ?: return null
        return if (rpm > o.a && o.aux <= 0.0) 0.0 else 1.0
    }

    fun result() = RunResult(
        earned = earned, orderEarned = orderEarned, stars = starsEarned, orders = ordersDone, afterfires = afterfires,
        sweetSeconds = sweetSeconds, blown = blown, hpLeft = hp, maxHp = perks.maxHp, maxPowerKw = maxPowerKw, maxEff = maxEff,
        event = event,
    )

    companion object {
        const val COLOR_RED = 0xFFEB4034.toInt()
        const val COLOR_GREEN = 0xFF50D278.toInt()
        const val COLOR_ORANGE = 0xFFFF7828.toInt()

        /** エンジン特性ごとのスイートゾーン (レッドライン比) */
        fun sweetZone(family: String, cycle: String, propeller: Boolean): Pair<Double, Double> = when {
            family == "electric" -> 0.30 to 0.62
            family == "turbine" -> 0.88 to 1.06
            cycle == "steam" -> 0.80 to 1.35
            cycle == "stirling" -> 0.80 to 1.10
            cycle.startsWith("diesel") -> 0.62 to 0.92
            propeller -> 0.78 to 1.0
            else -> GameRules.SWEET_LO to GameRules.SWEET_HI
        }

        /** 自動ダイナモの負荷率 (x = (rpm − idle)/(0.9·red − idle)) */
        fun dynoLoad(x: Double): Double =
            if (x <= 1.0) 0.9 * Math.pow(x.coerceAtLeast(0.0), 1.6) else min(3.0, 0.9 + 6.0 * (x - 1.0))
    }
}

/** 1 日の結果 */
data class RunResult(
    val earned: Double,
    val orderEarned: Double,
    val stars: Int,
    val orders: Int,
    val afterfires: Int,
    val sweetSeconds: Double,
    val blown: Boolean,
    val hpLeft: Double,
    val maxHp: Double,
    val maxPowerKw: Double,
    val maxEff: Double,
    val event: DayEvent,
)

/** 1 日を締める: 稼ぎと ★ を反映し、記録を更新する */
fun GameState.closeDay(r: RunResult, engineKey: String) {
    earn(max(0.0, r.earned))
    if (r.earned < 0) money += r.earned
    stars += r.stars
    stats.days++
    stats.bestDay = max(stats.bestDay, r.earned)
    stats.ordersDone += r.orders
    stats.afterfires += r.afterfires
    stats.sweetSeconds += r.sweetSeconds
    if (r.blown) stats.blown++
    if (!r.blown && r.hpLeft >= r.maxHp - 1e-6 && r.orders >= 3) stats.cleanDays++
    val p = prog(engineKey)
    p.bestPowerKw = max(p.bestPowerKw, r.maxPowerKw)
    p.bestEff = max(p.bestEff, r.maxEff)
    day++
}
