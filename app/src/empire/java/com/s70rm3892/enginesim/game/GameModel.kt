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
    const val COAL_PRICE = 0.05        // 蒸気機関の石炭 (安いが効率が低い)
    const val THRUST_SPEED = 250.0     // ジェット: 推力 × 250m/s (巡航速度) を推進仕事率とみなす
    const val OFFLINE_RATE = 0.5       // オフライン収入の割合
    const val OFFLINE_CAP_S = 4 * 3600.0
    const val COMBO_MAX = 3.0
    const val SWEET_LO = 0.72          // コンボが溜まる回転域 (レッドライン比)
    const val SWEET_HI = 0.95

    fun tpFromEarnings(totalEarned: Double): Int = floor(sqrt(max(0.0, totalEarned) / 1e6)).toInt()
    fun prestigeMultiplier(tp: Int): Double = 1.0 + 0.1 * tp
    /** 解放済みエンジン 1 台ごとに全体収入 +10% (コレクション・ボーナス) */
    fun collectionMultiplier(unlocked: Int): Double = 1.0 + 0.10 * max(0, unlocked - 1)
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

/**
 * エンジンツリーのノード。catalogId は組み込み JSON、custom は n 気筒生成器のパラメータ。
 * parents のどれか 1 つを持っていれば解放できる (ルートは parents 空)。
 * lane (行) と depth (列) はツリー画面での配置。estNet は無改造・全開・コンボ無しでの純収入の目安 [¥/s]。
 */
data class GameEngineDef(
    val key: String,
    val name: String,
    val price: Double,
    val catalogId: String? = null,
    val custom: JSONObject? = null,
    val lane: Int = 0,
    val depth: Int = 0,
    val parents: List<String> = emptyList(),
    val estNet: Double = 0.0,
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

    /** ツリーの行 (系統) */
    val lanes = listOf("外燃", "単気筒", "直列", "過給", "ディーゼル", "電動", "ロータリー", "水平対向", "V型", "星型", "タービン")

    /** ルート (耕運機) は直列の行に置く */
    const val ROOT = "tiller"

    private fun node(id: String, name: String, lane: Int, depth: Int, price: Double, est: Double, vararg parents: String) =
        GameEngineDef(id, name, price, catalogId = id, lane = lane, depth = depth, parents = parents.toList(), estNet = est)

    val engines: List<GameEngineDef> = listOf(
        GameEngineDef(ROOT, "耕運機 単気筒", 0.0,
            custom = custom("name" to "耕運機 単気筒", "layout" to "inline", "cylinders" to 1, "boreMm" to 70, "strokeMm" to 60,
                "compressionRatio" to 9.0, "idleRpm" to 1300, "redlineRpm" to 6500),
            lane = 2, depth = 0, estNet = 5.0),
        node("stirling_alpha", "スターリング", 0, 1, 300.0, 0.8, "tiller"),
        node("steam_mill", "蒸気 製粉所", 0, 2, 4000.0, 30.0, "stirling_alpha"),
        node("steam_loco", "蒸気機関車", 0, 6, 710000.0, 1034.0, "steam_mill"),
        node("i1_250", "単気筒 250", 1, 1, 800.0, 8.9, "tiller"),
        node("i1_2st_125", "2スト 125", 1, 2, 1000.0, 4.6, "i1_250"),
        node("i2_270", "並列2 270°", 2, 1, 2100.0, 22.8, "tiller"),
        node("i3_1200", "直列3", 2, 2, 4000.0, 29.8, "i2_270"),
        node("i4_20", "直列4 2.0", 2, 3, 11000.0, 52.2, "i3_1200"),
        node("i5_25", "直列5", 2, 4, 18000.0, 60.8, "i4_20"),
        node("i6_30", "直列6", 2, 5, 35000.0, 77.4, "i5_25"),
        node("i8_straight", "直列8", 2, 6, 47000.0, 68.3, "i6_30"),
        node("i4_20t", "直4 ターボ", 3, 4, 51000.0, 169.0, "i4_20"),
        node("v8_sc", "V8 スーパーチャージ", 3, 6, 230000.0, 338.0, "i4_20t", "v8_cross"),
        node("wankel3_20b", "3ローター 20B", 3, 7, 230000.0, 227.0, "v8_sc", "wankel2_13b"),
        node("i4_tdi", "直4 ディーゼル", 4, 4, 28000.0, 92.0, "i4_20"),
        node("jumo205", "Jumo 205", 4, 6, 340000.0, 502.0, "i4_tdi"),
        node("deltic18", "デルティック", 4, 8, 2700000.0, 1764.0, "jumo205"),
        node("motor_induction", "誘導モータ", 5, 3, 18000.0, 90.6, "i3_1200", "v4_90", "b2_1200"),
        node("motor_pmsm", "PMSM モータ", 5, 4, 30000.0, 99.8, "motor_induction"),
        node("wankel1", "1ローター", 6, 1, 1400.0, 15.8, "tiller"),
        node("wankel2_13b", "2ローター 13B", 6, 2, 7300.0, 54.2, "wankel1"),
        node("wankel4_26b", "4ローター 26B", 6, 4, 34000.0, 113.0, "wankel2_13b"),
        node("b2_1200", "水平対向2", 7, 1, 3200.0, 35.8, "tiller"),
        node("b4_20", "水平対向4", 7, 2, 19000.0, 137.3, "b2_1200"),
        node("b6_30", "水平対向6", 7, 3, 25000.0, 97.6, "b4_20"),
        node("v2_45", "V2 45°", 8, 1, 3200.0, 35.6, "tiller"),
        node("v4_90", "V4 90°", 8, 2, 4200.0, 28.6, "v2_45"),
        node("v6_60", "V6 60°", 8, 3, 18000.0, 88.8, "v4_90"),
        node("v8_cross", "V8 クロス", 8, 4, 40000.0, 131.0, "v6_60"),
        node("v8_flat", "V8 フラット", 8, 5, 67000.0, 148.0, "v8_cross"),
        node("v10_72", "V10", 8, 6, 110000.0, 157.0, "v8_flat"),
        node("v12_60", "V12", 8, 7, 180000.0, 178.0, "v10_72"),
        node("v16_45", "V16", 8, 8, 230000.0, 63.0, "v12_60"),
        node("r3", "星型3", 9, 1, 1500.0, 16.4, "tiller"),
        node("r5", "星型5", 9, 2, 3700.0, 27.1, "r3"),
        node("r7", "星型7", 9, 3, 7500.0, 36.8, "r5"),
        node("r9_r1820", "星型9 R-1820", 9, 4, 110000.0, 374.0, "r7"),
        node("r14_r1830", "複列14", 9, 5, 210000.0, 460.0, "r9_r1820"),
        node("r18_r2800", "複列18", 9, 6, 580000.0, 844.0, "r14_r1830"),
        node("r28_r4360", "4列28", 9, 7, 1300000.0, 1315.0, "r18_r2800"),
        node("turbojet", "ターボジェット", 10, 8, 3000000.0, 1971.0, "r18_r2800", "jumo205"),
        node("turboshaft", "ターボシャフト", 10, 9, 3900000.0, 936.0, "turbojet"),
        node("turbofan", "ターボファン", 10, 10, 64000000.0, 18442.0, "turboshaft"),
    )

    private val byKeyMap = engines.associateBy { it.key }
    fun byKey(key: String) = byKeyMap[key] ?: engines.first()
    fun has(key: String) = key in byKeyMap
    fun children(key: String) = engines.filter { key in it.parents }

    /** 旧版 (一本道ロスター) のキー → 新しいツリーのキー */
    val legacyKeys = mapOf(
        "twin" to "i2_270", "rotary" to "wankel2_13b", "i4t" to "i4_20t", "flat6" to "b6_30", "pmsm" to "motor_pmsm",
        "v8" to "v8_cross", "v12" to "v12_60", "radial" to "r9_r1820", "jumo" to "jumo205", "deltic" to "deltic18",
    )
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

    /** ツリー上で解放可能か (未所持で、親のどれかを所持) */
    fun canUnlock(def: GameEngineDef): Boolean =
        def.key !in unlocked && (def.parents.isEmpty() || def.parents.any { it in unlocked })

    /** 解放を試みる。成功したら true */
    fun unlock(def: GameEngineDef): Boolean {
        if (!canUnlock(def) || money < def.price) return false
        money -= def.price
        unlocked += def.key
        return true
    }
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
                fun migrate(k: String) = GameRoster.legacyKeys[k] ?: k
                g.current = migrate(o.optString("current", g.current)).takeIf { GameRoster.has(it) } ?: GameRoster.ROOT
                o.optJSONArray("unlocked")?.let { a ->
                    for (i in 0 until a.length()) migrate(a.getString(i)).takeIf { GameRoster.has(it) }?.let { g.unlocked += it }
                }
                if (g.current !in g.unlocked) g.current = GameRoster.ROOT
                o.optJSONArray("progress")?.let { a ->
                    for (i in 0 until a.length()) EngineProgress.fromJson(a.getJSONObject(i)).let { p ->
                        val k = migrate(p.key)
                        if (GameRoster.has(k)) g.progress[k] = EngineProgress(k).apply { levels += p.levels; bestDragTime = p.bestDragTime }
                    }
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
