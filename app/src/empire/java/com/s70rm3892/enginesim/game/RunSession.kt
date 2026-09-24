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
        /** 帯に居続けた段階 (1 = NICE 3 秒, 2 = GREAT 6 秒, 3 = PERFECT 10 秒) */
        data class Groove(val level: Int) : Ev()
        object NitroReady : Ev()
        object NitroStart : Ev()
        object NitroEnd : Ev()
        object GoldenSpawn : Ev()
        data class GoldenGot(val text: String, val color: Int) : Ev()
        object Knock : Ev()
        object Fever : Ev()
        /** 今日の稼ぎが節目 (¥1K, ¥10K, …) を越えた */
        data class Milestone(val amount: Double) : Ev()
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

    // ターゲット帯 (レッドライン比)。針を帯に入れているとコンボが溜まる。帯は滑らかに動き、ときどき跳ねる
    var bandCenter = 0.0
        private set
    var bandWidth = 0.1
        private set
    private var bandFrom = 0.0
    private var bandTo = 0.0
    private var bandT = 0.0         // 移動の進み (0..1)
    private var bandMove = 1.0      // 移動にかける秒数
    private var bandHold = 0.0      // 次の移動までの静止時間
    private val bandLo: Double
    private val bandHi: Double
    var inBand = false
        private set
    var grooveTime = 0.0            // 帯に居続けている秒数
        private set
    private var grooveLevel = 0

    // ニトロ
    var nitro = 0.0                 // ゲージ 0..1
        private set
    var nitroTime = 0.0             // 残り秒数
        private set
    private var nitroReadyShown = false

    // フレンジー (金のボルトの当たり): 収入倍率
    var frenzyTime = 0.0
        private set

    // 金のボルト
    var goldenLife = 0.0            // 表示中の残り秒数 (0 = 出ていない)
        private set
    var goldenX = 0.5               // 画面上の位置 (0..1)
        private set
    var goldenY = 0.5
        private set
    private var goldenTimer = 0.0
    var goldenCollected = 0
        private set

    // ノッキング (低回転で全開 = ラグ)
    private var knockTimer = 0.0
    private var knockWarn = 0.0
    var knocking = false
        private set

    private var fever = false
    /** 今日のオーバーヒート回数 (回を重ねるほど停止時間とダメージが増える) */
    var overheatCount = 0
        private set
    /** 熱 72〜92% を保っている (攻めた運転ボーナス ×1.25) */
    var hot = false
        private set
    private var nextMilestone = 1000.0
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
        // 帯が動く範囲: スイートゾーンの少し下から上端まで。応答の遅い機関 (タービン/蒸気) は帯を広く
        bandLo = (sweetLo - 0.22).coerceAtLeast(0.15)
        bandHi = min(if (profile.isTurbine) 1.04 else 0.96, sweetHi)
        bandCenter = (sweetLo + sweetHi) / 2
        bandFrom = bandCenter
        bandTo = bandCenter
        bandHold = 3.0
        goldenTimer = nextGoldenDelay()
    }

    private val slowEngine get() = profile.isTurbine || profile.cycle == "steam" || profile.cycle == "stirling"

    private fun nextGoldenDelay() = (14.0 + rnd.nextDouble() * 12.0) / perks.goldenRate

    /** 帯の目標位置を更新 (静止 → 滑らかに移動 → 静止、たまに高回転へのスパイク) */
    private fun updateBand(dt: Double) {
        if (bandT < 1.0) {
            bandT = min(1.0, bandT + dt / bandMove)
            val e = bandT * bandT * (3 - 2 * bandT)      // smoothstep
            bandCenter = bandFrom + (bandTo - bandFrom) * e
            return
        }
        bandHold -= dt
        if (bandHold > 0) return
        bandFrom = bandCenter
        val spike = rnd.nextDouble() < 0.18
        bandTo = if (spike) bandHi - 0.02 else bandLo + rnd.nextDouble() * (bandHi - bandLo)
        bandMove = (if (slowEngine) 2.2 else 0.9) + rnd.nextDouble() * 0.8
        bandHold = if (spike) 1.6 else 2.2 + rnd.nextDouble() * 2.5
        bandT = 0.0
    }

    /** ニトロを使う (ゲージ満タン時) */
    fun fireNitro(out: MutableList<Ev>): Boolean {
        if (nitro < 1.0 || nitroTime > 0 || finished) return false
        nitro = 0.0
        nitroTime = perks.nitroSeconds
        nitroReadyShown = false
        out += Ev.NitroStart
        return true
    }

    /** 金のボルトをタップした */
    fun collectGolden(out: MutableList<Ev>): Boolean {
        if (goldenLife <= 0 || finished) return false
        goldenLife = 0.0
        goldenCollected++
        val roll = rnd.nextDouble()
        when {
            roll < 0.42 -> {
                val v = max(30.0, incomeRate) * 12.0
                earned += v
                out += Ev.GoldenGot("ボーナス +¥${fmtMoney(v)}", COLOR_GOLD)
            }
            roll < 0.67 -> { frenzyTime = 7.0; out += Ev.GoldenGot("フレンジー! 収入 ×4", COLOR_GOLD) }
            roll < 0.82 -> { heat = 0.0; hp = min(perks.maxHp, hp + 20.0); out += Ev.GoldenGot("クールダウン & 修理", COLOR_GREEN) }
            roll < 0.95 -> { nitro = 1.0; out += Ev.GoldenGot("ニトロ満タン!", COLOR_CYAN) }
            else -> { starsEarned += 1; out += Ev.GoldenGot("★ +1", COLOR_VIOLET) }
        }
        nitro = min(1.0, nitro + 0.15)
        return true
    }

    val finished get() = blown || time >= duration
    val timeLeft get() = max(0.0, duration - time)

    /** ペダルを離しているときのアシスタント (ターゲット帯の中央を狙う P 制御。Lv が低いと開度の上限が低い) */
    fun assistThrottle(rpm: Double): Double {
        val lv = perks.assistLevel
        if (lv <= 0) return 0.0
        val err = bandCenter - rpm / profile.redline
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

        // ターゲット帯とコンボ
        updateBand(dt)
        val nitroOn = nitroTime > 0
        val comboFrac = ((combo - 1) / max(0.1, perks.comboCap - 1)).coerceIn(0.0, 1.0)
        bandWidth = (0.13 + perks.sweetWiden) * (if (slowEngine) 1.7 else 1.0) * (1.0 - 0.3 * comboFrac) * (if (nitroOn) 1.5 else 1.0)
        inBand = kotlin.math.abs(frac - bandCenter) <= bandWidth / 2 && outKw > 0.3
        if (s.limiter && !wasLimiter) {
            if (combo > 1.3) out += Ev.Text("COMBO BREAK", COLOR_RED)
            combo = 1.0
        }
        val gain = 0.22 * perks.comboRate * (if (nitroOn) 2.0 else 1.0)
        combo = if (inBand) min(perks.comboCap, combo + dt * gain) else max(1.0, combo - dt * 0.25)
        if (inBand && !wasSweet) out += Ev.SweetIn
        if (inBand) {
            sweetSeconds += dt
            grooveTime += dt
            nitro = min(1.0, nitro + dt * 0.045 * perks.nitroRate)
            val lvl = when { grooveTime >= 10 -> 3; grooveTime >= 6 -> 2; grooveTime >= 3 -> 1; else -> 0 }
            if (lvl > grooveLevel) { grooveLevel = lvl; out += Ev.Groove(lvl) }
        } else {
            grooveTime = 0.0
            grooveLevel = 0
        }
        val atCap = combo >= perks.comboCap - 1e-6
        if (atCap && !fever) out += Ev.Fever
        fever = atCap
        if (nitro >= 1.0 && !nitroReadyShown && nitroTime <= 0) { nitroReadyShown = true; out += Ev.NitroReady }
        if (nitroOn) {
            nitroTime -= dt
            if (nitroTime <= 0) { nitroTime = 0.0; out += Ev.NitroEnd }
        }
        if (frenzyTime > 0) frenzyTime = max(0.0, frenzyTime - dt)
        wasLimiter = s.limiter
        wasSweet = inBand

        // 金のボルト
        if (goldenLife > 0) {
            goldenLife -= dt
            if (goldenLife <= 0) { goldenLife = 0.0; goldenTimer = nextGoldenDelay() }
        } else {
            goldenTimer -= dt
            if (goldenTimer <= 0) {
                goldenLife = 5.0
                goldenX = 0.25 + rnd.nextDouble() * 0.45
                goldenY = 0.30 + rnd.nextDouble() * 0.35
                goldenTimer = nextGoldenDelay()
                out += Ev.GoldenSpawn
            }
        }

        // 熱をぎりぎりで保つ攻めた運転はボーナス。越えてオーバーヒートすると重いペナルティ
        hot = overheat <= 0 && inBand && heat in HOT_LO..HOT_HI
        val boost = (if (nitroOn) 2.0 else 1.0) * (if (frenzyTime > 0) 4.0 else 1.0) * (if (hot) HOT_BONUS else 1.0)
        // 帯 = 電力会社の指令値。指令どおりの回転で出した電力は満額、外れた電力は買い叩かれる
        val dispatch = if (inBand) 1.0 else OFF_BAND_PRICE
        val income = (outKw * powerPrice * dispatch * combo * boost - fuelKw * fuelPrice) * multiplier
        earned += income * dt
        incomeRate += (income - incomeRate) * min(1.0, dt / 1.2)
        while (earned >= nextMilestone) { out += Ev.Milestone(nextMilestone); nextMilestone *= 10 }

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
        if (nitroOn) heatMul *= 1.8
        val dmgMul = perks.damageMul * (if (event == DayEvent.INSPECTION) 2.0 else 1.0)
        // 帯の中 (効率の良い回転) は発熱が少なく、外すと多い
        heatMul *= if (inBand) 0.7 else 1.15
        if (overheat > 0) {
            // 焼けたエンジンはゆっくりとしか冷えず、55% までしか下がらない (オーバーヒートは冷却の近道にならない)
            overheat -= dt
            heat = max(0.55, heat - dt * 0.06)
            if (overheat <= 0) { overheat = 0.0; out += Ev.Text("再始動", COLOR_GREEN) }
        } else {
            // 自分でアクセルを抜けば素早く冷える
            val cool = 0.035 + if (throttle < 0.3) 0.10 else 0.0
            heat = (heat + dt * (0.07 * throttle * throttle * (0.5 + frac) * heatMul - cool)).coerceIn(0.0, 1.0)
            if (heat >= 1.0) {
                overheatCount++
                val n = overheatCount - 1
                overheat = perks.overheatStop + 2.0 * n
                combo = 1.0
                nitro = 0.0
                nitroReadyShown = false
                damage(20.0 * Math.pow(1.5, n.toDouble()) * dmgMul, out)
                out += Ev.Overheat
            }
        }
        if (s.limiter) damage(6.0 * dt * dmgMul, out)
        if (heat > 0.94) damage(3.0 * dt * dmgMul, out)
        // ノッキング: 低回転で全開 (ラグ) を続けるとカンカン鳴って耐久が減る
        val knockProne = !profile.isElectric && !profile.isTurbine && profile.cycle != "steam" && profile.cycle != "stirling"
        if (knockProne && throttle > 0.85 && frac < 0.4 && overheat <= 0) knockTimer += dt else knockTimer = max(0.0, knockTimer - 2 * dt)
        knocking = knockTimer > 0.6
        knockWarn = max(0.0, knockWarn - dt)
        if (knocking) {
            damage(5.0 * dt * dmgMul, out)
            if (knockWarn <= 0) { knockWarn = 1.5; out += Ev.Knock }
        }

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
        nitro = min(1.0, nitro + 0.25)
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
        const val COLOR_GOLD = 0xFFFFC83C.toInt()
        const val HOT_LO = 0.72
        const val HOT_HI = 0.92
        const val HOT_BONUS = 1.25
        const val OFF_BAND_PRICE = 0.6
        const val COLOR_CYAN = 0xFF40C8E6.toInt()
        const val COLOR_VIOLET = 0xFFAA78FF.toInt()

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
