package com.s70rm3892.enginesim.game

import kotlin.math.pow

enum class Currency { MONEY, STAR }

/**
 * 強化ボードのノード (夜に買う)。parents のどれか 1 つを Lv1 以上にすると買える。
 * cost(lv) = base × growth^lv。★ ノードは整数。
 */
data class BoardNode(
    val id: String,
    val name: String,
    val desc: String,
    val lane: Int,
    val depth: Int,
    val maxLevel: Int,
    val currency: Currency,
    val base: Double,
    val growth: Double,
    val parents: List<String> = emptyList(),
) {
    fun cost(lv: Int): Double {
        val c = base * growth.pow(lv.toDouble())
        return if (currency == Currency.STAR) kotlin.math.ceil(c) else c
    }
}

object GameBoard {
    val lanes = listOf("営業", "発電", "整備", "自動化", "工房")

    private fun m(id: String, name: String, desc: String, lane: Int, depth: Int, max: Int, base: Double, growth: Double, vararg p: String) =
        BoardNode(id, name, desc, lane, depth, max, Currency.MONEY, base, growth, p.toList())

    private fun s(id: String, name: String, desc: String, lane: Int, depth: Int, max: Int, base: Double, growth: Double, vararg p: String) =
        BoardNode(id, name, desc, lane, depth, max, Currency.STAR, base, growth, p.toList())

    val nodes: List<BoardNode> = listOf(
        // 営業: シフト時間と依頼
        m("overtime", "残業許可", "シフト +8 秒 / Lv", 0, 0, 6, 400.0, 2.1),
        m("negotiate", "交渉術", "依頼の報酬 ¥ +25% / Lv", 0, 1, 6, 800.0, 2.0, "overtime"),
        s("orderBoard", "依頼ボード拡張", "同時に受けられる依頼 +1", 0, 2, 2, 4.0, 2.0, "negotiate"),
        s("reputation", "評判", "依頼達成で ★ が出る確率 +35% / Lv (2 個目)", 0, 2 + 1, 2, 5.0, 1.6, "negotiate"),
        s("vip", "VIP 顧客", "高難度・高報酬の VIP 依頼が出るようになる (★3)", 0, 4, 1, 10.0, 1.0, "orderBoard", "reputation"),
        m("overtime2", "深夜営業", "シフト +15 秒 / Lv", 0, 5, 3, 250_000.0, 4.0, "vip"),
        // 発電: 売電と燃料とコンボ
        m("grid", "売電契約", "電力の買取単価 +12% / Lv", 1, 0, 10, 450.0, 1.75),
        m("fuelDeal", "燃料契約", "燃料代 −8% / Lv", 1, 1, 6, 700.0, 1.9, "grid"),
        m("comboCap", "コンボ上限", "コンボ上限 +0.5 / Lv", 1, 1 + 1, 4, 1200.0, 2.6, "grid"),
        m("rhythm", "リズム感", "コンボの溜まる速さ +30% / Lv", 1, 3, 3, 2700.0, 2.4, "comboCap"),
        m("research", "研究開発", "全収入 +8% / Lv (上限なし・エンドコンテンツ)", 1, 5, 999, 2.0e7, 1.3, "sweetWide"),
        s("sweetWide", "ピーク需要", "ターゲット帯を 3% ずつ広げる / Lv", 1, 4, 3, 4.0, 1.8, "rhythm", "fuelDeal"),
        // 整備: 熱と耐久
        m("radiator", "大型ラジエーター", "発熱 −12% / Lv", 2, 0, 6, 480.0, 1.9),
        m("block", "強化ブロック", "耐久 +25 / Lv", 2, 1, 5, 720.0, 2.0, "radiator"),
        s("antilag", "アンチラグ", "アフターファイアのボーナス ×1.6 / Lv", 2, 1 + 1, 3, 3.0, 1.7, "radiator"),
        m("nitroTank", "ニトロタンク", "ニトロの持続 +2 秒 / Lv、溜まる速さ +20% / Lv", 2, 4, 3, 3000.0, 2.6, "mechanic"),
        m("mechanic", "専属メカニック", "オーバーヒートの停止時間 −1 秒 / Lv、耐久の減り −15% / Lv", 2, 3, 3, 4500.0, 2.8, "block"),
        // 自動化
        m("assistant", "アシスタント", "ペダルを離している間、ターゲット帯を追う (開度の上限 18% / Lv)", 3, 0, 5, 1000.0, 2.3),
        s("autoShift", "オートブリップ", "ブリッピング依頼をアシスタントがこなす", 3, 1, 1, 6.0, 1.0, "assistant"),
        m("telemetry", "計測器", "依頼の達成が 20% 早く進む / Lv", 3, 2, 3, 6000.0, 3.0, "assistant"),
        s("luckyBolt", "ラッキーボルト", "金のボルトの出現頻度 +30% / Lv", 3, 3, 3, 3.0, 1.8, "assistant"),
        // 工房: 系統とツールの解放
        s("goggles", "X線ゴーグル", "「内部を見る」(透視・断面・温度・応力・スロー) が使える", 4, 0, 1, 1.0, 1.0),
        s("steamWorks", "蒸気工房", "エンジンツリー: 外燃系統を開く", 4, 1, 1, 3.0, 1.0, "goggles"),
        s("rotaryShop", "ロータリー工房", "エンジンツリー: ロータリー系統を開く", 4, 1 + 1, 1, 4.0, 1.0, "goggles"),
        s("turboShop", "過給ショップ", "エンジンツリー: 過給系統を開き、チューンに「過給機」を追加", 4, 3, 1, 5.0, 1.0, "goggles"),
        s("dieselShop", "ディーゼル工房", "エンジンツリー: ディーゼル系統を開く", 4, 4, 1, 5.0, 1.0, "goggles"),
        s("vehicleBay", "車両ベイ", "夜のゼロヨン (ドラッグレース) が走れる", 4, 5, 1, 6.0, 1.0, "turboShop", "rotaryShop"),
        s("evLab", "EV ラボ", "エンジンツリー: 電動系統を開く", 4, 6, 1, 8.0, 1.0, "dieselShop", "turboShop"),
        s("hangar", "航空ハンガー", "エンジンツリー: 星型系統を開く", 4, 7, 1, 8.0, 1.0, "dieselShop"),
        s("jetTest", "ジェット試験場", "エンジンツリー: タービン系統を開く", 4, 8, 1, 15.0, 1.0, "hangar"),
    )

    private val byId = nodes.associateBy { it.id }
    fun byId(id: String) = byId.getValue(id)
    fun has(id: String) = id in byId
}

/** 強化ボードのレベルから導く数値 */
class Perks(private val g: GameState) {
    private fun lv(id: String) = g.lv(id)
    val shiftSeconds get() = GameRules.SHIFT_BASE_S + 8.0 * lv("overtime") + 15.0 * lv("overtime2")
    val orderRewardMul get() = 1.0 + 0.25 * lv("negotiate")
    val orderSlots get() = 2 + lv("orderBoard")
    val extraStarChance get() = 0.35 * lv("reputation")
    val vip get() = lv("vip") > 0
    val powerPriceMul get() = (1.0 + 0.12 * lv("grid")) * (1.0 + 0.08 * lv("research"))
    val fuelMul get() = 0.92.pow(lv("fuelDeal").toDouble())
    val comboCap get() = GameRules.COMBO_MAX + 0.5 * lv("comboCap")
    val comboRate get() = 1.0 + 0.3 * lv("rhythm")
    val sweetWiden get() = 0.03 * lv("sweetWide")
    val heatMul get() = 0.88.pow(lv("radiator").toDouble())
    val maxHp get() = GameRules.HP_BASE + 25.0 * lv("block")
    val afterfireMul get() = 1.6.pow(lv("antilag").toDouble())
    val overheatStop get() = (4.0 - lv("mechanic")).coerceAtLeast(1.0)
    val damageMul get() = 0.85.pow(lv("mechanic").toDouble())
    val assistLevel get() = lv("assistant")
    val autoBlip get() = lv("autoShift") > 0
    val orderSpeed get() = 1.0 + 0.2 * lv("telemetry")
    val internalView get() = lv("goggles") > 0
    val dragRace get() = lv("vehicleBay") > 0
    val nitroSeconds get() = 6.0 + 2.0 * lv("nitroTank")
    val nitroRate get() = 1.0 + 0.2 * lv("nitroTank")
    val goldenRate get() = 1.0 + 0.3 * lv("luckyBolt")
}
