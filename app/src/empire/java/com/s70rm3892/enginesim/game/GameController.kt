package com.s70rm3892.enginesim.game

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/**
 * ENGINE EMPIRE のゲーム進行 (昼のシフト ↔ 夜のガレージ)。
 *
 * 昼: 制限時間のあいだダイナモでエンジンを回す。実出力が収入、燃料代が支出。依頼 (エンジン特性に合わせて生成)
 *     をこなすと ¥ と ★。熱が溜まるとオーバーヒート、無理をすると耐久が減り、0 でブロー (今日の稼ぎ半減)。
 * 夜: 結果を見て、¥/★ で強化ボード・チューン・仕様・新エンジンを選んで買う。明日のイベント予報も見て決める。
 */
class GameController(
    private val activity: Activity,
    private val backend: ConsoleBackend,
    private val engineSource: EngineSource,
    private val store: Store,
) {
    interface EngineSource {
        fun catalogJson(id: String): String
        fun customJson(params: JSONObject): String
    }

    interface Store {
        fun load(): String?
        fun save(json: String)
    }

    enum class Phase { NIGHT, DAY, RACE }
    enum class NightTab(val label: String) { BOARD("強化ボード"), TUNE("チューン"), ENGINES("エンジン"), GOALS("目標"), RACE("ゼロヨン") }
    private enum class RaceState { COUNTDOWN, RUN, DONE }

    val state: GameState = GameState.fromJson(store.load())
    var phase = Phase.NIGHT
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val tel = FloatArray(Tel.COUNT)
    private var running = false
    private val rnd = Random(System.nanoTime())

    private var def = GameRoster.byKey(state.current)
    private var summary: EngineSummary? = null
    private var family = "reciprocating"
    private var cycle = "otto4"
    var run: RunSession? = null
        private set
    private val events = ArrayList<RunSession.Ev>()
    private var lastResult: RunResult? = null
    private var lastGoals: List<Goal> = emptyList()

    // レース
    private var raceState = RaceState.COUNTDOWN
    private var raceMt = false
    private var raceGear = 1
    private var raceTimer = 0.0
    private var raceStartDist = 0f
    private var racePerfect = 0

    // 内部表示
    private var viewMode = 0
    private var viewPreset = 0
    private var viewSerial = 1
    private var slow = false
    private var orbit = true
    var onAutoOrbit: ((Float) -> Unit)? = null
    /** ネイティブ演出の強さ (ニトロ/フレンジー中は炎・火花・揺れを増やす) */
    var onEffects: ((Float) -> Unit)? = null
    private var effectsLevel = 1f

    // シフトの演出
    private lateinit var stage: FrameLayout
    private lateinit var nitroBtn: NitroButton
    private lateinit var golden: GoldenBoltView
    private var countdownT = 0.0
    private var coinAcc = 0.0
    private var coinTimer = 0.0
    private var lastEarned = 0.0
    private var tipText = ""
    private var tipTime = 0.0
    private var tallyLines: List<String> = emptyList()
    private var tallyShown = 0
    private var tallyTimer = 0.0

    // UI
    private lateinit var hud: GameHudView
    private lateinit var pedal: PedalButton
    private lateinit var shiftKey: KeyButton
    private lateinit var leaveKey: KeyButton
    private lateinit var insideKey: KeyButton
    private lateinit var viewBar: LinearLayout
    private lateinit var modeSel: SegmentedSelector
    private lateinit var night: LinearLayout
    private lateinit var nightHeader: TextView
    private lateinit var nightReport: TextView
    private lateinit var forecast: TextView
    private lateinit var goKey: KeyButton
    private lateinit var tabs: SegmentedSelector
    private lateinit var pane: FrameLayout
    private lateinit var graph: NodeGraphView
    private lateinit var graphInfo: TextView
    private lateinit var graphAction: KeyButton
    private lateinit var graphBox: LinearLayout
    private lateinit var listBox: LinearLayout
    private var tab = NightTab.BOARD
    private var selectedNode: String? = null
    private val rows = ArrayList<Pair<ShopRow, () -> Unit>>()
    private val descCache = HashMap<String, String>()
    private var refreshTimer = 0.0

    // ================================================================ UI 構築

    fun buildInto(root: FrameLayout, viewportHost: FrameLayout) {
        val ctx = activity
        root.setBackgroundColor(Color.BLACK)
        stage = FrameLayout(ctx)
        stage.addView(viewportHost, match())
        hud = GameHudView(ctx).apply { bottomInset = ctx.dp(8f) }
        stage.addView(hud, match())

        val controls = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        pedal = PedalButton(ctx).apply { label = "踏め!"; accent = Pal.RED }
        shiftKey = KeyButton(ctx, "SHIFT ▲").apply { accent = Pal.CYAN; onPress = { shiftUp() }; visibility = View.GONE }
        controls.addView(pedal, LinearLayout.LayoutParams(ctx.dp(96f).toInt(), ctx.dp(120f).toInt()))
        nitroBtn = NitroButton(ctx).apply { onFire = { fireNitro() }; visibility = View.GONE }
        controls.addView(nitroBtn, LinearLayout.LayoutParams(ctx.dp(92f).toInt(), ctx.dp(92f).toInt()).apply { marginStart = ctx.dp(10f).toInt() })
        controls.addView(shiftKey, LinearLayout.LayoutParams(ctx.dp(96f).toInt(), ctx.dp(96f).toInt()).apply { marginStart = ctx.dp(10f).toInt() })
        stage.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(ctx.dp(12f).toInt(), 0, 0, ctx.dp(12f).toInt()) })

        leaveKey = KeyButton(ctx, "早退").apply { onRelease = { if (phase == Phase.DAY) endDay() } }
        stage.addView(leaveKey, FrameLayout.LayoutParams(ctx.dp(64f).toInt(), ctx.dp(30f).toInt(), Gravity.TOP or Gravity.START)
            .apply { setMargins(ctx.dp(12f).toInt(), ctx.dp(142f).toInt(), 0, 0) })

        golden = GoldenBoltView(ctx).apply { onTap = { tapGolden() }; visibility = View.GONE }
        stage.addView(golden, FrameLayout.LayoutParams(ctx.dp(84f).toInt(), ctx.dp(84f).toInt()))
        buildInsideView(stage)
        root.addView(stage, match())
        buildNight(root)
        showNight()
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    private fun text(sizeSp: Float, color: Int = Pal.TEXT, bold: Boolean = false) = TextView(activity).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun buildInsideView(stage: FrameLayout) {
        val ctx = activity
        insideKey = KeyButton(ctx, "内部を見る", toggle = true).apply { accent = Pal.CYAN; onToggle = { showInside(it) } }
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
        val presetSel = SegmentedSelector(ctx, listOf("全体", "断面", "クランク", "バルブ", "出力軸")).apply {
            onSelect = { i ->
                viewPreset = i
                viewSerial++
                if (i == 1) { viewMode = 2; modeSel.selected = 2 }
                pushView()
            }
        }
        val keys = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        keys.addView(KeyButton(ctx, "スロー ×1/16", toggle = true).apply {
            onToggle = { slow = it; if (it) hud.floatText("映像だけスロー (収入・音は実時間)", Pal.CYAN) }
        }, LinearLayout.LayoutParams(0, ctx.dp(34f).toInt(), 1f))
        keys.addView(KeyButton(ctx, "自動周回", toggle = true).apply {
            on = true
            onToggle = { orbit = it; onAutoOrbit?.invoke(if (it) 0.12f else 0f) }
        }, LinearLayout.LayoutParams(0, ctx.dp(34f).toInt(), 1f))
        keys.addView(KeyButton(ctx, "閉じる").apply { onRelease = { showInside(false) } },
            LinearLayout.LayoutParams(ctx.dp(64f).toInt(), ctx.dp(34f).toInt()))
        viewBar.addView(modeSel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(38f).toInt()))
        viewBar.addView(presetSel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(38f).toInt()))
        viewBar.addView(keys)
        stage.addView(viewBar, FrameLayout.LayoutParams(ctx.dp(320f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(ctx.dp(12f).toInt(), ctx.dp(100f).toInt(), 0, 0) })
    }

    fun showInside(on: Boolean) {
        val allowed = state.perks.internalView
        insideKey.on = on && allowed
        insideKey.visibility = if (!allowed || on || phase == Phase.NIGHT) View.GONE else View.VISIBLE
        viewBar.visibility = if (on && allowed && phase != Phase.NIGHT) View.VISIBLE else View.GONE
        hud.compact = on && allowed
        if (!on) setViewMode(0)
    }

    fun setViewMode(mode: Int) {
        viewMode = mode
        modeSel.selected = mode
        pushView()
    }

    private fun pushView() {
        backend.setView(viewPreset, viewSerial, viewMode, 0f, 0f, 1f, 0f, 0, 0)
    }

    // ---------------------------------------------------------------- 夜のガレージ

    private fun buildNight(root: FrameLayout) {
        val ctx = activity
        night = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; isClickable = true }
        // 左: 今日の結果・明日の予報・出勤
        val left = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(215, 8, 10, 13))
            val pd = ctx.dp(10f).toInt()
            setPadding(pd, pd, pd, pd)
        }
        nightHeader = text(16f, Pal.AMBER, bold = true).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
        nightReport = text(12f)
        forecast = text(12f, Pal.CYAN)
        goKey = KeyButton(ctx, "出勤 ▶").apply { accent = Pal.GREEN; onRelease = { startDay() } }
        left.addView(nightHeader)
        val sc = ScrollView(ctx)
        sc.addView(nightReport)
        left.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        left.addView(forecast)
        left.addView(goKey, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(52f).toInt()).apply { topMargin = ctx.dp(6f).toInt() })
        night.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 36f))

        // 右: タブ
        val right = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            val pd = ctx.dp(4f).toInt()
            setPadding(pd, pd, pd, pd)
        }
        tabs = SegmentedSelector(ctx, NightTab.entries.map { it.label }).apply {
            onSelect = { i -> tab = NightTab.entries[i]; selectedNode = null; rebuildPane() }
        }
        right.addView(tabs, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(42f).toInt()))
        pane = FrameLayout(ctx)
        right.addView(pane, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        night.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 64f))

        // グラフ (ボード / エンジン) とリスト (チューン / 目標 / ゼロヨン)
        graphBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        graph = NodeGraphView(ctx).apply { onSelect = { it -> selectedNode = it.id; refreshGraphDetail() } }
        graphBox.addView(graph, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val detail = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Pal.PANEL)
            val pd = ctx.dp(6f).toInt()
            setPadding(pd, pd, pd, pd)
        }
        graphInfo = text(11f).apply { maxLines = 3 }
        graphAction = KeyButton(ctx, "").apply { onRelease = { graphActionPressed() } }
        detail.addView(graphInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        detail.addView(graphAction, LinearLayout.LayoutParams(ctx.dp(128f).toInt(), ctx.dp(46f).toInt()))
        graphBox.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(64f).toInt()))
        listBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        root.addView(night, match())
    }

    private fun showNight() {
        phase = Phase.NIGHT
        night.visibility = View.VISIBLE
        hud.dayActive = false
        hud.raceActive = false
        leaveKey.visibility = View.GONE
        pedal.visibility = View.GONE
        nitroBtn.visibility = View.GONE
        golden.visibility = View.GONE
        shiftKey.visibility = View.GONE
        setEffects(1f)
        stage.translationX = 0f
        stage.translationY = 0f
        showInside(false)
        refreshNightHeader()
        // 結果は 1 行ずつ出す (集計の手応え)
        tallyLines = reportText().split('\n')
        tallyShown = if (lastResult == null) tallyLines.size else 0
        tallyTimer = 0.0
        nightReport.text = tallyLines.take(tallyShown).joinToString("\n")
        val e = state.nextEvent
        forecast.text = "次のシフト (DAY ${state.day}) の予報: ${e.label}" + if (e != DayEvent.NONE) " — ${e.desc}" else ""
        goKey.text = "DAY ${state.day} 出勤 ▶  (${state.perks.shiftSeconds.toInt()} 秒 / ${def.name})"
        goKey.invalidate()
        rebuildPane()
    }

    private fun refreshNightHeader() {
        nightHeader.text = "¥ ${fmtMoney(state.money)}   ★ ${state.stars}\n" + nextGoalHint()
        // タブに「今買えるもの」の数をバッジ表示
        val board = GameBoard.nodes.count { n ->
            state.canBuyNode(n) && (if (n.currency == Currency.STAR) state.stars >= state.boardCost(n) else state.money >= state.boardCost(n))
        }
        val engines = GameRoster.engines.count { state.canUnlock(it) && state.money >= it.price && state.stars >= it.starCost }
        val tune = UpgradeKind.entries.count { upgradeApplies(def, family, it) && state.tuneAvailable(it) &&
            state.prog(def.key).level(it) < it.maxLevel && state.money >= state.upgradeCost(def, it) }
        val counts = mapOf(NightTab.BOARD to board, NightTab.TUNE to tune, NightTab.ENGINES to engines)
        val labels = NightTab.entries.map { t -> counts[t]?.takeIf { it > 0 }?.let { "${t.label} ($it)" } ?: t.label }
        if (tabs.items != labels) tabs.items = labels
    }

    /** 次の目標: まだ買えない一番安いエンジン (無ければボードのノード) まであといくら */
    private fun nextGoalHint(): String {
        val eng = GameRoster.engines.filter { state.canUnlock(it) && state.money < it.price }.minByOrNull { it.price }
        if (eng != null) return "次の目標: ${eng.name} まで あと ¥${fmtMoney(eng.price - state.money)}"
        val node = GameBoard.nodes.filter { state.canBuyNode(it) && it.currency == Currency.MONEY && state.money < state.boardCost(it) }
            .minByOrNull { state.boardCost(it) } ?: return ""
        return "次の目標: ${node.name} まで あと ¥${fmtMoney(state.boardCost(node) - state.money)}"
    }

    private fun reportText(): String {
        val r = lastResult
        val sb = StringBuilder()
        if (r == null) {
            sb.append(if (state.stats.days == 0) "ようこそ、ENGINE EMPIRE へ。\n\n" +
                "昼はシフト。ダイナモでエンジンを回すほど稼げる。依頼をこなすと ¥ と ★。\n" +
                "回しすぎると熱と耐久に注意。耐久 0 でブロー、その日の稼ぎは半分。\n\n" +
                "夜はガレージ。稼ぎで強化ボード・チューン・新エンジンを選んで買おう。全部は買えない。"
            else "おかえり。DAY ${state.day} の準備をしよう。")
        } else {
            sb.append("■ DAY ${state.day - 1} の結果").append(if (r.event != DayEvent.NONE) " (${r.event.label})" else "").append('\n')
            if (r.blown) sb.append("💥 エンジンブロー! 稼ぎ半減\n")
            sb.append("発電 ¥${fmtMoney(r.earned - r.orderEarned)}\n")
            sb.append("依頼 ${r.orders} 件  ¥${fmtMoney(r.orderEarned)}  ★${r.stars}\n")
            sb.append("アフターファイア ${r.afterfires} 回  スイート ${r.sweetSeconds.toInt()} 秒\n")
            sb.append("耐久 ${r.hpLeft.toInt()} / ${r.maxHp.toInt()}   最高出力 ${"%.0f".format(r.maxPowerKw)} kW\n")
            sb.append("合計 ${if (r.earned >= 0) "+" else ""}¥${fmtMoney(r.earned)}\n")
            if (lastGoals.isNotEmpty()) {
                sb.append("\n★ 目標達成!\n")
                lastGoals.forEach { sb.append("・${it.name} (★${it.stars})\n") }
            }
        }
        return sb.toString()
    }

    private fun rebuildPane() {
        pane.removeAllViews()
        rows.clear()
        when (tab) {
            NightTab.BOARD, NightTab.ENGINES -> {
                pane.addView(graphBox, match())
                refreshGraph()
                val focus = selectedNode ?: if (tab == NightTab.ENGINES) def.key else GameBoard.nodes.first().id
                selectedNode = focus
                graph.selectedId = focus
                graph.centerOn(focus)
                refreshGraphDetail()
            }
            else -> {
                listBox.removeAllViews()
                (listBox.parent as? ViewGroup)?.removeView(listBox)
                val sc = ScrollView(activity)
                sc.addView(listBox)
                pane.addView(sc, match())
                when (tab) {
                    NightTab.TUNE -> buildTune()
                    NightTab.GOALS -> buildGoals()
                    NightTab.RACE -> buildRace()
                    else -> {}
                }
            }
        }
    }

    // --- 強化ボード / エンジンツリー (共通グラフ)

    private fun refreshGraph() {
        if (tab == NightTab.BOARD) {
            graph.lanes = GameBoard.lanes
            graph.laneLocked = { null }
            graph.items = GameBoard.nodes.map { n ->
                val lv = state.lv(n.id)
                val st = when {
                    lv >= n.maxLevel -> NodeGraphView.State.MAXED
                    lv > 0 -> NodeGraphView.State.OWNED
                    state.canBuyNode(n) -> NodeGraphView.State.AVAILABLE
                    else -> NodeGraphView.State.LOCKED
                }
                val cost = state.boardCost(n)
                val price = if (n.currency == Currency.STAR) "★${cost.toInt()}" else "¥${fmtMoney(cost)}"
                val enough = if (n.currency == Currency.STAR) state.stars >= cost else state.money >= cost
                NodeGraphView.Item(
                    n.id, n.name, if (lv >= n.maxLevel) "MAX" else "$price  Lv$lv/${n.maxLevel}", n.lane, n.depth, n.parents, st,
                    affordable = state.canBuyNode(n) && enough, accent = if (n.currency == Currency.STAR) Pal.VIOLET else Pal.AMBER,
                    progress = if (n.maxLevel > 1) lv.toFloat() / n.maxLevel else -1f,
                )
            }
        } else {
            graph.lanes = GameRoster.lanes
            graph.laneLocked = { i -> GameRoster.laneWorkshop[i]?.takeIf { !state.has(it) }?.let { GameBoard.byId(it).name } }
            graph.items = GameRoster.engines.map { d ->
                val st = when {
                    d.key in state.unlocked -> NodeGraphView.State.OWNED
                    state.canUnlock(d) -> NodeGraphView.State.AVAILABLE
                    else -> NodeGraphView.State.LOCKED
                }
                NodeGraphView.Item(
                    d.key, d.name, when {
                        d.key == def.key -> "稼働中"
                        st == NodeGraphView.State.OWNED -> "所持"
                        else -> "¥" + fmtMoney(d.price) + if (d.starCost > 0) " ★${d.starCost}" else ""
                    }, d.lane, d.depth, d.parents, st,
                    affordable = st == NodeGraphView.State.AVAILABLE && state.money >= d.price && state.stars >= d.starCost, current = d.key == def.key,
                )
            }
        }
    }

    private fun refreshGraphDetail() {
        val id = selectedNode ?: return
        if (tab == NightTab.BOARD) {
            val n = GameBoard.byId(id)
            val lv = state.lv(n.id)
            val cost = state.boardCost(n)
            val price = if (n.currency == Currency.STAR) "★${cost.toInt()}" else "¥${fmtMoney(cost)}"
            val req = if (n.parents.isEmpty()) "" else "  必要: " + n.parents.joinToString(" / ") { GameBoard.byId(it).name }
            graphInfo.text = "${n.name}  Lv $lv/${n.maxLevel}  [${GameBoard.lanes[n.lane]}]$req\n${n.desc}"
            val enough = if (n.currency == Currency.STAR) state.stars >= cost else state.money >= cost
            when {
                lv >= n.maxLevel -> { graphAction.text = "MAX"; graphAction.accent = Pal.DIM }
                !state.canBuyNode(n) -> { graphAction.text = "🔒 未開放"; graphAction.accent = Pal.DIM }
                else -> { graphAction.text = "購入 $price"; graphAction.accent = if (enough) (if (n.currency == Currency.STAR) Pal.VIOLET else Pal.AMBER) else Pal.DIM }
            }
        } else {
            val d = GameRoster.byKey(id)
            val parents = d.parents.joinToString(" / ") { GameRoster.byKey(it).name }
            val shop = GameRoster.laneWorkshop[d.lane]?.takeIf { !state.has(it) }?.let { "  要工房: " + GameBoard.byId(it).name } ?: ""
            val req = if (d.parents.isEmpty()) "" else "  必要: $parents のどれか"
            graphInfo.text = "${d.name}  [${GameRoster.lanes[d.lane]}]  目安 +${fmtMoney(d.estNet)}/s$req$shop\n${description(d)}"
            when {
                d.key == def.key -> { graphAction.text = "稼働中"; graphAction.accent = Pal.DIM }
                d.key in state.unlocked -> { graphAction.text = "乗り換える"; graphAction.accent = Pal.GREEN }
                state.canUnlock(d) -> {
                    graphAction.text = "解放 ¥" + fmtMoney(d.price) + if (d.starCost > 0) " ★${d.starCost}" else ""
                    graphAction.accent = if (state.money >= d.price && state.stars >= d.starCost) Pal.AMBER else Pal.DIM
                }
                else -> { graphAction.text = "🔒 未解放"; graphAction.accent = Pal.DIM }
            }
        }
        graphAction.invalidate()
    }

    private fun graphActionPressed() {
        val id = selectedNode ?: return
        if (tab == NightTab.BOARD) {
            val n = GameBoard.byId(id)
            if (state.buyNode(n)) {
                hud.burstConfetti(if (n.currency == Currency.STAR) 160 else 60)
                hud.floatText("${n.name} Lv${state.lv(n.id)}", Pal.CYAN, big = true)
                afterPurchase()
            } else if (state.canBuyNode(n)) hud.floatText("足りない", Pal.RED)
        } else {
            val d = GameRoster.byKey(id)
            when {
                d.key == def.key -> {}
                d.key in state.unlocked -> switchEngine(d)
                state.unlock(d) -> {
                    hud.popup("NEW ENGINE!", Pal.AMBER)
                    hud.burstConfetti(220)
                    hud.flash(Pal.AMBER)
                    switchEngine(d)
                }
                state.canUnlock(d) -> hud.floatText("足りない", Pal.RED)
            }
            afterPurchase()
        }
    }

    private fun afterPurchase() {
        save()
        refreshNightHeader()
        if (tab == NightTab.BOARD || tab == NightTab.ENGINES) {
            refreshGraph()
            refreshGraphDetail()
        } else rows.forEach { it.second() }
        goKey.text = "DAY ${state.day} 出勤 ▶  (${state.perks.shiftSeconds.toInt()} 秒 / ${def.name})"
        goKey.invalidate()
    }

    private fun description(d: GameEngineDef): String = descCache.getOrPut(d.key) {
        when {
            d.catalogId != null -> runCatching { JSONObject(engineSource.catalogJson(d.catalogId)).optString("description") }.getOrDefault("")
            else -> "すべてはここから。気筒追加で 12 気筒まで育つ単気筒。"
        }
    }

    // --- リスト (チューン / 目標 / ゼロヨン)

    private fun rowLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(56f).toInt()).apply {
        topMargin = activity.dp(3f).toInt()
    }

    private fun note(t: String, color: Int = Pal.DIM) = text(11f, color).apply {
        text = t
        val pd = activity.dp(6f).toInt()
        setPadding(pd, pd, pd, pd)
    }

    private fun addRow(update: ShopRow.() -> Unit, click: ShopRow.() -> Unit): ShopRow {
        val r = ShopRow(activity)
        r.onClick = { click(r) }
        val u = { update(r); r.refresh() }
        u()
        rows += r to u
        listBox.addView(r, rowLp())
        return r
    }

    private fun buildTune() {
        val prog = state.prog(def.key)
        listBox.addView(note("${def.name}  — 仕様は 1 台に 1 つ (排他)。チューンはこのエンジン専用。", Pal.TEXT))
        for (sp in EngineSpecialty.entries.filter { it != EngineSpecialty.NONE }) {
            addRow({
                val cur = prog.specialty == sp
                val (st, yen) = state.specialtyCost(def)
                title = sp.label
                subtitle = sp.desc
                current = cur
                price = when {
                    cur -> "選択中"
                    st > 0 -> "★$st"
                    else -> "¥${fmtMoney(yen)}"
                }
                affordable = !cur && state.stars >= st && state.money >= yen && family != "turbine" && family != "electric"
                accent = Pal.VIOLET
            }) {
                if (family == "turbine" || family == "electric") { hud.floatText("この機種には仕様がない", Pal.DIM); return@addRow }
                if (state.setSpecialty(def, sp)) {
                    celebrate(this)
                    reloadEngine()
                    afterPurchase()
                }
            }
        }
        for (u in UpgradeKind.entries) {
            if (!upgradeApplies(def, family, u)) continue
            addRow({
                val lv = prog.level(u)
                val maxed = lv >= u.maxLevel
                val avail = state.tuneAvailable(u)
                title = "${u.label}  Lv $lv/${u.maxLevel}"
                subtitle = if (avail) u.desc else "🔒 過給ショップ (強化ボード) が必要"
                level = lv
                maxLevel = u.maxLevel
                val cost = state.upgradeCost(def, u)
                price = if (maxed) "MAX" else "¥" + fmtMoney(cost)
                affordable = avail && !maxed && state.money >= cost
                accent = if (u == UpgradeKind.CYLINDERS || u == UpgradeKind.BOOST) Pal.VIOLET else Pal.AMBER
            }) {
                if (state.buyTune(def, u)) {
                    celebrate(this)
                    reloadEngine()
                    afterPurchase()
                }
            }
        }
    }

    private fun celebrate(row: ShopRow) {
        row.celebrate()
        hud.burstConfetti(50)
    }

    private fun buildGoals() {
        val done = Goals.all.count { it.id in state.goalsDone }
        listBox.addView(note("目標 $done / ${Goals.all.size}  — 達成すると ★ がもらえる (夜に判定)", Pal.TEXT))
        for (g in Goals.all.sortedBy { it.id in state.goalsDone }) {
            addRow({
                val ok = g.id in state.goalsDone
                title = (if (ok) "✓ " else "") + g.name
                subtitle = g.desc
                price = "★${g.stars}"
                current = ok
                affordable = false
                accent = Pal.VIOLET
            }) {}
        }
        listBox.addView(note("累計: ${state.stats.days} 日 / 依頼 ${state.stats.ordersDone} 件 / ¥${fmtMoney(state.stats.totalEarned)} / 最高日給 ¥${fmtMoney(state.stats.bestDay)}"))
    }

    private fun buildRace() {
        val s = summary
        when {
            !state.perks.dragRace -> listBox.addView(note("🔒 強化ボードの「車両ベイ」(工房) を買うと、夜にゼロヨンを 1 回走れる。"))
            s == null || s.propeller || s.isTurbine || s.gears <= 0 -> listBox.addView(note("このエンジンは車両に載らない (プロペラ/ジェット/蒸気)。"))
            state.raceUsedDay == state.day -> listBox.addView(note("今夜はもう走った。また明日の夜に。"))
            else -> {
                listBox.addView(note("ゼロヨン (402m) を 1 回だけ走れる。ベスト更新で報酬 2 倍 + ★1。MT はシフトランプが赤の瞬間に SHIFT で PERFECT。"))
                addRow({ title = "ゼロヨン MT"; subtitle = "自分で変速。PERFECT 1 回ごとに報酬 +10%"; price = "START"; affordable = true; accent = Pal.RED }) { startRace(true) }
                addRow({ title = "ゼロヨン AT"; subtitle = "トルコン AT に任せる (報酬 ×0.7)"; price = "START"; affordable = true; accent = Pal.AMBER }) { startRace(false) }
            }
        }
        val best = state.prog(def.key).bestDragTime
        listBox.addView(note(if (best > 0) "このエンジンのベスト: ${"%.3f".format(best)} s" else "記録なし"))
    }

    // ================================================================ エンジン

    private fun switchEngine(d: GameEngineDef) {
        state.current = d.key
        def = d
        reloadEngine()
        save()
    }

    fun reloadEngine() {
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
        val s = summary ?: return
        hud.engineName = def.name + (if (def.cylinderStep > 0) "  (${s.cylinders}気筒)" else "") +
            (if (prog.specialty != EngineSpecialty.NONE) "  [${prog.specialty.label}]" else "")
        hud.traits = traitsOf(s, family, cycle)
        hud.redline = s.redlineRpm
        hud.idle = s.idleRpm
        hud.isTurbine = s.isTurbine
        val z = RunSession.sweetZone(family, cycle, s.propeller)
        hud.sweetLo = z.first.toFloat()
        hud.sweetHi = z.second.toFloat()
    }

    fun profile(): EngineProfile? {
        val s = summary ?: return null
        val p = state.prog(def.key)
        val tuneBoost = 1.0 + 0.04 * p.levels.values.sum()
        val estOut = def.estNet / 0.6 * tuneBoost
        val defEff = when {
            family == "electric" -> 0.85
            family == "turbine" -> 0.25
            cycle.startsWith("diesel") -> 0.36
            else -> 0.28
        }
        return EngineProfile(
            redline = s.redlineRpm.toDouble(), idle = s.idleRpm.toDouble(), family = family, cycle = cycle,
            boostCapable = s.induction != 0, propeller = s.propeller,
            refPowerKw = if (p.bestPowerKw > 0) p.bestPowerKw else estOut,
            refEff = if (p.bestEff > 0) p.bestEff else defEff,
            refNet = def.estNet * tuneBoost, specialty = if (family == "turbine" || family == "electric") EngineSpecialty.NONE else p.specialty,
        )
    }

    // ================================================================ 昼のシフト

    fun startDay() {
        if (phase != Phase.NIGHT) return
        val prof = profile() ?: kotlin.run { reloadEngine(); profile() } ?: return
        run = RunSession(prof, state.perks, state.nextEvent, state.multiplier)
        phase = Phase.DAY
        night.visibility = View.GONE
        pedal.visibility = View.VISIBLE
        leaveKey.visibility = View.VISIBLE
        hud.dayActive = true
        hud.day = state.day
        hud.eventLabel = if (state.nextEvent == DayEvent.NONE) "" else "${state.nextEvent.label}: ${state.nextEvent.desc}"
        hud.sweetLo = run!!.sweetLo.toFloat()
        hud.sweetHi = run!!.sweetHi.toFloat()
        hud.maxHp = state.perks.maxHp.toFloat()
        hud.comboCap = state.perks.comboCap.toFloat()
        hud.popup("DAY ${state.day}", Pal.AMBER)
        nitroBtn.visibility = View.VISIBLE
        countdownT = 3.0
        lastEarned = 0.0
        coinAcc = 0.0
        showInside(false)
        if (state.stats.days < 2) showTip("pedal", "ペダルを押して、タコメータの光る帯に針を合わせよう", 8.0)
    }

    /** 初めての状況でだけ出すヒント */
    private fun showTip(key: String, text: String, seconds: Double) {
        if (key in state.tipsSeen) return
        state.tipsSeen += key
        tipText = text
        tipTime = seconds
    }

    private fun haptic(strong: Boolean) {
        hud.performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun setEffects(level: Float) {
        if (level != effectsLevel) { effectsLevel = level; onEffects?.invoke(level) }
    }

    private fun fireNitro() {
        val r = run ?: return
        if (countdownT > 0) return
        events.clear()
        if (r.fireNitro(events)) handleEvents() else hud.floatText("ゲージが足りない", Pal.DIM)
    }

    private fun tapGolden() {
        val r = run ?: return
        events.clear()
        if (r.collectGolden(events)) {
            hud.spawnCoins(10)
            handleEvents()
        }
    }

    private fun endDay() {
        val r = run ?: return
        val result = r.result()
        state.closeDay(result, def.key)
        lastResult = result
        lastGoals = Goals.evaluate(state)
        state.nextEvent = DayEvent.roll(state.day, rnd)
        run = null
        save()
        if (lastGoals.isNotEmpty()) hud.burstConfetti(200)
        showNight()
    }

    private fun sample() = Sample(
        rpm = tel[Tel.RPM].toDouble(), loadNm = tel[Tel.LOAD_NM].toDouble(), powerKw = tel[Tel.POWER_KW].toDouble(),
        thrustKn = tel[Tel.THRUST_KN].toDouble(), fuelKw = tel[Tel.FUEL_KW].toDouble(), efficiency = tel[Tel.EFFICIENCY].toDouble(),
        boostBar = tel[Tel.BOOST_BAR].toDouble(), limiter = tel[Tel.LIMITER] > 0.5f, afterfireCount = tel[Tel.AFTERFIRE_COUNT].toInt(),
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            step(0.033)
            handler.postDelayed(this, 33)
        }
    }

    fun step(dt: Double) {
        pedal.advance(dt.toFloat())
        val n = backend.getTelemetry(tel)
        val s = summary
        if (s != null && n >= Tel.COUNT) {
            when (phase) {
                Phase.DAY -> stepDay(dt, s)
                Phase.RACE -> stepRace(dt)
                Phase.NIGHT -> backend.setControls(0f, 0f, false, 0f, 0f, true, false, 1, 1f, 0L, true, 0, 0f, 0f)
            }
            hud.rpm = tel[Tel.RPM]
            hud.limiter = tel[Tel.LIMITER] > 0.5f
            hud.powerKw = if (s.isTurbine) tel[Tel.POWER_KW] else tel[Tel.LOAD_NM] * tel[Tel.RPM] * (2 * Math.PI.toFloat() / 60f) / 1000f
            hud.fuelKw = tel[Tel.FUEL_KW]
            hud.efficiency = tel[Tel.EFFICIENCY]
            hud.boostBar = tel[Tel.BOOST_BAR]
            hud.thrustKn = tel[Tel.THRUST_KN]
        }
        hud.money = state.money
        hud.stars = state.stars
        hud.multiplier = state.multiplier
        hud.autoLevel = state.perks.assistLevel
        hud.advance(dt.toFloat())
        nitroBtn.advance(dt.toFloat())
        if (golden.visibility == View.VISIBLE) golden.advance(dt.toFloat())
        if (phase == Phase.DAY) {
            stage.translationX = hud.shakeX
            stage.translationY = hud.shakeY
        }
        if (phase == Phase.NIGHT && tallyShown < tallyLines.size) {
            tallyTimer += dt
            if (tallyTimer > 0.16) {
                tallyTimer = 0.0
                tallyShown++
                nightReport.text = tallyLines.take(tallyShown).joinToString("\n")
                val line = tallyLines[tallyShown - 1]
                if (line.startsWith("合計") || line.startsWith("・")) { hud.burstConfetti(70); haptic(true) } else if (line.isNotBlank()) haptic(false)
            }
        }
        if (phase == Phase.NIGHT && night.visibility == View.VISIBLE) {
            if (tab == NightTab.BOARD || tab == NightTab.ENGINES) graph.advance(dt.toFloat())
            refreshTimer += dt
            if (refreshTimer > 0.5) { refreshTimer = 0.0; refreshNightHeader() }
        }
    }

    private fun stepDay(dt: Double, s: EngineSummary) {
        val r = run ?: return
        val rpm = tel[Tel.RPM].toDouble()
        val pedalThr = pedal.value.toDouble()
        // 開始前のカウントダウン (空ぶかしはできるが、まだ稼ぎにならない)
        if (countdownT > 0) {
            val before = countdownT
            countdownT -= dt
            hud.countdown = when { countdownT > 2 -> "3"; countdownT > 1 -> "2"; countdownT > 0 -> "1"; else -> "GO!" }
            if (before.toInt() != countdownT.toInt() || countdownT <= 0) { haptic(countdownT <= 0); if (countdownT <= 0) hud.shake(6f) }
            backend.setControls(0f, pedalThr.toFloat(), false, 0f, 0f, true, false, 1, 1f, 0L, true, 0, 0f, 0f)
            updateDayHud(r)
            return
        }
        if (hud.countdown == "GO!" && r.time > 0.6) hud.countdown = ""
        var thr = if (pedal.pressed || pedalThr > 0.01) pedalThr else (r.autoBlipThrottle(rpm) ?: r.assistThrottle(rpm))
        val cut = r.overheat > 0 || r.blown
        if (cut) thr = 0.0
        val red = s.redlineRpm.toDouble()
        val lo = if (s.isElectric) 0.0 else s.idleRpm.toDouble()
        val load = RunSession.dynoLoad(((rpm - lo) / max(1.0, 0.9 * red - lo)).coerceIn(0.0, 2.0)).toFloat()
        backend.setControls(0f, thr.toFloat(), false, load, 0f, !cut, false, 1, if (slow) 1f / 16 else 1f, 0L, true, 0, 0f, 0f)
        events.clear()
        r.step(dt, sample(), thr, events)
        handleEvents()
        // 稼ぎに応じてコインを飛ばす
        val gained = r.earned - lastEarned
        lastEarned = r.earned
        if (gained > 0) coinAcc += gained
        coinTimer += dt
        if (coinTimer > 0.3) {
            coinTimer = 0.0
            if (coinAcc > 0) hud.spawnCoins((1 + Math.log10(1 + coinAcc)).toInt().coerceIn(1, 6))
            coinAcc = 0.0
        }
        setEffects(if (r.nitroTime > 0 || r.frenzyTime > 0) 2.2f else 1f)
        updateDayHud(r)
        if (r.finished) {
            if (r.blown) handler.postDelayed({ if (run === r) endDay() }, 1500) else endDay()
        }
    }

    private fun handleEvents() {
        for (e in events) when (e) {
            is RunSession.Ev.Text -> hud.floatText(e.text, e.color, e.big).also { if (e.big) { hud.shake(5f); haptic(false) } }
            is RunSession.Ev.Popup -> hud.popup(e.text, e.color)
            is RunSession.Ev.OrderDone -> {
                hud.floatText("依頼達成 +¥${fmtMoney(e.order.reward)}" + if (e.order.stars > 0) " ★${e.order.stars}" else "", Pal.GREEN, big = true)
                hud.burstConfetti(80)
                hud.spawnCoins(8)
                hud.shake(7f)
                hud.flash(Pal.GREEN)
                haptic(true)
            }
            RunSession.Ev.Overheat -> {
                hud.popup("OVERHEAT!", Pal.RED); hud.flash(Pal.RED); hud.shake(12f); haptic(true)
                showTip("overheat", "オーバーヒートは回を重ねるほど停止が長く、ダメージも増える。熱 72〜92% で抜くのが上手い運転 (HOT ×1.25)", 8.0)
            }
            RunSession.Ev.Blown -> { hud.popup("BLOWN!", Pal.RED); hud.flash(Color.WHITE); hud.burstConfetti(80); hud.shake(24f); haptic(true) }
            RunSession.Ev.SweetIn -> {
                hud.floatText("IN!", Pal.CYAN)
                if (state.stats.days < 3) showTip("band", "帯 = 電力会社の指令。帯の中で出した電力は満額、外だと 6 割。コンボも帯の中だけ", 7.0)
            }
            is RunSession.Ev.Groove -> {
                val (txt, col) = when (e.level) { 1 -> "NICE!" to Pal.CYAN; 2 -> "GREAT!!" to Pal.VIOLET; else -> "PERFECT!!!" to Pal.AMBER }
                hud.popup(txt, col)
                hud.shake(3f + 3f * e.level)
                if (e.level >= 3) hud.burstConfetti(120)
                haptic(e.level >= 2)
            }
            RunSession.Ev.NitroReady -> {
                hud.floatText("NITRO READY", Color.rgb(130, 190, 255), big = true)
                showTip("nitro", "ニトロが溜まった! 押すと 収入×2 (でも熱も×1.8)", 6.0)
                haptic(false)
            }
            RunSession.Ev.NitroStart -> { hud.popup("NITRO!!", Color.rgb(130, 190, 255)); hud.flash(Color.rgb(90, 150, 255)); hud.shake(14f); haptic(true) }
            RunSession.Ev.NitroEnd -> hud.floatText("NITRO END", Pal.DIM)
            RunSession.Ev.GoldenSpawn -> {
                showTip("golden", "金のボルトが出た! タップで拾おう", 5.0)
                haptic(false)
            }
            is RunSession.Ev.GoldenGot -> { hud.popup(e.text, e.color); hud.flash(Color.rgb(255, 210, 80)); hud.burstConfetti(100); hud.shake(8f); haptic(true) }
            RunSession.Ev.Knock -> {
                hud.floatText("ノッキング! 低回転で踏みすぎ", Pal.RED, big = true)
                hud.shake(6f)
                showTip("knock", "低回転で全開はノッキング (耐久が減る)。回転を上げてから踏もう", 6.0)
                haptic(true)
            }
            RunSession.Ev.Fever -> { hud.popup("FEVER!!", Pal.VIOLET); hud.burstConfetti(150); hud.shake(10f); haptic(true) }
            is RunSession.Ev.Milestone -> { hud.floatText("今日 ¥${fmtMoney(e.amount)} 突破!", Pal.AMBER, big = true); hud.burstConfetti(90); haptic(false) }
        }
        events.clear()
    }

    private fun updateDayHud(r: RunSession) {
        hud.todayEarned = r.earned
        hud.incomePerSec = r.incomeRate
        hud.combo = r.combo.toFloat()
        hud.heat = r.heat.toFloat()
        hud.overheat = r.overheat.toFloat()
        hud.hp = r.hp.toFloat()
        hud.timeLeft = r.timeLeft.toFloat()
        hud.duration = r.duration.toFloat()
        hud.orders = r.orders.map { GameHudView.OrderCard(it.kind.icon + " " + it.title, it.detail, it.progress.toFloat(), it.reward, it.stars, it.done) }
        hud.bandCenter = r.bandCenter.toFloat()
        hud.bandWidth = r.bandWidth.toFloat()
        hud.inBand = r.inBand
        hud.grooveTime = r.grooveTime.toFloat()
        hud.nitroOn = r.nitroTime > 0
        hud.frenzyOn = r.frenzyTime > 0
        hud.knocking = r.knocking
        hud.fever = r.combo >= state.perks.comboCap - 1e-6
        hud.hot = r.hot
        hud.overheatCount = r.overheatCount
        nitroBtn.level = r.nitro.toFloat()
        nitroBtn.active = if (r.nitroTime > 0) (r.nitroTime / state.perks.nitroSeconds).toFloat() else 0f
        // 金のボルト
        if (r.goldenLife > 0) {
            if (golden.visibility != View.VISIBLE) {
                golden.visibility = View.VISIBLE
                golden.translationX = (stage.width * r.goldenX).toFloat() - golden.width / 2f
                golden.translationY = (stage.height * r.goldenY).toFloat() - golden.height / 2f
            }
            golden.life = (r.goldenLife / 5.0).toFloat()
        } else golden.visibility = View.GONE
        // ヒント
        if (tipTime > 0) tipTime -= 0.033
        hud.tip = if (tipTime > 0) tipText else ""
        if (state.stats.days < 1 && r.time > 15.0) showTip("orders", "右の依頼をこなすと ¥ と ★。★ は夜の工房で使う", 6.0)
    }

    // ================================================================ 夜のゼロヨン

    private fun startRace(mt: Boolean) {
        phase = Phase.RACE
        state.raceUsedDay = state.day
        raceState = RaceState.COUNTDOWN
        raceMt = mt
        raceGear = 1
        raceTimer = 3.0
        racePerfect = 0
        night.visibility = View.GONE
        pedal.visibility = View.VISIBLE
        hud.raceActive = true
        shiftKey.visibility = if (mt) View.VISIBLE else View.GONE
        save()
    }

    private fun shiftUp() {
        if (phase != Phase.RACE || raceState != RaceState.RUN || !raceMt) return
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

    private fun stepRace(dt: Double) {
        val brake = raceState != RaceState.RUN
        val thr = if (raceState == RaceState.DONE) 0f else pedal.value
        backend.setControls(0f, thr, false, 0f, 0f, true, false, raceGear, if (slow) 1f / 16 else 1f, 0L, true,
            if (raceMt) 1 else 2, if (brake) 1f else 0f, 0f)
        when (raceState) {
            RaceState.COUNTDOWN -> {
                raceTimer -= dt
                hud.raceStage = if (raceTimer > 0) "${raceTimer.toInt() + 1}" else "GO!"
                if (raceTimer <= 0) {
                    raceState = RaceState.RUN
                    raceTimer = 0.0
                    raceStartDist = tel[Tel.DISTANCE_M]
                    hud.flash(Color.WHITE)
                }
            }
            RaceState.RUN -> {
                raceTimer += dt
                if (raceTimer > 0.8) hud.raceStage = ""
                val d = tel[Tel.DISTANCE_M] - raceStartDist
                hud.raceDistance = d
                hud.raceTime = raceTimer.toFloat()
                if (d >= 402.336f) finishRace(true) else if (raceTimer > 60) finishRace(false)
            }
            RaceState.DONE -> {
                raceTimer -= dt
                if (raceTimer <= 0) {
                    hud.raceActive = false
                    hud.raceStage = ""
                    shiftKey.visibility = View.GONE
                    showNight()
                }
            }
        }
        hud.raceSpeed = tel[Tel.SPEED_KMH]
        hud.raceGear = if (raceMt) "$raceGear" else if (tel[Tel.EFF_GEAR] > 0) "D${tel[Tel.EFF_GEAR].toInt()}" else "N"
        hud.raceBest = state.prog(def.key).bestDragTime.toFloat()
    }

    private fun finishRace(ok: Boolean) {
        raceState = RaceState.DONE
        val t = raceTimer
        raceTimer = 3.0
        if (!ok) { hud.raceStage = "DNF"; return }
        val prog = state.prog(def.key)
        val newBest = prog.bestDragTime <= 0 || t < prog.bestDragTime
        if (newBest) prog.bestDragTime = t
        if (state.stats.bestDrag <= 0 || t < state.stats.bestDrag) state.stats.bestDrag = t
        var reward = max(20.0, def.estNet) * 30.0 * (12.0 / t).pow(2) * state.multiplier * (1 + 0.1 * racePerfect)
        if (!raceMt) reward *= 0.7
        if (newBest) { reward *= 2; state.stars += 1 }
        state.earn(reward)
        lastGoals = Goals.evaluate(state)
        hud.raceStage = "%.3f s".format(t)
        hud.popup(if (newBest) "NEW RECORD! +¥${fmtMoney(reward)} ★1" else "+¥${fmtMoney(reward)}", if (newBest) Pal.GREEN else Pal.AMBER)
        hud.burstConfetti(if (newBest) 300 else 100)
        save()
    }

    // ================================================================ ライフサイクル

    fun start() {
        if (summary == null || EngineOwner.token !== this) reloadEngine()
        viewSerial = (System.nanoTime() and 0x3fffffff).toInt()
        pushView()
        onAutoOrbit?.invoke(if (orbit) 0.12f else 0f)
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
        store.save(state.toJson())
    }

    /** テスト用: 夜のタブを切り替える */
    fun openTab(t: NightTab) {
        tab = t
        tabs.selected = t.ordinal
        selectedNode = null
        rebuildPane()
    }

    companion object {
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
            override fun load(): String? = sp.getString("state2", null)
            override fun save(json: String) { sp.edit().putString("state2", json).apply() }
        }
    }
}
