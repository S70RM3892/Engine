package com.s70rm3892.enginesim.game

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 「ENGINE EMPIRE」— エンジンの物理特性をそのまま使う、1 日単位のサイクル型インクリメンタルゲームのモデル (Android 非依存)。
 *
 * 昼 (シフト): 制限時間内にダイナモでエンジンを回して稼ぐ。依頼・熱・耐久・日替わりイベントがある (RunSession)。
 * 夜 (ガレージ): 稼いだ ¥ と ★ で強化ボード・チューン・仕様・新エンジンを選んで買う。全部は買えないので迷う。
 *
 * 収入 = ダイナモ (またはプロペラ) が吸収した実出力 [kW] × 単価 × コンボ
 * 支出 = 燃料 (電動機は電力) の投入熱量 [kW] × 燃料単価
 */
object GameRules {
    const val POWER_PRICE = 1.0        // ¥ / (kW·s)
    const val FUEL_PRICE = 0.12        // ¥ / (kW·s)  燃料投入熱量あたり
    const val ELECTRIC_PRICE = 0.35    // 電動機の電力単価 (燃料より高いが効率が高い)
    const val COAL_PRICE = 0.05        // 蒸気機関の石炭 (安いが効率が低い)
    const val THRUST_SPEED = 250.0     // ジェット: 推力 × 250m/s (巡航速度) を推進仕事率とみなす
    const val COMBO_MAX = 3.0
    const val SWEET_LO = 0.72          // コンボが溜まる回転域 (レッドライン比)
    const val SWEET_HI = 0.95
    const val SHIFT_BASE_S = 50.0      // 1 日のシフト時間
    const val HP_BASE = 100.0          // エンジン耐久
    const val BLOWN_KEEP = 0.5         // ブロー時に残る今日の稼ぎの割合

    /** 解放済みエンジン 1 台ごとに全体収入 +5% (コレクション・ボーナス) */
    fun collectionMultiplier(unlocked: Int): Double = 1.0 + 0.05 * max(0, unlocked - 1)
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
    BOOST("過給機", "Lv1 でターボ装着、以降ブースト +0.2bar (要: 過給ショップ)", 8, 2.0, 1.55),
    EXHAUST("排気チューン", "背圧 −8% → 高回転出力と排気音", 6, 0.5, 1.4),
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
    val basePrice: Double get() = max(price, 500.0)

    /** 深い段のエンジンは ★ も必要 (工房と ★ を取り合う) */
    val starCost: Int get() = if (depth >= 4) depth - 2 else 0
}

object GameRoster {
    private fun custom(vararg kv: Pair<String, Any>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    /** ツリーの行 (系統) */
    val lanes = listOf("外燃", "単気筒", "直列", "過給", "ディーゼル", "電動", "ロータリー", "水平対向", "V型", "星型", "タービン")

    /** 系統ごとに必要な工房 (強化ボードのノード)。null は最初から開いている */
    val laneWorkshop: List<String?> = listOf(
        "steamWorks", null, null, "turboShop", "dieselShop", "evLab", "rotaryShop", null, null, "hangar", "jetTest",
    )

    /** ルート (耕運機) は直列の行に置く */
    const val ROOT = "tiller"

    /** 価格の倍率 (目安: 1 つ手前のエンジンで数日働くと買える。最初の分岐は早めに選べるよう半分) */
    fun priceScale(depth: Int) = if (depth <= 1) 2.0 else 4.0

    private fun node(id: String, name: String, lane: Int, depth: Int, price: Double, est: Double, vararg parents: String) =
        GameEngineDef(id, name, price * priceScale(depth), catalogId = id, lane = lane, depth = depth, parents = parents.toList(), estNet = est)

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
}

/** エンジンの仕様 (1 台につき 1 つ。夜に選ぶ。互いに排他) */
enum class EngineSpecialty(val label: String, val desc: String) {
    NONE("標準", "特化なし"),
    HIGH_REV("高回転仕様", "レッドライン +10%・吸気 +10%・慣性 −10%。スイートゾーンが上へ、熱 +15%。高回転系の依頼 ×2"),
    TORQUE("低速トルク仕様", "ストローク +6%・レッドライン −5%。スイートゾーンが下へ。低回転/出力系の依頼 ×2"),
    ECO("燃費仕様", "圧縮比 +1.0・燃料代 −15%。効率/電力量系の依頼 ×2"),
}

/** 1 エンジン分の進行状況 */
class EngineProgress(val key: String) {
    val levels = mutableMapOf<UpgradeKind, Int>()
    var bestDragTime = 0.0
    var specialty = EngineSpecialty.NONE
    var bestPowerKw = 0.0      // 実測の最高出力 (依頼の目標値に使う)
    var bestEff = 0.0          // 実測の最高効率
    fun level(u: UpgradeKind) = levels[u] ?: 0

    fun toJson() = JSONObject().apply {
        put("key", key)
        put("best", bestDragTime)
        put("spec", specialty.name)
        put("pmax", bestPowerKw)
        put("emax", bestEff)
        put("levels", JSONObject().apply { levels.forEach { (k, v) -> put(k.name, v) } })
    }

    companion object {
        fun fromJson(o: JSONObject) = EngineProgress(o.getString("key")).apply {
            bestDragTime = o.optDouble("best", 0.0)
            specialty = runCatching { EngineSpecialty.valueOf(o.optString("spec", "NONE")) }.getOrDefault(EngineSpecialty.NONE)
            bestPowerKw = o.optDouble("pmax", 0.0)
            bestEff = o.optDouble("emax", 0.0)
            val lv = o.optJSONObject("levels")
            lv?.keys()?.forEach { k -> runCatching { levels[UpgradeKind.valueOf(k)] = lv.getInt(k) } }
        }
    }
}

/** 累計の記録 (目標・実績の判定に使う) */
class GameStats {
    var days = 0
    var totalEarned = 0.0
    var bestDay = 0.0
    var ordersDone = 0
    var afterfires = 0
    var sweetSeconds = 0.0
    var blown = 0
    var cleanDays = 0          // 無傷 (耐久満タン) で依頼 3 件以上
    var bestDrag = 0.0

    fun toJson() = JSONObject().apply {
        put("days", days); put("total", totalEarned); put("bestDay", bestDay); put("orders", ordersDone)
        put("af", afterfires); put("sweet", sweetSeconds); put("blown", blown); put("clean", cleanDays); put("drag", bestDrag)
    }

    fun read(o: JSONObject) {
        days = o.optInt("days"); totalEarned = o.optDouble("total"); bestDay = o.optDouble("bestDay")
        ordersDone = o.optInt("orders"); afterfires = o.optInt("af"); sweetSeconds = o.optDouble("sweet")
        blown = o.optInt("blown"); cleanDays = o.optInt("clean"); bestDrag = o.optDouble("drag")
    }
}

/** ゲーム全体の状態 (セーブ対象) */
class GameState {
    var day = 1
    var money = 0.0
    var stars = 0
    var current = GameRoster.ROOT
    val unlocked = mutableSetOf(GameRoster.ROOT)
    val progress = mutableMapOf<String, EngineProgress>()
    val board = mutableMapOf<String, Int>()          // 強化ボードのレベル
    val goalsDone = mutableSetOf<String>()
    val stats = GameStats()
    var raceUsedDay = 0                              // その夜のゼロヨンを走った日
    var nextEvent = DayEvent.NONE                    // 明日のイベント (夜のうちに予報される)

    fun prog(key: String) = progress.getOrPut(key) { EngineProgress(key) }
    fun lv(node: String) = board[node] ?: 0
    fun has(node: String) = lv(node) > 0
    val perks: Perks get() = Perks(this)

    /** 系統が工房で開いているか */
    fun laneOpen(lane: Int): Boolean = GameRoster.laneWorkshop[lane]?.let { has(it) } ?: true

    /** ツリー上で解放可能か (未所持で、親のどれかを所持し、系統の工房がある) */
    fun canUnlock(def: GameEngineDef): Boolean =
        def.key !in unlocked && laneOpen(def.lane) && (def.parents.isEmpty() || def.parents.any { it in unlocked })

    /** 解放を試みる。成功したら true */
    fun unlock(def: GameEngineDef): Boolean {
        if (!canUnlock(def) || money < def.price || stars < def.starCost) return false
        money -= def.price
        stars -= def.starCost
        unlocked += def.key
        return true
    }

    val multiplier: Double get() = GameRules.collectionMultiplier(unlocked.size)

    fun upgradeCost(def: GameEngineDef, kind: UpgradeKind): Double {
        val lv = prog(def.key).level(kind)
        return def.basePrice * kind.costFactor * kind.growth.pow(lv.toDouble())
    }

    /** チューンを買えるか (過給機は過給ショップが必要) */
    fun tuneAvailable(kind: UpgradeKind): Boolean = kind != UpgradeKind.BOOST || has("turboShop")

    fun buyTune(def: GameEngineDef, kind: UpgradeKind): Boolean {
        val p = prog(def.key)
        if (!tuneAvailable(kind) || p.level(kind) >= kind.maxLevel) return false
        val c = upgradeCost(def, kind)
        if (money < c) return false
        money -= c
        p.levels[kind] = p.level(kind) + 1
        return true
    }

    /** 仕様の変更: 初回は ★2、以降の変更は ¥ (エンジン価格の 50%) */
    fun specialtyCost(def: GameEngineDef): Pair<Int, Double> =
        if (prog(def.key).specialty == EngineSpecialty.NONE) 2 to 0.0 else 0 to def.basePrice * 0.5

    fun setSpecialty(def: GameEngineDef, s: EngineSpecialty): Boolean {
        val p = prog(def.key)
        if (p.specialty == s) return false
        val (st, yen) = specialtyCost(def)
        if (stars < st || money < yen) return false
        stars -= st
        money -= yen
        p.specialty = s
        return true
    }

    fun boardCost(n: BoardNode): Double = n.cost(lv(n.id))

    fun canBuyNode(n: BoardNode): Boolean =
        lv(n.id) < n.maxLevel && (n.parents.isEmpty() || n.parents.any { has(it) })

    fun buyNode(n: BoardNode): Boolean {
        if (!canBuyNode(n)) return false
        val c = boardCost(n)
        if (n.currency == Currency.STAR) {
            if (stars < c) return false
            stars -= c.toInt()
        } else {
            if (money < c) return false
            money -= c
        }
        board[n.id] = lv(n.id) + 1
        return true
    }

    fun earn(v: Double) {
        money += v
        if (v > 0) stats.totalEarned += v
    }

    fun toJson(): String = JSONObject().apply {
        put("v", 2)
        put("day", day)
        put("money", money)
        put("stars", stars)
        put("current", current)
        put("unlocked", org.json.JSONArray(unlocked.toList()))
        put("progress", org.json.JSONArray(progress.values.map { it.toJson() }))
        put("board", JSONObject().apply { board.forEach { (k, v) -> put(k, v) } })
        put("goals", org.json.JSONArray(goalsDone.toList()))
        put("stats", stats.toJson())
        put("race", raceUsedDay)
        put("event", nextEvent.name)
    }.toString()

    companion object {
        fun fromJson(s: String?): GameState {
            val g = GameState()
            if (s.isNullOrBlank()) return g
            runCatching {
                val o = JSONObject(s)
                if (o.optInt("v", 1) < 2) return g   // 旧版 (常時放置型) のセーブは引き継がない
                g.day = o.optInt("day", 1)
                g.money = o.optDouble("money", 0.0)
                g.stars = o.optInt("stars", 0)
                g.current = o.optString("current", GameRoster.ROOT).takeIf { GameRoster.has(it) } ?: GameRoster.ROOT
                o.optJSONArray("unlocked")?.let { a ->
                    for (i in 0 until a.length()) a.getString(i).takeIf { GameRoster.has(it) }?.let { g.unlocked += it }
                }
                if (g.current !in g.unlocked) g.current = GameRoster.ROOT
                o.optJSONArray("progress")?.let { a ->
                    for (i in 0 until a.length()) EngineProgress.fromJson(a.getJSONObject(i)).let { p -> if (GameRoster.has(p.key)) g.progress[p.key] = p }
                }
                o.optJSONObject("board")?.let { b -> b.keys().forEach { k -> if (GameBoard.has(k)) g.board[k] = b.getInt(k) } }
                o.optJSONArray("goals")?.let { a -> for (i in 0 until a.length()) g.goalsDone += a.getString(i) }
                o.optJSONObject("stats")?.let { g.stats.read(it) }
                g.raceUsedDay = o.optInt("race", 0)
                g.nextEvent = runCatching { DayEvent.valueOf(o.optString("event", "NONE")) }.getOrDefault(DayEvent.NONE)
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
        applySpecialty(j, fam, p.specialty)
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
        val specBreath = if (p.specialty == EngineSpecialty.HIGH_REV) 1.10 else 1.0
        tuning.put("breathingScale", (1 + 0.06 * lv(UpgradeKind.CAMS)) * specBreath)
        tuning.put("frictionScale", 0.95.pow(lv(UpgradeKind.LIGHTEN)))
        tuning.put("backpressureScale", 0.92.pow(lv(UpgradeKind.EXHAUST)))
        j.put("tuning", tuning)
        return j
    }

    /** 仕様 (高回転/低速トルク/燃費) を定義 JSON に反映 */
    private fun applySpecialty(j: JSONObject, fam: String, s: EngineSpecialty) {
        if (fam == "turbine" || fam == "electric" || s == EngineSpecialty.NONE) return
        fun scale(key: String, f: Double) { if (j.has(key)) j.put(key, j.getDouble(key) * f) }
        when (s) {
            EngineSpecialty.HIGH_REV -> { scale("redlineRpm", 1.10); scale("inertia", 0.90) }
            EngineSpecialty.TORQUE -> { scale("stroke", 1.06); scale("rodLength", 1.06); scale("redlineRpm", 0.95) }
            EngineSpecialty.ECO -> if (j.has("compressionRatio")) j.put("compressionRatio", j.getDouble("compressionRatio") + 1.0)
            EngineSpecialty.NONE -> {}
        }
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
