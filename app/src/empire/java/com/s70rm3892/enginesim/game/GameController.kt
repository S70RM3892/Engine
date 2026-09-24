package com.s70rm3892.enginesim.game

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.s70rm3892.enginesim.ConsoleBackend
import com.s70rm3892.enginesim.EngineOwner
import com.s70rm3892.enginesim.EngineSummary
import com.s70rm3892.enginesim.Tel
import com.s70rm3892.enginesim.ui.KeyButton
import com.s70rm3892.enginesim.ui.Pal
import com.s70rm3892.enginesim.ui.PedalButton
import com.s70rm3892.enginesim.ui.SegmentedSelector
import com.s70rm3892.enginesim.ui.dp
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * ENGINE EMPIRE のゲームループ。ネイティブのエンジンシミュレーションをそのまま「発電所」として使う:
 *  - ダイナモ負荷は回転数に応じて自動で掛かり、吸収した実出力 [kW] が収入になる。
 *  - 投入した燃料 (電力) の熱量がコストになるので、熱効率の高いエンジンほど純収入が増える。
 *  - エンジンごとの「おいしい回転域」(スイートゾーン) に入れるとコンボ倍率が溜まる。
 *  - 熱ゲージが満タンになるとオーバーヒートで 4 秒間点火カット。
 *  - 過給・高回転・多気筒などのアップグレードは JSON 定義を書き換えて物理モデルごと変わる。
 */
class GameController(
    private val activity: Activity,
    private val backend: ConsoleBackend,
    private val engineSource: EngineSource,
    private val store: Store,
) {
    /** エンジン定義 JSON の取得 (組み込みカタログ / n 気筒生成器) */
    interface EngineSource {
        fun catalogJson(id: String): String
        fun customJson(params: JSONObject): String
    }

    /** セーブデータの読み書き */
    interface Store {
        fun load(): String?
        fun save(json: String)
    }

    enum class Tab(val label: String) { TUNE("チューン"), GARAGE("ガレージ"), RESEARCH("研究"), RACE("レース") }

    private enum class RaceState { NONE, COUNTDOWN, RUN, DONE }

    val state: GameState = GameState.fromJson(store.load())
    private val handler = Handler(Looper.getMainLooper())
    private val tel = FloatArray(Tel.COUNT)
    private var running = false

    // 現在のエンジン
    private var def = GameRoster.byKey(state.current)
    private var summary: EngineSummary? = null
    private var family = "reciprocating"
    private var cycle = "otto4"
    private var sweetLo = GameRules.SWEET_LO
    private var sweetHi = GameRules.SWEET_HI
    private var reloadAt = 0L

    // ゲーム進行
    private var combo = 1.0
    private var heat = 0.0
    private var overheat = 0.0
    private var incomeEma = 0.0
    private var floatAcc = 0.0
    private var floatTimer = 0.0
    private var lastAfterfire = -1f
    private var saveTimer = 0.0
    private var wasLimiter = false
    private var wasSweet = false

    // ドラッグレース
    private var race = RaceState.NONE
    private var raceMt = false
    private var raceGear = 1
    private var raceTimer = 0.0
    private var raceStartDist = 0f
    private var racePerfect = 0

    // UI
    private lateinit var hud: GameHudView
    private lateinit var pedal: PedalButton
    private lateinit var shiftKey: KeyButton
    private lateinit var tabs: SegmentedSelector
    private lateinit var list: LinearLayout
    private lateinit var header: TextView
    private var tab = Tab.TUNE
    private val rows = ArrayList<Pair<ShopRow, () -> Unit>>()   // 行と、その表示更新処理

    // エンジンツリー (オーバーレイ)
    private lateinit var treeOverlay: LinearLayout
    private lateinit var tree: TechTreeView
    private lateinit var treeInfo: TextView
    private lateinit var treeAction: KeyButton
    private var treeSel: GameEngineDef? = null
    private val descCache = HashMap<String, String>()

    // 内部表示 (描画モード / 視点 / スロー / 周回)
    private lateinit var viewBar: LinearLayout
    private lateinit var insideKey: KeyButton
    private lateinit var modeSel: SegmentedSelector
    private lateinit var presetSel: SegmentedSelector
    private lateinit var slowKey: KeyButton
    private lateinit var orbitKey: KeyButton
    private var viewMode = 0
    private var viewPreset = 0
    private var viewSerial = 1
    private var slow = false
    private var orbit = true

    /** カメラ自動周回の設定先 (GameActivity が NativeBridge.setAutoOrbit を渡す) */
    var onAutoOrbit: ((Float) -> Unit)? = null

    // ---------------------------------------------------------------- UI 構築

    fun buildInto(root: FrameLayout, viewportHost: FrameLayout) {
        val ctx = activity
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.BLACK) }
        val stage = FrameLayout(ctx)
        row.addView(stage, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 64f))
        stage.addView(viewportHost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        hud = GameHudView(ctx)
        hud.bottomInset = ctx.dp(8f)
        stage.addView(hud, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // 左下: 大きなアクセルペダルと SHIFT ボタン
        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        pedal = PedalButton(ctx).apply { label = "踏め!"; accent = Pal.RED }
        shiftKey = KeyButton(ctx, "SHIFT ▲").apply {
            accent = Pal.CYAN
            onPress = { shiftUp() }
            visibility = View.GONE
        }
        controls.addView(pedal, LinearLayout.LayoutParams(ctx.dp(96f).toInt(), ctx.dp(120f).toInt()))
        controls.addView(shiftKey, LinearLayout.LayoutParams(ctx.dp(96f).toInt(), ctx.dp(96f).toInt()).apply { marginStart = ctx.dp(10f).toInt() })
        stage.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(ctx.dp(12f).toInt(), 0, 0, ctx.dp(12f).toInt()) })

        // 上部: 内部を見る (描画モード・視点・スロー・周回)。「内部」ボタンで開閉
        insideKey = KeyButton(ctx, "内部を見る", toggle = true).apply {
            accent = Pal.CYAN
            onToggle = { on -> showInside(on) }
        }
        stage.addView(insideKey, FrameLayout.LayoutParams(ctx.dp(96f).toInt(), ctx.dp(34f).toInt(), Gravity.TOP or Gravity.START)
            .apply { setMargins(ctx.dp(12f).toInt(), ctx.dp(100f).toInt(), 0, 0) })
        viewBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(170, 12, 14, 17))
            val pd = ctx.dp(4f).toInt()
            setPadding(pd, pd, pd, pd)
            visibility = View.GONE
        }
        modeSel = SegmentedSelector(ctx, listOf("外観", "透視", "断面", "温度", "応力")).apply {
            accent = Pal.CYAN
            onSelect = { i -> viewMode = i; pushView() }
        }
        presetSel = SegmentedSelector(ctx, listOf("全体", "断面", "クランク", "バルブ", "出力軸")).apply {
            onSelect = { i ->
                viewPreset = i
                viewSerial++
                if (i == 1) { viewMode = 2; modeSel.selected = 2 }   // 断面視点は断面表示に
                pushView()
            }
        }
        slowKey = KeyButton(ctx, "スロー ×1/16", toggle = true).apply {
            onToggle = {
                slow = it
                if (it) hud.floatText("映像だけスロー (収入・音は実時間)", Pal.CYAN)
            }
        }
        orbitKey = KeyButton(ctx, "自動周回", toggle = true).apply {
            on = true
            onToggle = { orbit = it; onAutoOrbit?.invoke(if (it) 0.12f else 0f) }
        }
        val keys = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(slowKey, LinearLayout.LayoutParams(0, ctx.dp(34f).toInt(), 1f))
        keys.addView(orbitKey, LinearLayout.LayoutParams(0, ctx.dp(34f).toInt(), 1f))
        keys.addView(KeyButton(ctx, "閉じる").apply { onRelease = { showInside(false) } },
            LinearLayout.LayoutParams(ctx.dp(64f).toInt(), ctx.dp(34f).toInt()))
        viewBar.addView(modeSel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(38f).toInt()))
        viewBar.addView(presetSel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(38f).toInt()))
        viewBar.addView(keys)
        // 開いている間はトグルボタンと同じ位置を置き換える
        stage.addView(viewBar, FrameLayout.LayoutParams(ctx.dp(320f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(ctx.dp(12f).toInt(), ctx.dp(100f).toInt(), 0, 0) })

        // 右: ショップパネル
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            val pd = ctx.dp(6f).toInt()
            setPadding(pd, pd, pd, pd)
        }
        header = TextView(ctx).apply {
            text = "ENGINE EMPIRE"
            setTextColor(Pal.AMBER)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(KeyButton(ctx, "エンジンツリー").apply { accent = Pal.GREEN; onRelease = { openTree() } },
            LinearLayout.LayoutParams(ctx.dp(116f).toInt(), ctx.dp(34f).toInt()))
        panel.addView(top)
        tabs = SegmentedSelector(ctx, Tab.entries.map { it.label }).apply {
            onSelect = { i -> tab = Tab.entries[i]; rebuildList() }
        }
        panel.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(44f).toInt()))
        val scroll = ScrollView(ctx)
        list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        row.addView(panel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 36f))
        root.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        buildTreeOverlay(root)
        rebuildList()
    }

    // ---------------------------------------------------------------- エンジンツリー

    private fun buildTreeOverlay(root: FrameLayout) {
        val ctx = activity
        treeOverlay = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 10, 13))
            visibility = View.GONE
            isClickable = true
        }
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val pd = ctx.dp(4f).toInt()
            setPadding(pd * 2, pd, pd, pd)
        }
        head.addView(TextView(ctx).apply {
            text = "エンジンツリー  (ドラッグで移動 / 親のどれか 1 台で解放可)"
            setTextColor(Pal.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(KeyButton(ctx, "閉じる ✕").apply { onRelease = { closeTree() } },
            LinearLayout.LayoutParams(ctx.dp(96f).toInt(), ctx.dp(30f).toInt()))
        treeOverlay.addView(head)
        tree = TechTreeView(ctx).apply {
            state = this@GameController.state
            onSelect = { d -> selectTreeNode(d) }
        }
        treeOverlay.addView(tree, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val detail = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.PANEL)
            val pd = ctx.dp(8f).toInt()
            setPadding(pd, pd, pd, pd)
        }
        treeInfo = TextView(ctx).apply {
            setTextColor(Pal.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 3
        }
        treeAction = KeyButton(ctx, "解放").apply { accent = Pal.AMBER; onRelease = { treeActionPressed() } }
        detail.addView(treeInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        detail.addView(treeAction, LinearLayout.LayoutParams(ctx.dp(140f).toInt(), ctx.dp(48f).toInt()))
        treeOverlay.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(66f).toInt()))
        root.addView(treeOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun openTree() {
        if (race != RaceState.NONE) return
        treeOverlay.visibility = View.VISIBLE
        tree.currentKey = def.key
        selectTreeNode(treeSel ?: def)
        tree.centerOn((treeSel ?: def).key)
    }

    private fun closeTree() {
        treeOverlay.visibility = View.GONE
    }

    private fun description(d: GameEngineDef): String = descCache.getOrPut(d.key) {
        when {
            d.catalogId != null -> runCatching { JSONObject(engineSource.catalogJson(d.catalogId)).optString("description") }.getOrDefault("")
            else -> "すべてはここから。気筒追加で 12 気筒まで育つ単気筒。"
        }
    }

    private fun selectTreeNode(d: GameEngineDef) {
        treeSel = d
        tree.selectedKey = d.key
        refreshTreeDetail()
    }

    private fun refreshTreeDetail() {
        val d = treeSel ?: return
        val st = tree.nodeState(d)
        val parents = d.parents.joinToString(" / ") { GameRoster.byKey(it).name }
        val req = if (d.parents.isEmpty()) "" else "  必要: $parents のどれか"
        treeInfo.text = "${d.name}  [${GameRoster.lanes[d.lane]}]  目安 +${fmtMoney(d.estNet)}/s$req\n${description(d)}"
        when {
            d.key == def.key -> { treeAction.text = "稼働中"; treeAction.accent = Pal.DIM }
            st == TechTreeView.NodeState.OWNED -> { treeAction.text = "乗り換える"; treeAction.accent = Pal.GREEN }
            st == TechTreeView.NodeState.AVAILABLE ->
                { treeAction.text = "解放 ¥" + fmtMoney(d.price); treeAction.accent = if (state.money >= d.price) Pal.AMBER else Pal.DIM }
            else -> { treeAction.text = "🔒 未解放"; treeAction.accent = Pal.DIM }
        }
        treeAction.invalidate()
    }

    private fun treeActionPressed() {
        val d = treeSel ?: return
        when {
            d.key == def.key -> {}
            d.key in state.unlocked -> { switchEngine(d); closeTree() }
            state.unlock(d) -> {
                hud.popup("NEW ENGINE!", Pal.AMBER)
                hud.burstConfetti(220)
                hud.flash(Pal.AMBER)
                switchEngine(d)
                tree.currentKey = d.key
                save()
            }
            state.canUnlock(d) -> hud.floatText("資金不足", Pal.RED)
        }
        refreshTreeDetail()
        tree.invalidate()
    }

    private fun rowLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(58f).toInt()).apply {
        topMargin = activity.dp(3f).toInt()
    }

    private fun note(text: String, color: Int = Pal.DIM) = TextView(activity).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val pd = activity.dp(6f).toInt()
        setPadding(pd, pd, pd, pd)
    }

    private fun addRow(update: ShopRow.() -> Unit, click: ShopRow.() -> Unit): ShopRow {
        val r = ShopRow(activity)
        r.onClick = { click(r) }
        val u = { update(r); r.refresh() }
        u()
        rows += r to u
        list.addView(r, rowLp())
        return r
    }

    fun rebuildList() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        rows.clear()
        when (tab) {
            Tab.TUNE -> buildTune()
            Tab.GARAGE -> buildGarage()
            Tab.RESEARCH -> buildResearch()
            Tab.RACE -> buildRace()
        }
    }

    private fun buildTune() {
        list.addView(note("${def.name}\n${description(def)}", Pal.TEXT))
        val prog = state.prog(def.key)
        for (u in UpgradeKind.entries) {
            if (!upgradeApplies(def, family, u)) continue
            addRow({
                val lv = prog.level(u)
                val maxed = lv >= u.maxLevel
                title = "${u.label}  Lv $lv/${u.maxLevel}"
                subtitle = u.desc
                level = lv
                maxLevel = u.maxLevel
                val cost = state.upgradeCost(def, u)
                price = if (maxed) "MAX" else "¥" + fmtMoney(cost)
                affordable = !maxed && state.money >= cost
                accent = if (u == UpgradeKind.CYLINDERS || u == UpgradeKind.BOOST) Pal.VIOLET else Pal.AMBER
            }) { buyUpgrade(u, this) }
        }
    }

    private fun buildGarage() {
        addRow({
            val avail = GameRoster.engines.count { state.canUnlock(it) }
            val afford = GameRoster.engines.count { state.canUnlock(it) && state.money >= it.price }
            title = "エンジンツリーを開く"
            subtitle = "解放可能 $avail 台 (うち購入可 $afford 台) / 所持 ${state.unlocked.size}/${GameRoster.engines.size}"
            price = "▶"
            affordable = afford > 0
            accent = Pal.GREEN
        }) { openTree() }
        list.addView(note("所持エンジン (タップで乗り換え)。1 台ごとに全収入 +10%"))
        for (d in GameRoster.engines.filter { it.key in state.unlocked }.sortedBy { it.estNet }) {
            addRow({
                title = d.name
                subtitle = "[${GameRoster.lanes[d.lane]}]  目安 +${fmtMoney(d.estNet)}/s"
                current = d.key == def.key
                price = if (current) "稼働中" else "乗り換え"
                affordable = !current
                accent = Pal.GREEN
            }) { if (d.key != def.key) switchEngine(d) }
        }
    }

    private fun buildResearch() {
        addRow({
            val maxed = state.automation >= 10
            title = "自動スロットル  Lv ${state.automation}/10"
            subtitle = "放置中もスロットル ${(min(0.85, 0.08 * max(1, state.automation + 1)) * 100).toInt()}% を保持 (オフライン収入の元)"
            level = state.automation
            maxLevel = 10
            price = if (maxed) "MAX" else "¥" + fmtMoney(state.automationCost())
            affordable = !maxed && state.money >= state.automationCost()
            accent = Pal.CYAN
        }) {
            if (state.automation < 10 && state.money >= state.automationCost()) {
                state.money -= state.automationCost()
                state.automation++
                celebrate()
                rebuildList()
            }
        }
        addRow({
            val gain = state.pendingTechPoints()
            title = "オーバーホール (プレステージ)"
            subtitle = "全てリセットして技術ポイント +$gain (1TP = 収入 +10%)"
            price = if (gain > 0) "+$gain TP" else "要 ¥1M"
            affordable = gain > 0
            accent = Pal.VIOLET
        }) { confirmPrestige() }
        list.addView(note(
            "技術ポイント: ${state.techPoints} TP\n" +
                "収入倍率: ×${"%.2f".format(state.multiplier)}\n" +
                "今回の獲得: ¥${fmtMoney(state.runEarned)}\n" +
                "累計獲得: ¥${fmtMoney(state.totalEarned)}\n\n" +
                "収入 = ダイナモが吸収した出力 [kW] × コンボ − 燃料の熱量 [kW] × 単価。\n" +
                "熱効率の高いエンジンほど儲かる。ディーゼルは低回転、電動機は中回転、タービンは最高回転がスイートゾーン。\n" +
                "アクセルを抜いた瞬間のアフターファイアはボーナス。"))
    }

    private fun buildRace() {
        val can = raceAvailable()
        list.addView(note(if (can) "ゼロヨン (402m)。ベスト更新で報酬 2 倍。MT はシフトランプが赤の瞬間に SHIFT で PERFECT。"
            else "このエンジンは車両に載らない (プロペラ/ジェット)。別のエンジンで挑戦しよう。"))
        addRow({
            title = "ゼロヨン MT"
            subtitle = "自分で変速。PERFECT シフト 1 回ごとに報酬 +10%"
            price = if (race != RaceState.NONE) "走行中" else "START"
            affordable = can && race == RaceState.NONE
            accent = Pal.RED
        }) { if (can && race == RaceState.NONE) startRace(mt = true) }
        addRow({
            title = "ゼロヨン AT"
            subtitle = "トルコン AT に任せる (報酬 ×0.7)"
            price = if (race != RaceState.NONE) "走行中" else "START"
            affordable = can && race == RaceState.NONE
            accent = Pal.AMBER
        }) { if (can && race == RaceState.NONE) startRace(mt = false) }
        val best = state.prog(def.key).bestDragTime
        list.addView(note(if (best > 0) "このエンジンのベスト: ${"%.3f".format(best)} s" else "記録なし"))
    }

    // ---------------------------------------------------------------- 購入・切替

    private fun buyUpgrade(u: UpgradeKind, row: ShopRow) {
        val prog = state.prog(def.key)
        if (prog.level(u) >= u.maxLevel) return
        val cost = state.upgradeCost(def, u)
        if (state.money < cost) return
        state.money -= cost
        prog.levels[u] = prog.level(u) + 1
        row.celebrate()
        hud.floatText("${u.label} Lv${prog.level(u)}!", Pal.CYAN, big = u == UpgradeKind.CYLINDERS || u == UpgradeKind.BOOST)
        if (u == UpgradeKind.CYLINDERS || u == UpgradeKind.BOOST) {
            hud.burstConfetti(80)
            hud.flash(Pal.VIOLET)
        }
        scheduleReload()
        refreshRows()
    }

    private fun switchEngine(d: GameEngineDef) {
        if (race != RaceState.NONE) return
        state.current = d.key
        def = d
        reloadEngine()
        rebuildList()
    }

    private fun celebrate() {
        hud.burstConfetti(60)
    }

    private fun confirmPrestige() {
        val gain = state.pendingTechPoints()
        if (gain <= 0) return
        AlertDialog.Builder(activity)
            .setTitle("オーバーホール")
            .setMessage("所持金・解放エンジン・アップグレード・自動化をリセットし、技術ポイント +$gain を得る。\n(収入 +${gain * 10}%)")
            .setPositiveButton("実行") { _, _ ->
                state.prestige()
                def = GameRoster.byKey(state.current)
                hud.popup("+$gain TP", Pal.VIOLET)
                hud.burstConfetti(300)
                reloadEngine()
                rebuildList()
                save()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun scheduleReload() {
        // 連続購入をまとめてから物理モデルを作り直す
        reloadAt = System.currentTimeMillis() + 700
    }

    /** 現在のエンジン + アップグレードから定義 JSON を作って読み込む */
    fun reloadEngine() {
        reloadAt = 0
        val prog = state.prog(def.key)
        val base = runCatching {
            if (def.custom != null) engineSource.customJson(EngineTuner.customParams(def, prog))
            else engineSource.catalogJson(def.catalogId!!)
        }.getOrNull()
        val baseObj = base?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (baseObj == null || baseObj.has("error")) {
            hud.popup("ENGINE ERROR", Pal.RED)
            return
        }
        val tuned = EngineTuner.apply(baseObj, prog)
        summary = EngineSummary.parse(backend.loadEngine(tuned.toString()))
        EngineOwner.token = this
        family = engineFamily(tuned)
        cycle = summary?.cycle ?: tuned.optString("cycle", "otto4")
        val zone = sweetZone(family, cycle, summary?.propeller == true)
        sweetLo = zone.first
        sweetHi = zone.second
        combo = 1.0
        lastAfterfire = -1f
        updateStaticHud()
    }

    private fun updateStaticHud() {
        val s = summary ?: return
        hud.engineName = def.name + (if (def.cylinderStep > 0) "  (${s.cylinders}気筒)" else "")
        hud.traits = traitsOf(s, family, cycle)
        hud.redline = s.redlineRpm
        hud.idle = s.idleRpm
        hud.sweetLo = sweetLo.toFloat()
        hud.sweetHi = sweetHi.toFloat()
        hud.isTurbine = s.isTurbine
    }

    private fun raceAvailable(): Boolean {
        val s = summary ?: return false
        return !s.propeller && !s.isTurbine && s.gears > 0
    }

    // ---------------------------------------------------------------- ドラッグレース

    private fun startRace(mt: Boolean) {
        race = RaceState.COUNTDOWN
        raceMt = mt
        raceGear = 1
        raceTimer = 3.0
        racePerfect = 0
        hud.raceActive = true
        shiftKey.visibility = if (mt) View.VISIBLE else View.GONE
        refreshRows()
    }

    private fun shiftUp() {
        if (race != RaceState.RUN || !raceMt) return
        val s = summary ?: return
        if (raceGear >= s.gears) return
        val frac = tel[Tel.RPM] / s.redlineRpm
        raceGear++
        when {
            frac in 0.88f..1.02f -> { racePerfect++; hud.popup("PERFECT SHIFT!", Pal.GREEN); hud.flash(Pal.GREEN) }
            frac > 0.75f -> hud.floatText("GOOD", Pal.AMBER)
            else -> hud.floatText("EARLY…", Pal.DIM)
        }
    }

    private fun updateRace(dt: Double) {
        when (race) {
            RaceState.COUNTDOWN -> {
                raceTimer -= dt
                hud.raceStage = if (raceTimer > 0) "${raceTimer.toInt() + 1}" else "GO!"
                if (raceTimer <= 0) {
                    race = RaceState.RUN
                    raceTimer = 0.0
                    raceStartDist = tel[Tel.DISTANCE_M]
                    hud.flash(Color.WHITE)
                }
            }
            RaceState.RUN -> {
                raceTimer += dt
                if (raceTimer > 0.8) hud.raceStage = ""
                val d = tel[Tel.DISTANCE_M] - raceStartDist
                if (d >= 402.336f) finishRace(true)
                else if (raceTimer > 60) finishRace(false)
            }
            RaceState.DONE -> {
                raceTimer -= dt
                if (raceTimer <= 0) {
                    race = RaceState.NONE
                    hud.raceActive = false
                    hud.raceStage = ""
                    shiftKey.visibility = View.GONE
                    refreshRows()
                }
            }
            RaceState.NONE -> {}
        }
        hud.raceDistance = if (race == RaceState.RUN) tel[Tel.DISTANCE_M] - raceStartDist else if (race == RaceState.DONE) hud.raceDistance else 0f
        if (race == RaceState.RUN) hud.raceTime = raceTimer.toFloat()
        hud.raceSpeed = tel[Tel.SPEED_KMH]
        hud.raceGear = if (raceMt) "$raceGear" else if (tel[Tel.EFF_GEAR] > 0) "D${tel[Tel.EFF_GEAR].toInt()}" else "N"
        hud.raceBest = state.prog(def.key).bestDragTime.toFloat()
    }

    private fun finishRace(ok: Boolean) {
        race = RaceState.DONE
        val t = raceTimer
        raceTimer = 3.0
        if (!ok) {
            hud.raceStage = "DNF"
            return
        }
        val prog = state.prog(def.key)
        val newBest = prog.bestDragTime <= 0 || t < prog.bestDragTime
        if (newBest) prog.bestDragTime = t
        var reward = def.basePrice * 0.5 * (12.0 / t).pow(2) * state.multiplier * (1 + 0.1 * racePerfect)
        if (!raceMt) reward *= 0.7
        if (newBest) reward *= 2
        state.earn(reward)
        hud.raceStage = "%.3f s".format(t)
        hud.popup(if (newBest) "NEW RECORD! +¥${fmtMoney(reward)}" else "+¥${fmtMoney(reward)}", if (newBest) Pal.GREEN else Pal.AMBER)
        hud.burstConfetti(if (newBest) 300 else 100)
        save()
    }

    // ---------------------------------------------------------------- ループ

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step(0.033)
            handler.postDelayed(this, 33)
        }
    }

    fun step(dt: Double) {
        val s = summary
        if (reloadAt in 1..System.currentTimeMillis()) reloadEngine()
        pedal.advance(dt.toFloat())
        val n = backend.getTelemetry(tel)
        val autoThr = if (state.automation > 0) min(0.85, 0.08 * (state.automation + 1)) else 0.0
        val pedalThr = pedal.value.toDouble()
        val cut = overheat > 0
        var thr = max(pedalThr, autoThr)
        val rpm = tel[Tel.RPM].toDouble()
        if (s != null && n >= Tel.COUNT) {
            val red = s.redlineRpm.toDouble()
            val idle = s.idleRpm.toDouble()
            val racing = race != RaceState.NONE
            if (racing) thr = if (race == RaceState.DONE) 0.0 else pedalThr
            // 自動ダイナモ負荷: 回転の上昇とともに吸収トルクが増え、スイートゾーン付近で釣り合う
            val lo = if (s.isElectric) 0.0 else idle
            val x = ((rpm - lo) / max(1.0, 0.9 * red - lo)).coerceIn(0.0, 2.0)
            val load = dynoLoad(x).toFloat()
            backend.setControls(
                0f, if (cut) 0f else thr.toFloat(), false, if (racing) 0f else load, 0f, !cut, false,
                if (racing) raceGear else 1, if (slow) 1f / 16 else 1f, 0L, true,
                if (racing) (if (raceMt) 1 else 2) else 0, if (race == RaceState.COUNTDOWN || race == RaceState.DONE) 1f else 0f, 0f,
            )
            if (racing) updateRace(dt) else economy(dt, s, thr, pedalThr, autoThr)
            hud.rpm = tel[Tel.RPM]
            hud.limiter = tel[Tel.LIMITER] > 0.5f
            hud.powerKw = if (s.isTurbine) tel[Tel.POWER_KW] else tel[Tel.LOAD_NM] * tel[Tel.RPM] * (2 * PI.toFloat() / 60f) / 1000f
            hud.fuelKw = tel[Tel.FUEL_KW]
            hud.efficiency = tel[Tel.EFFICIENCY]
            hud.boostBar = tel[Tel.BOOST_BAR]
            hud.thrustKn = tel[Tel.THRUST_KN]
        }
        hud.money = state.money
        hud.multiplier = state.multiplier
        hud.autoLevel = state.automation
        hud.combo = combo.toFloat()
        hud.heat = heat.toFloat()
        hud.overheat = overheat.toFloat()
        hud.advance(dt.toFloat())
        floatTimer += dt
        if (floatTimer > 0.25) {
            floatTimer = 0.0
            refreshRows()
            if (treeOverlay.visibility == View.VISIBLE) refreshTreeDetail()
        }
        if (treeOverlay.visibility == View.VISIBLE) tree.advance(dt.toFloat())
        saveTimer += dt
        if (saveTimer > 10) { saveTimer = 0.0; save() }
    }

    private fun economy(dt: Double, s: EngineSummary, thr: Double, pedalThr: Double, autoThr: Double) {
        val rpm = tel[Tel.RPM].toDouble()
        val red = s.redlineRpm.toDouble()
        val frac = rpm / red
        val outKw = if (s.isTurbine) tel[Tel.POWER_KW] + tel[Tel.THRUST_KN] * GameRules.THRUST_SPEED
        else tel[Tel.LOAD_NM] * rpm * 2 * PI / 60 / 1000
        // 外燃機関はボイラー/ヒーターの熱量をモデルが報告しないので効率から逆算する
        // (蒸気: 石炭は安いが効率 10%、スターリング: 熱源は廃熱・太陽熱とみなし無料)
        val fuelKw = when (cycle) {
            "steam" -> outKw / 0.10
            "stirling" -> 0.0
            else -> tel[Tel.FUEL_KW].toDouble()
        }
        val price = when {
            s.isElectric -> GameRules.ELECTRIC_PRICE
            cycle == "steam" -> GameRules.COAL_PRICE
            else -> GameRules.FUEL_PRICE
        }

        // コンボ: スイートゾーンで溜まり、外れると減衰、リミッターで消える
        val limiter = tel[Tel.LIMITER] > 0.5f
        val sweet = frac in sweetLo..sweetHi && outKw > 0.5
        if (limiter && !wasLimiter) {
            if (combo > 1.3) hud.floatText("COMBO BREAK", Pal.RED)
            combo = 1.0
        }
        combo = if (sweet) min(GameRules.COMBO_MAX, combo + dt * 0.18) else max(1.0, combo - dt * 0.6)
        if (sweet && !wasSweet) hud.floatText("SWEET ZONE", Pal.GREEN)
        wasLimiter = limiter
        wasSweet = sweet

        val income = (outKw * GameRules.POWER_PRICE * combo - fuelKw * price) * state.multiplier
        state.earn(income * dt)
        incomeEma += (income - incomeEma) * min(1.0, dt / 1.5)
        hud.incomePerSec = incomeEma
        // オフライン収入の基準: ペダルを踏んでいない (自動運転のみ) ときの平均収入
        if (pedalThr < 0.01 && autoThr > 0) state.offlineRate += (max(0.0, income) - state.offlineRate) * min(1.0, dt / 20.0)
        if (state.automation == 0) state.offlineRate = 0.0

        floatAcc += income * dt
        if (floatAcc > max(1.0, incomeEma * 0.5) && floatTimer == 0.0) {
            hud.floatText("+¥" + fmtMoney(floatAcc), if (combo > 2.5) Pal.VIOLET else if (sweet) Pal.GREEN else Pal.AMBER)
            floatAcc = 0.0
        } else if (floatAcc < 0) floatAcc = 0.0

        // アフターファイア (アクセルオフ直後の未燃焼ガス) = ボーナス
        val af = tel[Tel.AFTERFIRE_COUNT]
        if (lastAfterfire >= 0 && af > lastAfterfire) {
            val bonus = max(1.0, incomeEma) * 1.5 * (af - lastAfterfire)
            state.earn(bonus)
            hud.floatText("BANG! +¥${fmtMoney(bonus)}", Color.rgb(255, 120, 40), big = true)
        }
        lastAfterfire = af

        // 熱: スロットル² × 回転に比例して上昇、ラジエーターで冷える
        val prog = state.prog(def.key)
        val heatMul = when {
            s.isElectric -> 0.6
            cycle.startsWith("diesel") -> 0.8
            else -> 1.0
        } * (1 + 0.12 * prog.level(UpgradeKind.BOOST)) * (1 + 0.05 * prog.level(UpgradeKind.REV))
        val cool = 0.035 * (1 + 0.12 * prog.level(UpgradeKind.RADIATOR)) + if (thr < 0.3) 0.05 else 0.0
        if (overheat > 0) {
            overheat -= dt
            heat = max(0.0, heat - dt * 0.2)
            if (overheat <= 0) { overheat = 0.0; hud.floatText("再始動", Pal.GREEN) }
        } else {
            heat = (heat + dt * (0.07 * thr * thr * (0.5 + frac) * heatMul - cool)).coerceIn(0.0, 1.0)
            if (heat >= 1.0) {
                overheat = 4.0
                combo = 1.0
                hud.popup("OVERHEAT!", Pal.RED)
                hud.flash(Pal.RED)
            }
        }
    }

    private fun refreshRows() {
        rows.forEach { it.second() }
    }

    // ---------------------------------------------------------------- ライフサイクル

    /** 内部表示パネルの開閉 */
    fun showInside(on: Boolean) {
        insideKey.on = on
        insideKey.visibility = if (on) View.GONE else View.VISIBLE
        viewBar.visibility = if (on) View.VISIBLE else View.GONE
        hud.compact = on   // エンジンを見やすいよう計器を 1 行表示にする
    }

    /** 描画モード (0 外観 1 透視 2 断面 3 温度 4 応力) */
    fun setViewMode(mode: Int) {
        viewMode = mode
        modeSel.selected = mode
        pushView()
    }

    /** 描画モード・視点をネイティブへ */
    private fun pushView() {
        backend.setView(viewPreset, viewSerial, viewMode, 0f, 0f, 1f, 0f, 0, 0)
    }

    fun start() {
        viewSerial = (System.nanoTime() and 0x3fffffff).toInt()
        pushView()
        onAutoOrbit?.invoke(if (orbit) 0.12f else 0f)
        val gained = state.collectOffline(System.currentTimeMillis())
        if (gained >= 1) {
            hud.popup("おかえり! +¥${fmtMoney(gained)}", Pal.AMBER)
            hud.burstConfetti(150)
        }
        if (summary == null || EngineOwner.token !== this) reloadEngine()
        running = true
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        save()
    }

    fun save() {
        state.lastSeenMs = System.currentTimeMillis()
        store.save(state.toJson())
    }

    companion object {
        /**
         * 自動ダイナモの負荷率。x = (rpm − idle)/(0.9·red − idle)。
         * x ≤ 1 は 0.9·x^1.6 (回転とともに増える)、x > 1 は急勾配にして過回転を抑える (蒸気機関など)。
         */
        fun dynoLoad(x: Double): Double =
            if (x <= 1.0) 0.9 * x.coerceAtLeast(0.0).pow(1.6) else min(3.0, 0.9 + 6.0 * (x - 1.0))

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

        /** HUD に出す特性チップ */
        fun traitsOf(s: EngineSummary, family: String, cycle: String): List<Pair<String, Int>> {
            val t = ArrayList<Pair<String, Int>>()
            when (family) {
                "turbine" -> { t += "ジェット推力" to Pal.CYAN; t += "スプールラグ" to Pal.DIM }
                "electric" -> { t += "効率 90%" to Pal.GREEN; t += "低速から最大トルク" to Pal.CYAN }
                "wankel" -> { t += "ロータリー" to Pal.VIOLET; t += "燃費悪い" to Pal.RED }
            }
            if (cycle.startsWith("diesel")) t += "ディーゼル 高効率" to Pal.GREEN
            if (cycle == "steam") t += "蒸気 石炭焚き" to Pal.DIM
            if (cycle == "stirling") t += "燃料不要" to Pal.GREEN
            if (cycle.endsWith("2")) t += "2スト" to Pal.AMBER
            if (s.induction == 1) t += "ターボ" to Pal.VIOLET
            if (s.induction == 2) t += "スーパーチャージャー" to Pal.VIOLET
            if (s.isCombustion) {
                if (s.redlineRpm >= 8500) t += "高回転" to Pal.AMBER
                if (s.cylinders > 0 && s.displacementL / s.cylinders >= 0.9) t += "大トルク" to Pal.RED
                if (s.cylinders == 1) t += "単気筒の鼓動" to Pal.DIM
                if (s.cylinders >= 8) t += "多気筒 滑らか" to Pal.CYAN
            }
            if (s.propeller) t += "プロペラ負荷" to Pal.DIM
            return t
        }

        fun prefsStore(ctx: Context) = object : Store {
            private val sp = ctx.getSharedPreferences("engine_empire", Context.MODE_PRIVATE)
            override fun load(): String? = sp.getString("state", null)
            override fun save(json: String) { sp.edit().putString("state", json).apply() }
        }
    }
}
