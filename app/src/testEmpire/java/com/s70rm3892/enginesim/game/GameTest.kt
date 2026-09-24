package com.s70rm3892.enginesim.game

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.s70rm3892.enginesim.ConsoleBackend
import com.s70rm3892.enginesim.Tel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** ENGINE EMPIRE: モデル (経済/セーブ/チューナー) の単体テストと HUD のスクリーンショット。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
class GameTest {
    private fun asset(id: String): JSONObject =
        JSONObject(RuntimeEnvironment.getApplication().assets.open("engines/$id.json").bufferedReader().use { it.readText() })

    @Test
    fun moneyFormat() {
        assertEquals("999", fmtMoney(999.0))
        assertEquals("1.50K", fmtMoney(1500.0))
        assertEquals("12.3M", fmtMoney(12_345_678.0))
        assertEquals("-2.00B", fmtMoney(-2e9))
    }

    @Test
    fun engineTreeIsConsistent() {
        val keys = GameRoster.engines.map { it.key }
        assertEquals("unique keys", keys.size, keys.toSet().size)
        val roots = GameRoster.engines.filter { it.parents.isEmpty() }
        assertEquals(listOf(GameRoster.ROOT), roots.map { it.key })
        assertEquals(0.0, roots.first().price, 0.0)
        for (d in GameRoster.engines) {
            assertTrue(d.key, (d.catalogId != null) xor (d.custom != null))
            d.catalogId?.let { asset(it) }
            assertTrue(d.key, d.lane in GameRoster.lanes.indices)
            for (pk in d.parents) {
                assertTrue("$pk exists", GameRoster.has(pk))
                val parent = GameRoster.byKey(pk)
                assertTrue("${d.key} deeper than $pk", d.depth > parent.depth)
            }
            if (d.parents.isNotEmpty()) {
                val cheapestParent = d.parents.minOf { GameRoster.byKey(it).price }
                assertTrue("${d.key} costs more than its parent", d.price >= cheapestParent)
            }
        }
        // 同じ列・同じ行に 2 つのノードを置かない (ツリー画面で重ならない)
        val cells = GameRoster.engines.map { it.lane to it.depth }
        assertEquals(cells.size, cells.toSet().size)
        // 組み込みカタログの全機種がツリーに載っている
        val index = JSONObject(RuntimeEnvironment.getApplication().assets.open("engines/index.json").bufferedReader().use { it.readText() })
            .getJSONArray("engines")
        for (i in 0 until index.length()) {
            val id = index.getJSONObject(i).getString("id")
            assertTrue("$id in tree", GameRoster.engines.any { it.catalogId == id })
        }
    }

    @Test
    fun upgradeFamilies() {
        val tiller = GameRoster.byKey(GameRoster.ROOT)
        val v8 = GameRoster.byKey("v8_cross")
        val fan = GameRoster.byKey("turbofan")
        val motor = GameRoster.byKey("motor_pmsm")
        val rotary = GameRoster.byKey("wankel2_13b")
        assertTrue(upgradeApplies(tiller, "reciprocating", UpgradeKind.CYLINDERS))
        assertFalse("catalog engines keep their layout", upgradeApplies(v8, "reciprocating", UpgradeKind.CYLINDERS))
        assertTrue(upgradeApplies(v8, "reciprocating", UpgradeKind.BOOST))
        assertFalse(upgradeApplies(rotary, "wankel", UpgradeKind.CYLINDERS))
        assertTrue(upgradeApplies(fan, "turbine", UpgradeKind.SPOOL))
        assertFalse(upgradeApplies(fan, "turbine", UpgradeKind.BORE))
        assertTrue(upgradeApplies(motor, "electric", UpgradeKind.MAGNETS))
        assertFalse(upgradeApplies(motor, "electric", UpgradeKind.BOOST))
    }

    @Test
    fun tunerAppliesPhysicalChanges() {
        // 直4 ターボ: ボア/圧縮比/レブ/ブースト
        val p = EngineProgress("i4t").apply {
            levels[UpgradeKind.BORE] = 2; levels[UpgradeKind.COMPRESSION] = 1; levels[UpgradeKind.REV] = 2
            levels[UpgradeKind.BOOST] = 3
        }
        val base = asset("i4_20t")
        val t = EngineTuner.apply(base, p)
        assertEquals(base.getDouble("bore") * 1.03, t.getDouble("bore"), 1e-9)
        assertEquals(base.getDouble("compressionRatio") + 0.4, t.getDouble("compressionRatio"), 1e-9)
        assertEquals(base.getDouble("redlineRpm") * 1.06, t.getDouble("redlineRpm"), 1e-6)
        assertEquals(1.3 + 0.6, t.getJSONObject("induction").getDouble("maxBoostBar"), 1e-9)
        assertEquals(1.3, base.getJSONObject("induction").getDouble("maxBoostBar"), 1e-9)  // 元は不変

        // NA のカスタム機に過給機 → ターボ化
        val na = JSONObject().put("family", "reciprocating").put("bore", 0.07).put("induction", JSONObject().put("type", "natural"))
        val t2 = EngineTuner.apply(na, EngineProgress("x").apply { levels[UpgradeKind.BOOST] = 1 })
        assertEquals("turbo", t2.getJSONObject("induction").getString("type"))
        assertEquals(0.2, t2.getJSONObject("induction").getDouble("maxBoostBar"), 1e-9)

        // ロータリー: ボア → 創成半径
        val w = EngineTuner.apply(asset("wankel2_13b"), EngineProgress("rotary").apply { levels[UpgradeKind.BORE] = 10 })
        assertEquals(0.105 * 1.15, w.getJSONObject("wankel").getDouble("generatingRadius"), 1e-9)

        // タービン/モータ
        val f = EngineTuner.apply(asset("turbofan"), EngineProgress("turbofan").apply { levels[UpgradeKind.COMBUSTOR] = 5 })
        assertEquals(120000 * 1.4, f.getJSONObject("turbine").getDouble("maxThrustN"), 1e-6)
        val m = EngineTuner.apply(asset("motor_pmsm"), EngineProgress("pmsm").apply { levels[UpgradeKind.MAGNETS] = 10 })
        assertEquals(420 * 1.8, m.getJSONObject("motor").getDouble("ratedTorque"), 1e-6)

        // 気筒追加 (n 気筒生成器のパラメータ)
        val tiller = GameRoster.byKey("tiller")
        assertEquals(4, EngineTuner.customParams(tiller, EngineProgress("tiller").apply { levels[UpgradeKind.CYLINDERS] = 3 }).getInt("cylinders"))
    }


    @Test
    fun boardIsConsistent() {
        val ids = GameBoard.nodes.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        val cells = GameBoard.nodes.map { it.lane to it.depth }
        assertEquals("no overlapping nodes", cells.size, cells.toSet().size)
        for (n in GameBoard.nodes) {
            assertTrue(n.id, n.lane in GameBoard.lanes.indices)
            for (p in n.parents) {
                assertTrue("$p exists", GameBoard.has(p))
                assertTrue("${n.id} deeper than $p", n.depth > GameBoard.byId(p).depth)
            }
        }
        // 工房の参照はすべて実在するノード
        GameRoster.laneWorkshop.filterNotNull().forEach { assertTrue(it, GameBoard.has(it)) }
    }

    @Test
    fun workshopsGateLanesAndStars() {
        val g = GameState()
        g.money = 1e12
        val stirling = GameRoster.byKey("stirling_alpha")
        assertFalse("外燃系統は蒸気工房が必要", g.canUnlock(stirling))
        assertTrue(g.canUnlock(GameRoster.byKey("i2_270")))
        g.stars = 10
        assertTrue(g.buyNode(GameBoard.byId("goggles")))
        assertTrue(g.buyNode(GameBoard.byId("steamWorks")))
        assertEquals(10 - 1 - 3, g.stars)
        assertTrue(g.canUnlock(stirling))
        // 深い段は ★ も必要
        val deep = GameRoster.byKey("i5_25")
        assertEquals(2, deep.starCost)
        listOf("i2_270", "i3_1200", "i4_20").forEach { g.unlocked += it }
        g.stars = 1
        assertFalse(g.unlock(deep))
        g.stars = 2
        assertTrue(g.unlock(deep))
        assertEquals(0, g.stars)
        // 過給機チューンは過給ショップが必要
        assertFalse(g.tuneAvailable(UpgradeKind.BOOST))
        assertFalse(g.buyTune(GameRoster.byKey("i4_20"), UpgradeKind.BOOST))
    }

    @Test
    fun specialtyIsExclusiveAndCosts() {
        val g = GameState()
        val d = GameRoster.byKey(GameRoster.ROOT)
        assertFalse("★ が必要", g.setSpecialty(d, EngineSpecialty.HIGH_REV))
        g.stars = 2
        assertTrue(g.setSpecialty(d, EngineSpecialty.HIGH_REV))
        assertEquals(0, g.stars)
        assertEquals(EngineSpecialty.HIGH_REV, g.prog(d.key).specialty)
        // 2 回目以降は ¥ (エンジン価格の 50%)
        assertFalse(g.setSpecialty(d, EngineSpecialty.ECO))
        g.money = d.basePrice * 0.5
        assertTrue(g.setSpecialty(d, EngineSpecialty.ECO))
        assertEquals(0.0, g.money, 1e-9)
        val t = EngineTuner.apply(asset("i4_20"), g.prog(d.key))
        assertEquals(asset("i4_20").getDouble("compressionRatio") + 1.0, t.getDouble("compressionRatio"), 1e-9)
    }

    private fun profile(red: Double = 7000.0, cycle: String = "otto4", power: Double = 50.0) =
        EngineProfile(red, 800.0, "reciprocating", cycle, boostCapable = false, propeller = false, refPowerKw = power, refEff = 0.3, refNet = 30.0)

    private fun sample(rpm: Double, kw: Double, limiter: Boolean = false, af: Int = 0) =
        Sample(rpm, kw * 1000 / (rpm * 2 * Math.PI / 60), kw, 0.0, kw / 0.3, 0.3, 0.0, limiter, af)

    @Test
    fun runSessionEarnsAndEnds() {
        val g = GameState()
        val r = RunSession(profile(), g.perks, DayEvent.NONE, 1.0, seed = 1)
        val ev = ArrayList<RunSession.Ev>()
        assertEquals(GameRules.SHIFT_BASE_S, r.duration, 1e-9)
        assertEquals(2, r.orders.size)
        // 帯の中央を追いかける
        while (!r.finished) r.step(0.1, sample(r.bandCenter * 7000, 40.0), 0.6, ev)
        assertTrue("earned ${r.earned}", r.earned > 40.0 * 0.5 * 50)
        assertTrue("combo builds in the sweet zone", r.combo > 2.0)
        assertTrue(r.sweetSeconds > 40)
        assertFalse(r.blown)
        val res = r.result()
        g.closeDay(res, GameRoster.ROOT)
        assertEquals(2, g.day)
        assertEquals(1, g.stats.days)
        assertEquals(40.0, g.prog(GameRoster.ROOT).bestPowerKw, 1e-9)
        assertTrue(g.money > 0)
    }

    @Test
    fun bandMovesAndCombosOnlyInside() {
        val g = GameState()
        val r = RunSession(profile(), g.perks, DayEvent.NONE, 1.0, seed = 11)
        r.orders.clear()
        val ev = ArrayList<RunSession.Ev>()
        val centers = HashSet<Int>()
        // 帯の外 (低回転) にいるとコンボは増えない
        repeat(30) { r.step(0.1, sample(0.05 * 7000 + 800, 5.0), 0.3, ev) }
        assertEquals(1.0, r.combo, 1e-9)
        repeat(300) {
            r.step(0.1, sample(r.bandCenter * 7000, 40.0), 0.6, ev)
            centers += (r.bandCenter * 100).toInt()
        }
        assertTrue("band moves (${centers.size})", centers.size >= 5)
        assertTrue(r.combo > 1.5)
        assertTrue("groove events", ev.any { it is RunSession.Ev.Groove })
    }

    @Test
    fun nitroDoublesIncomeAndHeat() {
        val g = GameState()
        fun run(nitro: Boolean): Triple<RunSession, Double, Double> {
            val r = RunSession(profile(), g.perks, DayEvent.NONE, 1.0, seed = 12)
            r.orders.clear()
            val ev = ArrayList<RunSession.Ev>()
            // ゲージを溜める
            while (r.nitro < 1.0) r.step(0.1, sample(r.bandCenter * 7000, 40.0), 0.5, ev)
            assertTrue(ev.any { it is RunSession.Ev.NitroReady })
            if (nitro) assertTrue(r.fireNitro(ev))
            val e0 = r.earned
            val h0 = r.heat
            repeat(40) { r.step(0.1, sample(r.bandCenter * 7000, 40.0), 0.9, ev) }
            return Triple(r, r.earned - e0, r.heat - h0)
        }
        val (_, plainEarn, plainHeat) = run(false)
        val (boosted, boostEarn, boostHeat) = run(true)
        assertTrue("income ×2 ($boostEarn vs $plainEarn)", boostEarn > plainEarn * 1.6)
        assertTrue("more heat", boostHeat > plainHeat)
        assertFalse("can't fire twice", boosted.fireNitro(ArrayList()))
    }

    @Test
    fun goldenBoltAppearsAndPays() {
        val g = GameState()
        val r = RunSession(profile(), g.perks, DayEvent.NONE, 1.0, seed = 13)
        r.orders.clear()
        val ev = ArrayList<RunSession.Ev>()
        assertFalse("nothing to collect yet", r.collectGolden(ev))
        var t = 0
        while (r.goldenLife <= 0 && t < 400) { r.step(0.1, sample(r.bandCenter * 7000, 40.0), 0.5, ev); t++ }
        assertTrue("spawned within 40 s", r.goldenLife > 0)
        assertTrue(ev.any { it is RunSession.Ev.GoldenSpawn })
        val before = r.earned + r.frenzyTime + r.nitro + r.starsEarned - r.heat
        assertTrue(r.collectGolden(ev))
        assertTrue(ev.any { it is RunSession.Ev.GoldenGot })
        assertEquals(1, r.goldenCollected)
        assertTrue("some reward", r.earned + r.frenzyTime + r.nitro + r.starsEarned - r.heat > before)
    }

    @Test
    fun luggingKnocks() {
        val g = GameState()
        val r = RunSession(profile(), g.perks, DayEvent.NONE, 1.0, seed = 14)
        r.orders.clear()
        val ev = ArrayList<RunSession.Ev>()
        repeat(20) { r.step(0.1, sample(0.3 * 7000, 20.0), 1.0, ev) }
        assertTrue(r.knocking)
        assertTrue(ev.any { it is RunSession.Ev.Knock })
        assertTrue("damage", r.hp < g.perks.maxHp)
        // 電動機はノックしない
        val m = RunSession(profile().copy(family = "electric", cycle = "none"), g.perks, DayEvent.NONE, 1.0, seed = 14)
        repeat(20) { m.step(0.1, sample(0.3 * 7000, 20.0), 1.0, ev) }
        assertFalse(m.knocking)
    }

    @Test
    fun abuseBlowsTheEngine() {
        val g = GameState()
        val r = RunSession(profile(), g.perks, DayEvent.NONE, 1.0, seed = 2)
        val ev = ArrayList<RunSession.Ev>()
        var t = 0
        while (!r.finished && t < 2000) { r.step(0.1, sample(7200.0, 45.0, limiter = true), 1.0, ev); t++ }
        assertTrue("limiter + full throttle blows it", r.blown)
        assertTrue(ev.any { it is RunSession.Ev.Overheat })
        assertTrue(ev.any { it is RunSession.Ev.Blown })
        assertTrue("finished early", r.time < r.duration)
    }

    @Test
    fun ordersComplete() {
        val g = GameState()
        // 多数の依頼を生成して、種類ごとの完了条件が満たせることを確認
        val prof = profile()
        val kinds = HashSet<OrderKind>()
        for (seed in 0L until 60L) {
            val o = OrderFactory.make(prof, g.perks, DayEvent.NONE, kotlin.random.Random(seed))
            kinds += o.kind
            assertTrue(o.reward > 0)
        }
        assertTrue("variety of orders (${kinds.size})", kinds.size >= 8)
        assertFalse("VIP needs the board node", OrderKind.VIP_LIMIT in kinds)
        assertFalse("no boost orders for NA", OrderKind.BOOST in kinds)
        // 高回転キープ: 帯の中を保てば達成
        val r = RunSession(prof, g.perks, DayEvent.NONE, 1.0, seed = 3)
        r.orders.clear()
        r.orders += Order(OrderKind.HOLD_BAND, "t", "d", 1.0, 100.0, 1, 5000.0, 6000.0, 3.0)
        r.orders += Order(OrderKind.PEAK_POWER, "t", "d", 1.0, 50.0, 0, 30.0)
        val ev = ArrayList<RunSession.Ev>()
        repeat(40) { r.step(0.1, sample(5500.0, 35.0), 0.7, ev) }
        assertEquals(2, r.ordersDone)
        assertEquals(1, r.starsEarned)
        assertEquals(150.0, r.orderEarned, 1e-9)
        assertEquals(2, ev.count { it is RunSession.Ev.OrderDone })
    }

    @Test
    fun eventsChangeTheDay() {
        val g = GameState()
        fun earn(e: DayEvent): Double {
            val r = RunSession(profile(), g.perks, e, 1.0, seed = 5)
            r.orders.clear()
            val ev = ArrayList<RunSession.Ev>()
            repeat(100) { r.step(0.1, sample(r.bandCenter * 7000, 40.0), 0.5, ev) }
            return r.earned
        }
        assertTrue(earn(DayEvent.PEAK_DEMAND) > earn(DayEvent.NONE) * 1.3)
        assertTrue(earn(DayEvent.FUEL_SPIKE) < earn(DayEvent.NONE))
        assertEquals(DayEvent.NONE, DayEvent.roll(1, kotlin.random.Random(1)))
    }

    @Test
    fun goalsAwardStarsOnce() {
        val g = GameState()
        g.stats.days = 1
        val got = Goals.evaluate(g)
        assertEquals(listOf("day1"), got.map { it.id })
        assertEquals(1, g.stars)
        assertTrue(Goals.evaluate(g).isEmpty())
    }

    @Test
    fun saveRoundTrip() {
        val g = GameState()
        g.money = 1234.5; g.stars = 7; g.day = 9
        g.unlocked += "i2_270"; g.current = "i2_270"
        g.board["grid"] = 3
        g.prog("i2_270").levels[UpgradeKind.CAMS] = 4
        g.prog("i2_270").specialty = EngineSpecialty.TORQUE
        g.goalsDone += "day1"
        g.stats.ordersDone = 12
        g.nextEvent = DayEvent.HEATWAVE
        val r = GameState.fromJson(g.toJson())
        assertEquals(1234.5, r.money, 1e-9)
        assertEquals(7, r.stars)
        assertEquals(9, r.day)
        assertEquals("i2_270", r.current)
        assertEquals(3, r.lv("grid"))
        assertEquals(4, r.prog("i2_270").level(UpgradeKind.CAMS))
        assertEquals(EngineSpecialty.TORQUE, r.prog("i2_270").specialty)
        assertTrue("day1" in r.goalsDone)
        assertEquals(12, r.stats.ordersDone)
        assertEquals(DayEvent.HEATWAVE, r.nextEvent)
        // 旧版 (v1) のセーブは引き継がずに新規開始
        assertEquals(1, GameState.fromJson("""{"money":5e9,"current":"v8"}""").day)
        assertEquals(0.0, GameState.fromJson("""{"money":5e9}""").money, 0.0)
    }

    @Test
    fun exportMaxTunedEngines() {
        val dir = File("build/game_tuned").apply { mkdirs() }
        for (d in GameRoster.engines) {
            val id = d.catalogId ?: continue
            val p = EngineProgress(d.key).apply { UpgradeKind.entries.forEach { levels[it] = it.maxLevel } }
            File(dir, "${d.key}.json").writeText(EngineTuner.apply(asset(id), p).toString(2))
        }
    }


    // ------------------------------------------------------------ 画面

    private class FakeBackend : ConsoleBackend {
        var controls = 0
        var lastLoad = 0f
        var lastMode = -1
        override fun loadEngine(json: String): String {
            val o = JSONObject(json)
            return """{"id":"x","family":"${o.optString("family", "reciprocating")}","cycle":"otto4","cylinders":4,"displacementL":1.998,
               "boreMm":86.0,"strokeMm":86.0,"rodMm":143.0,"compressionRatio":9.5,"idleRpm":750,"redlineRpm":6800,
               "limiterRpm":7000,"peakTorque":300.0,"gears":6,"propeller":false,"induction":1,"chambers":4,
               "firingOrder":"1-3-4-2","name":"${o.optString("name")}","description":""}"""
        }

        override fun setControls(
            targetRpm: Float, throttle: Float, throttleLink: Boolean, load: Float, sparkOffsetDeg: Float,
            ignition: Boolean, starter: Boolean, gear: Int, timeScale: Float, cylinderCutMask: Long, autoStart: Boolean,
            driveMode: Int, brake: Float, grade: Float,
        ) { controls++; lastLoad = load }

        override fun buildCustomEngine(paramsJson: String) = JSONObject(paramsJson).put("family", "reciprocating").toString()
        override fun setView(preset: Int, presetSerial: Int, mode: Int, yawRate: Float, pitchRate: Float, zoom: Float,
                             sectionOffset: Float, sectionAxis: Int, pvChamber: Int) { lastMode = mode }
        override fun setVolume(gain: Float) {}
        override fun getTelemetry(out: FloatArray): Int {
            out.fill(0f)
            out[Tel.RPM] = 5900f; out[Tel.LOAD_NM] = 290f; out[Tel.FUEL_KW] = 520f; out[Tel.POWER_KW] = 180f
            out[Tel.EFFICIENCY] = 0.33f; out[Tel.BOOST_BAR] = 1.1f; out[Tel.RUNNING] = 1f
            return Tel.COUNT
        }
        override fun getPV(volumeL: FloatArray, pressureBar: FloatArray) = 0
    }

    @Test
    fun dayAndNightScreens() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val backend = FakeBackend()
        val store = object : GameController.Store {
            var saved: String? = GameState().apply {
                money = 12_345.0; stars = 6; day = 4
                unlocked += "i2_270"; unlocked += "wankel1"
                board["goggles"] = 1; board["grid"] = 2; board["overtime"] = 1
                nextEvent = DayEvent.HEATWAVE
                stats.days = 3
            }.toJson()
            override fun load() = saved
            override fun save(json: String) { saved = json }
        }
        val source = object : GameController.EngineSource {
            override fun catalogJson(id: String) = asset(id).toString()
            override fun customJson(params: JSONObject) = JSONObject(params.toString()).put("family", "reciprocating").toString()
        }
        val root = FrameLayout(activity)
        val vp = FrameLayout(activity).apply {
            addView(object : View(activity) {
                override fun onDraw(c: Canvas) { c.drawColor(Color.rgb(22, 26, 32)) }
            })
        }
        val game = GameController(activity, backend, source, store)
        game.buildInto(root, vp)
        game.start()
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))
            val dm = activity.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            val out = File("build/screenshots/$name")
            out.parentFile?.mkdirs()
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // 夜 (起動直後): 強化ボード
        assertEquals(GameController.Phase.NIGHT, game.phase)
        shot("game_night_board.png")
        game.openTab(GameController.NightTab.ENGINES)
        shot("game_night_engines.png")
        game.openTab(GameController.NightTab.TUNE)
        shot("game_night_tune.png")
        game.openTab(GameController.NightTab.GOALS)
        shot("game_night_goals.png")

        // 昼: シフト開始 → 数秒回す
        game.startDay()
        assertEquals(GameController.Phase.DAY, game.phase)
        assertEquals(DayEvent.HEATWAVE, game.run!!.event)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1200))
        shot("game_countdown.png")
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5000))
        assertTrue("auto dyno load engaged", backend.lastLoad > 0.5f)
        assertTrue("earning", game.run!!.earned > 0)
        shot("game_hud.png")
        game.showInside(true)
        game.setViewMode(2)
        assertEquals(2, backend.lastMode)
        shot("game_hud_inside.png")
        game.showInside(false)
        // パネルを閉じても表示モードは残り、セーブにも入る
        assertEquals(2, backend.lastMode)
        assertEquals(2, game.state.settings.viewMode)
        assertEquals(2, GameState.fromJson(store.saved).settings.viewMode)

        // シフト終了 (時間切れまで進める) → 夜に戻り、日付が進む
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(70_000))
        assertEquals(GameController.Phase.NIGHT, game.phase)
        assertEquals(5, game.state.day)
        shot("game_night_result.png")
        game.stop()
        assertTrue(store.saved!!.contains("\"day\":5"))
    }
}
