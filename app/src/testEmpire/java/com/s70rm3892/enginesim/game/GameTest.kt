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
    fun rosterIsOrderedAndCatalogExists() {
        val prices = GameRoster.engines.map { it.price }
        assertEquals(prices.sorted(), prices)
        assertEquals(0.0, prices.first(), 0.0)
        for (d in GameRoster.engines) {
            assertTrue(d.key, (d.catalogId != null) xor (d.custom != null))
            d.catalogId?.let { asset(it) }
        }
    }

    @Test
    fun upgradeCostGrowsAndPrestigeResets() {
        val g = GameState()
        val def = GameRoster.engines[1]
        val c0 = g.upgradeCost(def, UpgradeKind.BORE)
        g.prog(def.key).levels[UpgradeKind.BORE] = 3
        assertEquals(c0 * UpgradeKind.BORE.growth * UpgradeKind.BORE.growth * UpgradeKind.BORE.growth, g.upgradeCost(def, UpgradeKind.BORE), 1e-6)

        assertEquals(0, g.prestige())            // 条件未達では何も起きない
        g.earn(4.1e6)
        g.unlocked += def.key
        g.current = def.key
        g.automation = 3
        assertEquals(2, g.pendingTechPoints())   // floor(sqrt(4.1))
        assertEquals(2, g.prestige())
        assertEquals(0.0, g.money, 0.0)
        assertEquals(setOf(GameRoster.engines.first().key), g.unlocked)
        assertEquals(0, g.prog(def.key).level(UpgradeKind.BORE))
        assertEquals(0, g.automation)
        assertEquals(1.2, g.multiplier, 1e-9)
        assertEquals(4.1e6, g.totalEarned, 1e-6)
    }

    @Test
    fun saveRoundTripAndOffline() {
        val g = GameState()
        g.earn(1234.5)
        g.unlocked += "twin"
        g.current = "twin"
        g.prog("twin").levels[UpgradeKind.CAMS] = 4
        g.prog("twin").bestDragTime = 12.3
        g.offlineRate = 10.0
        g.lastSeenMs = 1_000_000L
        val r = GameState.fromJson(g.toJson())
        assertEquals(1234.5, r.money, 1e-9)
        assertEquals("twin", r.current)
        assertTrue("twin" in r.unlocked)
        assertEquals(4, r.prog("twin").level(UpgradeKind.CAMS))
        assertEquals(12.3, r.prog("twin").bestDragTime, 1e-9)
        // 100 秒放置 → 10/s × 100 × 50%
        assertEquals(500.0, r.collectOffline(1_100_000L), 1e-6)
        // 上限 4 時間
        val r2 = GameState.fromJson(g.toJson())
        assertEquals(10.0 * GameRules.OFFLINE_CAP_S * GameRules.OFFLINE_RATE, r2.collectOffline(1_000_000L + 10L * 24 * 3600 * 1000), 1e-6)
        assertEquals(0, GameState.fromJson("garbage").techPoints)
    }

    @Test
    fun upgradeFamilies() {
        val v8 = GameRoster.byKey("v8")
        val fan = GameRoster.byKey("turbofan")
        val motor = GameRoster.byKey("pmsm")
        val rotary = GameRoster.byKey("rotary")
        assertTrue(upgradeApplies(v8, "reciprocating", UpgradeKind.CYLINDERS))
        assertFalse(upgradeApplies(rotary, "wankel", UpgradeKind.CYLINDERS))
        assertTrue(upgradeApplies(fan, "turbine", UpgradeKind.SPOOL))
        assertFalse(upgradeApplies(fan, "turbine", UpgradeKind.BORE))
        assertTrue(upgradeApplies(motor, "electric", UpgradeKind.MAGNETS))
        assertTrue(upgradeApplies(motor, "electric", UpgradeKind.RADIATOR))
        assertFalse(upgradeApplies(motor, "electric", UpgradeKind.BOOST))
    }

    @Test
    fun tunerAppliesPhysicalChanges() {
        // 直4 ターボ: ボア/圧縮比/レブ/ブースト
        val p = EngineProgress("i4t").apply {
            levels[UpgradeKind.BORE] = 2; levels[UpgradeKind.COMPRESSION] = 1; levels[UpgradeKind.REV] = 2
            levels[UpgradeKind.BOOST] = 3; levels[UpgradeKind.RADIATOR] = 5
        }
        val base = asset("i4_20t")
        val t = EngineTuner.apply(base, p)
        assertEquals(base.getDouble("bore") * 1.03, t.getDouble("bore"), 1e-9)
        assertEquals(base.getDouble("compressionRatio") + 0.4, t.getDouble("compressionRatio"), 1e-9)
        assertEquals(base.getDouble("redlineRpm") * 1.06, t.getDouble("redlineRpm"), 1e-6)
        assertEquals(1.3 + 0.6, t.getJSONObject("induction").getDouble("maxBoostBar"), 1e-9)
        assertEquals(1.6, t.getJSONObject("tuning").getDouble("coolingScale"), 1e-9)
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
        val v12 = GameRoster.byKey("v12")
        val cp = EngineTuner.customParams(v12, EngineProgress("v12").apply { levels[UpgradeKind.CYLINDERS] = 8 })
        assertEquals(24, cp.getInt("cylinders"))
        val tiller = GameRoster.byKey("tiller")
        assertEquals(4, EngineTuner.customParams(tiller, EngineProgress("tiller").apply { levels[UpgradeKind.CYLINDERS] = 3 }).getInt("cylinders"))
    }

    /** 全アップグレード最大のカタログ機 JSON を書き出す (ホストの game_balance でネイティブ読み込みを検証) */
    @Test
    fun exportMaxTunedEngines() {
        val dir = File("build/game_tuned").apply { mkdirs() }
        for (d in GameRoster.engines) {
            val id = d.catalogId ?: continue
            val p = EngineProgress(d.key).apply { UpgradeKind.entries.forEach { levels[it] = it.maxLevel } }
            File(dir, "${d.key}.json").writeText(EngineTuner.apply(asset(id), p).toString(2))
        }
    }

    // ------------------------------------------------------------ HUD スクリーンショット

    private class FakeBackend : ConsoleBackend {
        var controls = 0
        var lastLoad = 0f
        var lastThrottle = 0f
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
        ) { controls++; lastLoad = load; lastThrottle = throttle }

        override fun buildCustomEngine(paramsJson: String) = JSONObject(paramsJson).put("family", "reciprocating").toString()
        override fun setView(preset: Int, presetSerial: Int, mode: Int, yawRate: Float, pitchRate: Float, zoom: Float,
                             sectionOffset: Float, sectionAxis: Int, pvChamber: Int) {}
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
    fun hudScreenshotAndEconomyLoop() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val backend = FakeBackend()
        val store = object : GameController.Store {
            var saved: String? = GameState().apply { money = 123_456.0; unlocked += "twin"; unlocked += "rotary" }.toJson()
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
        val m0 = game.state.money
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(3000))
        assertTrue("controls pushed", backend.controls > 10)
        assertTrue("auto dyno load engaged", backend.lastLoad > 0.5f)
        assertTrue("earned money: ${game.state.money} vs $m0", game.state.money > m0)

        val dm = activity.resources.displayMetrics
        root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, dm.widthPixels, dm.heightPixels)
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("build/screenshots/game_hud.png")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        game.stop()
        assertTrue("saved", store.saved!!.contains("\"money\""))
    }
}
