package com.s70rm3892.enginesim.game

import org.json.JSONObject
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 「ENGINE EMPIRE」— エンジンの物理特性をそのまま使うインクリメンタルゲームのモデル (Android 非依存)。
 *
 * 収入 = ダイナモ (またはプロペラ) が吸収した実出力 [kW] × 単価 × 倍率
 * 支出 = 燃料 (電動機は電力) の投入熱量 [kW] × 燃料単価
 * → 熱効率の良いエンジンほど儲かり、過給・高回転はリスク (熱) と引き換えに大出力。
 */
object GameRules {
    const val POWER_PRICE = 1.0        // ¥ / (kW·s)
    const val FUEL_PRICE = 0.12        // ¥ / (kW·s)  燃料投入熱量あたり
    const val ELECTRIC_PRICE = 0.35    // 電動機の電力単価 (燃料より高いが効率が高い)
    const val THRUST_SPEED = 250.0     // ジェット: 推力 × 250m/s (巡航速度) を推進仕事率とみなす
    const val OFFLINE_RATE = 0.5       // オフライン収入の割合
    const val OFFLINE_CAP_S = 4 * 3600.0
    const val COMBO_MAX = 3.0
    const val SWEET_LO = 0.72          // コンボが溜まる回転域 (レッドライン比)
    const val SWEET_HI = 0.95

    fun tpFromEarnings(totalEarned: Double): Int = floor(sqrt(max(0.0, totalEarned) / 1e6)).toInt()
    fun prestigeMultiplier(tp: Int): Double = 1.0 + 0.1 * tp
    /** 解放済みエンジン 1 台ごとに全体収入 +25% (コレクション・ボーナス) */
    fun collectionMultiplier(unlocked: Int): Double = 1.0 + 0.25 * max(0, unlocked - 1)
}

/** アップグレードの種類。familyMask で対象を絞る。 */
enum class UpgradeKind(
    val label: String,
    val desc: String,
    val maxLevel: Int,
    val costFactor: Double,   // エンジン価格に対する初期費用
    val growth: Double,       // レベルごとの費用倍率
    val recip: Boolean = true,
    val turbine: Boolean = false,
    val electric: Boolean = false,
) {
    BORE("ボアアップ", "ボア +1.5% → 排気量とトルク増", 10, 0.6, 1.35),
    STROKE("ストロークアップ", "ストローク +1.5% → 低速トルク増", 8, 0.7, 1.4),
    COMPRESSION("高圧縮ピストン", "圧縮比 +0.4 → 熱効率アップ", 8, 0.8, 1.45),
    CAMS("ハイカム&ポート研磨", "吸排気の流れ +6% → 高回転の充填効率", 10, 0.9, 1.4),
    LIGHTEN("軽量化&低フリクション", "摩擦 −5% / 慣性 −6% → 吹け上がり", 8, 0.75, 1.4),
    REV("レブリミット上昇", "レッドライン +3%", 8, 1.0, 1.5),
    BOOST("過給機", "Lv1 でターボ装着、以降ブースト +0.2bar", 8, 2.0, 1.55),
    EXHAUST("排気チューン", "背圧 −8% → 高回転出力と排気音", 6, 0.5, 1.4),
    RADIATOR("大型ラジエーター", "冷却 +12% / 熱ゲージが冷えやすい", 10, 0.4, 1.3, recip = true, turbine = true, electric = true),
    CYLINDERS("気筒追加", "気筒数を増やす (直列+1 / V・水平対向+2)", 8, 6.0, 2.2),
    COMBUSTOR("燃焼器改良", "推力/軸出力 +8%", 10, 1.0, 1.45, recip = false, turbine = true),
    SPOOL("スプール応答", "加速ラグ −8%", 6, 0.8, 1.4, recip = false, turbine = true),
    MAGNETS("高性能磁石&巻線", "定格トルク/出力 +8%", 10, 1.0, 1.45, recip = false, electric = true),
}

/** ゲームに登場するエンジン (解放順)。custom は n 気筒生成器、catalog は組み込み JSON を改造。 */
data class GameEngineDef(
    val key: String,
    val name: String,
    val price: Double,          // 解放価格 (0 = 初期)
    val catalogId: String? = null,
    val custom: JSONObject? = null,
    val flavor: String,
) {
    val cylinderStep: Int
        get() = when (custom?.optString("layout")) {
            "inline" -> 1
            "v", "flat" -> 2
            "radial" -> 1
            else -> 0
        }
    /** 価格ゼロのエンジンのアップグレード基準価格 */
    val basePrice: Double get() = max(price, 150.0)
}

object GameRoster {
    private fun custom(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    val engines = listOf(
        GameEngineDef("tiller", "耕運機 単気筒 230cc", 0.0,
            custom = custom("name" to "耕運機 単気筒", "layout" to "inline", "cylinders" to 1, "boreMm" to 70, "strokeMm" to 60,
                "compressionRatio" to 9.0, "idleRpm" to 1300, "redlineRpm" to 6500),
            flavor = "すべてはここから。鼓動の大きな単気筒。"),
        GameEngineDef("twin", "並列2気筒 700cc", 800.0,
            custom = custom("name" to "並列2気筒", "layout" to "inline", "cylinders" to 2, "boreMm" to 80, "strokeMm" to 70,
                "compressionRatio" to 11.5, "idleRpm" to 1200, "redlineRpm" to 9500),
            flavor = "高回転まで回るバイク用ツイン。"),
        GameEngineDef("rotary", "ロータリー 2ローター 13B", 5_000.0, catalogId = "wankel2_13b",
            flavor = "9000rpm まで滑らかに回る。ただし燃費は悪い。"),
        GameEngineDef("flat6", "水平対向6気筒 3.0L", 25_000.0,
            custom = custom("name" to "水平対向6気筒", "layout" to "flat", "cylinders" to 6, "boreMm" to 91, "strokeMm" to 76,
                "compressionRatio" to 12.5, "idleRpm" to 850, "redlineRpm" to 9000),
            flavor = "低重心の完全バランス。気筒追加で 8, 10, 12 気筒へ。"),
        GameEngineDef("pmsm", "永久磁石同期モータ 210kW", 60_000.0, catalogId = "motor_pmsm",
            flavor = "熱効率 90%。静かだが電気代がかかる。"),
        GameEngineDef("v8", "V8 5.0L クロスプレーン", 150_000.0,
            custom = custom("name" to "V8 クロスプレーン", "layout" to "v", "cylinders" to 8, "bankAngle" to 90, "crossplane" to true,
                "boreMm" to 93, "strokeMm" to 92.7, "compressionRatio" to 11.0, "idleRpm" to 650, "redlineRpm" to 7000),
            flavor = "ドロドロの不等間隔排気。アメリカンな大トルク。"),
        GameEngineDef("i4t", "直列4気筒 2.0L ターボ", 400_000.0, catalogId = "i4_20t",
            flavor = "過給ラグを越えれば大トルク。ブローオフの音が快感。"),
        GameEngineDef("v12", "V12 6.0L", 1_000_000.0,
            custom = custom("name" to "V12", "layout" to "v", "cylinders" to 12, "bankAngle" to 60, "boreMm" to 89, "strokeMm" to 80,
                "compressionRatio" to 11.2, "idleRpm" to 800, "redlineRpm" to 8500),
            flavor = "60°毎の等間隔点火。気筒追加で V16, V20, V24 へ。"),
        GameEngineDef("radial", "星型9気筒 R-1820 級", 3_000_000.0, catalogId = "r9_r1820",
            flavor = "30L の大排気量。プロペラが出力を吸収する。"),
        GameEngineDef("jumo", "ユンカース Jumo 205", 8_000_000.0, catalogId = "jumo205",
            flavor = "対向ピストン 2 スト・ディーゼル。効率の鬼。"),
        GameEngineDef("turboshaft", "ターボシャフト", 25_000_000.0, catalogId = "turboshaft",
            flavor = "1.4MW の軸出力。スプールラグと EGT に注意。"),
        GameEngineDef("deltic", "ネイピア デルティック 18気筒", 60_000_000.0, catalogId = "deltic18",
            flavor = "三角形のクランク配置、88L・1800kW。"),
        GameEngineDef("turbofan", "高バイパス ターボファン", 150_000_000.0, catalogId = "turbofan",
            flavor = "推力 120kN。帝国の頂点。"),
    )

    fun byKey(key: String) = engines.firstOrNull { it.key == key } ?: engines.first()
}

/** 1 エンジン分の進行状況 */
class EngineProgress(val key: String) {
    val levels = mutableMapOf<UpgradeKind, Int>()
    var bestDragTime = 0.0
    fun level(u: UpgradeKind) = levels[u] ?: 0

    fun toJson() = JSONObject().apply {
        put("key", key)
        put("best", bestDragTime)
        put("levels", JSONObject().apply { levels.forEach { (k, v) -> put(k.name, v) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = EngineProgress(o.getString("key")).apply {
            bestDragTime = o.optDouble("best", 0.0)
            val lv = o.optJSONObject("levels")
            lv?.keys()?.forEach { k -> runCatching { levels[UpgradeKind.valueOf(k)] = lv.getInt(k) } }
        }
    }
}

/** ゲーム全体の状態 (セーブ対象) */
class GameState {
    var money = 0.0
    var totalEarned = 0.0
    var runEarned = 0.0           // 今回のプレステージ周回での獲得額
    var techPoints = 0
    var automation = 0            // 自動スロットル Lv (0..10)
    var current = GameRoster.engines.first().key
    val unlocked = mutableSetOf(GameRoster.engines.first().key)
    val progress = mutableMapOf<String, EngineProgress>()
    var lastSeenMs = 0L
    var offlineRate = 0.0         // 自動運転時の平均純収入 [¥/s]

    fun prog(key: String) = progress.getOrPut(key) { EngineProgress(key) }
    val multiplier: Double get() = GameRules.prestigeMultiplier(techPoints) * GameRules.collectionMultiplier(unlocked.size)

    fun upgradeCost(def: GameEngineDef, kind: UpgradeKind): Double {
        val lv = prog(def.key).level(kind)
        return def.basePrice * kind.costFactor * kind.growth.pow(lv.toDouble())
    }

    fun automationCost(): Double = 400.0 * 6.0.pow(automation.toDouble())

    fun pendingTechPoints(): Int = GameRules.tpFromEarnings(runEarned)

    /** プレステージ (オーバーホール): 所持金/解放/アップグレードをリセットし技術ポイントを得る */
    fun prestige(): Int {
        val gain = pendingTechPoints()
        if (gain <= 0) return 0
        techPoints += gain
        money = 0.0
        runEarned = 0.0
        automation = 0
        unlocked.clear()
        unlocked += GameRoster.engines.first().key
        current = GameRoster.engines.first().key
        progress.values.forEach { it.levels.clear() }
        return gain
    }

    /** オフライン収入 (前回終了時の自動運転の平均純収入 × 経過時間 × 50%, 最大 4 時間) */
    fun collectOffline(nowMs: Long): Double {
        if (lastSeenMs <= 0) return 0.0
        val dt = min(GameRules.OFFLINE_CAP_S, max(0.0, (nowMs - lastSeenMs) / 1000.0))
        val gain = offlineRate * dt * GameRules.OFFLINE_RATE
        if (gain > 0) earn(gain)
        return gain
    }

    fun earn(v: Double) {
        money += v
        if (v > 0) {
            totalEarned += v
            runEarned += v
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("money", money)
        put("total", totalEarned)
        put("run", runEarned)
        put("tp", techPoints)
        put("auto", automation)
        put("current", current)
        put("unlocked", org.json.JSONArray(unlocked.toList()))
        put("progress", org.json.JSONArray(progress.values.map { it.toJson() }))
        put("lastSeen", lastSeenMs)
        put("offlineRate", offlineRate)
    }.toString()

    companion object {
        fun fromJson(s: String?): GameState {
            val g = GameState()
            if (s.isNullOrBlank()) return g
            runCatching {
                val o = JSONObject(s)
                g.money = o.optDouble("money", 0.0)
                g.totalEarned = o.optDouble("total", 0.0)
                g.runEarned = o.optDouble("run", 0.0)
                g.techPoints = o.optInt("tp", 0)
                g.automation = o.optInt("auto", 0)
                g.current = o.optString("current", g.current)
                o.optJSONArray("unlocked")?.let { a -> for (i in 0 until a.length()) g.unlocked += a.getString(i) }
                o.optJSONArray("progress")?.let { a ->
                    for (i in 0 until a.length()) EngineProgress.fromJson(a.getJSONObject(i)).let { p -> g.progress[p.key] = p }
                }
                g.lastSeenMs = o.optLong("lastSeen", 0L)
                g.offlineRate = o.optDouble("offlineRate", 0.0)
            }
            return g
        }
    }
}

/** エンジンファミリー判定 (アップグレードの適用可否) */
fun engineFamily(json: JSONObject): String = json.optString("family", "reciprocating")

fun upgradeApplies(def: GameEngineDef, family: String, kind: UpgradeKind): Boolean = when (family) {
    "turbine" -> kind.turbine
    "electric" -> kind.electric
    else -> kind.recip && (kind != UpgradeKind.CYLINDERS || def.cylinderStep > 0)
}

/**
 * アップグレードをエンジン定義 JSON に反映する。
 * custom エンジンは気筒追加を n 気筒生成器のパラメータで行ってから JSON 化する (呼び出し側)。
 */
object EngineTuner {
    fun customParams(def: GameEngineDef, p: EngineProgress): JSONObject {
        val o = JSONObject(def.custom!!.toString())
        val add = p.level(UpgradeKind.CYLINDERS) * def.cylinderStep
        val maxN = when (o.optString("layout")) { "inline" -> 12; "v" -> 24; "flat" -> 16; "radial" -> 11; else -> 1 }
        o.put("cylinders", min(maxN, o.getInt("cylinders") + add))
        return o
    }

    fun apply(engine: JSONObject, p: EngineProgress): JSONObject {
        val j = JSONObject(engine.toString())
        val fam = engineFamily(j)
        fun lv(u: UpgradeKind) = p.level(u).toDouble()
        fun scale(key: String, f: Double) { if (j.has(key)) j.put(key, j.getDouble(key) * f) }
        when (fam) {
            "turbine" -> {
                val t = j.getJSONObject("turbine")
                val k = 1 + 0.08 * lv(UpgradeKind.COMBUSTOR)
                t.put("maxThrustN", t.optDouble("maxThrustN", 50000.0) * k)
                if (t.has("maxShaftPowerW")) t.put("maxShaftPowerW", t.getDouble("maxShaftPowerW") * k)
                t.put("spoolTime", t.optDouble("spoolTime", 2.5) * 0.92.pow(lv(UpgradeKind.SPOOL)))
            }
            "electric" -> {
                val m = j.getJSONObject("motor")
                val k = 1 + 0.08 * lv(UpgradeKind.MAGNETS)
                m.put("ratedTorque", m.optDouble("ratedTorque", 300.0) * k)
                m.put("ratedPowerW", m.optDouble("ratedPowerW", 150000.0) * k)
            }
            "wankel" -> {
                // ボア → 創成半径/偏心量 (作動室容積 ∝ e·R·b)、ストローク → ローター幅
                j.optJSONObject("wankel")?.let { w ->
                    val kb = 1 + 0.015 * lv(UpgradeKind.BORE)
                    w.put("generatingRadius", w.getDouble("generatingRadius") * kb)
                    w.put("eccentricity", w.getDouble("eccentricity") * kb)
                    w.put("width", w.getDouble("width") * (1 + 0.015 * lv(UpgradeKind.STROKE)))
                }
                commonRecip(j, p)
            }
            else -> {
                scale("bore", 1 + 0.015 * lv(UpgradeKind.BORE))
                scale("stroke", 1 + 0.015 * lv(UpgradeKind.STROKE))
                if (j.has("rodLength")) scale("rodLength", 1 + 0.015 * lv(UpgradeKind.STROKE))
                commonRecip(j, p)
            }
        }
        val tuning = j.optJSONObject("tuning") ?: JSONObject()
        tuning.put("breathingScale", 1 + 0.06 * lv(UpgradeKind.CAMS))
        tuning.put("frictionScale", 0.95.pow(lv(UpgradeKind.LIGHTEN)))
        tuning.put("backpressureScale", 0.92.pow(lv(UpgradeKind.EXHAUST)))
        tuning.put("coolingScale", 1 + 0.12 * lv(UpgradeKind.RADIATOR))
        j.put("tuning", tuning)
        return j
    }
}

private fun commonRecip(j: JSONObject, p: EngineProgress) {
    fun lv(u: UpgradeKind) = p.level(u).toDouble()
    if (j.has("compressionRatio")) j.put("compressionRatio", j.getDouble("compressionRatio") + 0.4 * lv(UpgradeKind.COMPRESSION))
    if (j.has("redlineRpm")) j.put("redlineRpm", j.getDouble("redlineRpm") * (1 + 0.03 * lv(UpgradeKind.REV)))
    j.remove("limiterRpm")
    j.remove("maxRpm")
    if (j.has("inertia")) j.put("inertia", j.getDouble("inertia") * 0.94.pow(lv(UpgradeKind.LIGHTEN)))
    val b = lv(UpgradeKind.BOOST)
    if (b > 0) {
        val ind = j.optJSONObject("induction") ?: JSONObject().put("type", "natural")
        if (ind.optString("type", "natural") == "natural") ind.put("type", "turbo").put("maxBoostBar", 0.0).put("spoolTime", 1.0)
        ind.put("maxBoostBar", ind.optDouble("maxBoostBar", 0.0) + 0.2 * b)
        j.put("induction", ind)
    }
    j.optJSONObject("exhaust")?.let { e -> e.put("mufflerVolume", e.optDouble("mufflerVolume", 0.02) * 0.9.pow(lv(UpgradeKind.EXHAUST))) }
}

/** 数値の短縮表記 (1.23K, 4.56M, ...) */
fun fmtMoney(v: Double): String {
    val a = kotlin.math.abs(v)
    if (a < 1000) return "%.0f".format(v)
    val units = arrayOf("K", "M", "B", "T", "Qa", "Qi", "Sx")
    var x = a
    var i = -1
    while (x >= 1000 && i < units.size - 1) { x /= 1000; i++ }
    val s = if (x < 10) "%.2f" else if (x < 100) "%.1f" else "%.0f"
    return (if (v < 0) "-" else "") + s.format(x) + units[i]
}

/** 収入の対数スケール (UI のバー表示用) */
fun logFrac(v: Double, maxV: Double): Float = if (v <= 1 || maxV <= 1) 0f else (ln(v) / ln(maxV)).toFloat().coerceIn(0f, 1f)
