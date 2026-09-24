package com.s70rm3892.enginesim.game

/** 目標 (実績)。達成すると ★ がもらえる。夜の締めで判定する */
data class Goal(val id: String, val name: String, val desc: String, val stars: Int, val check: (GameState) -> Boolean)

object Goals {
    val all: List<Goal> = listOf(
        Goal("day1", "初出勤", "1 日目を終える", 1) { it.stats.days >= 1 },
        Goal("week", "1 週間", "7 日働く", 2) { it.stats.days >= 7 },
        Goal("month", "ひと月", "30 日働く", 5) { it.stats.days >= 30 },
        Goal("earn1k", "日給 ¥1,000", "1 日で ¥1,000 稼ぐ", 2) { it.stats.bestDay >= 1e3 },
        Goal("earn10k", "日給 ¥10,000", "1 日で ¥10,000 稼ぐ", 3) { it.stats.bestDay >= 1e4 },
        Goal("earn100k", "日給 ¥100K", "1 日で ¥100,000 稼ぐ", 4) { it.stats.bestDay >= 1e5 },
        Goal("earn1m", "日給 ¥1M", "1 日で ¥1,000,000 稼ぐ", 6) { it.stats.bestDay >= 1e6 },
        Goal("earn100m", "日給 ¥100M", "1 日で ¥100,000,000 稼ぐ", 10) { it.stats.bestDay >= 1e8 },
        Goal("orders10", "頼れる工場", "依頼を 10 件達成", 2) { it.stats.ordersDone >= 10 },
        Goal("orders50", "人気工場", "依頼を 50 件達成", 4) { it.stats.ordersDone >= 50 },
        Goal("orders200", "伝説の工場", "依頼を 200 件達成", 8) { it.stats.ordersDone >= 200 },
        Goal("bang50", "パンパン", "アフターファイアを累計 50 回", 3) { it.stats.afterfires >= 50 },
        Goal("sweet600", "スイートスポット", "スイートゾーンに累計 10 分", 3) { it.stats.sweetSeconds >= 600 },
        Goal("clean", "無傷の一日", "耐久満タンのまま依頼 3 件以上で 1 日を終える", 3) { it.stats.cleanDays >= 1 },
        Goal("blown", "ブロー", "エンジンを壊す (誰もが通る道)", 1) { it.stats.blown >= 1 },
        Goal("engines5", "コレクター", "エンジンを 5 台持つ", 3) { it.unlocked.size >= 5 },
        Goal("engines15", "エンジン博物館", "エンジンを 15 台持つ", 6) { it.unlocked.size >= 15 },
        Goal("engines30", "エンジン帝国", "エンジンを 30 台持つ", 10) { it.unlocked.size >= 30 },
        Goal("allLanes", "全系統", "すべての工房を開く", 6) { g -> GameRoster.laneWorkshop.filterNotNull().all { g.has(it) } },
        Goal("specialist", "スペシャリスト", "3 台のエンジンに仕様を付ける", 3) { g -> g.progress.values.count { it.specialty != EngineSpecialty.NONE } >= 3 },
        Goal("tuned", "フルチューン", "どれか 1 台のチューンを 5 項目 Lv5 以上に", 5) { g -> g.progress.values.any { p -> p.levels.values.count { it >= 5 } >= 5 } },
        Goal("drag13", "ゼロヨン 13 秒", "ゼロヨンを 13 秒未満で走る", 3) { it.stats.bestDrag in 0.1..13.0 },
        Goal("drag10", "ゼロヨン 10 秒", "ゼロヨンを 10 秒未満で走る", 6) { it.stats.bestDrag in 0.1..10.0 },
        Goal("v12", "12 気筒", "V12 を手に入れる", 3) { "v12_60" in it.unlocked },
        Goal("deltic", "三角形の怪物", "デルティックを手に入れる", 5) { "deltic18" in it.unlocked },
        Goal("summit", "帝国の頂点", "ターボファンを手に入れる", 20) { "turbofan" in it.unlocked },
    )

    /** 新しく達成した目標を記録し、★ を加算して返す */
    fun evaluate(g: GameState): List<Goal> {
        val newly = all.filter { it.id !in g.goalsDone && it.check(g) }
        for (goal in newly) {
            g.goalsDone += goal.id
            g.stars += goal.stars
        }
        return newly
    }
}
