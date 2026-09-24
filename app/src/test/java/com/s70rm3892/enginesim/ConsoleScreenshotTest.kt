package com.s70rm3892.enginesim

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.s70rm3892.enginesim.ui.TelemetryView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** ネイティブ無しでコンソール UI を組み立て、画面全体を PNG に描画する (レイアウト崩れの検出用)。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConsoleScreenshotTest {

    private class FakeBackend : ConsoleBackend {
        var controlsCalls = 0
        var lastTarget = 0f
        var lastMode = -1
        override fun loadEngine(json: String) =
            """{"id":"i4_20","family":"reciprocating","cycle":"otto4","cylinders":4,"displacementL":1.998,
               "boreMm":86.0,"strokeMm":86.0,"rodMm":143.0,"compressionRatio":11.0,"idleRpm":750,"redlineRpm":7000,
               "limiterRpm":7210,"peakTorque":199.0,"gears":6,"propeller":false,"induction":0,"chambers":4,
               "firingOrder":"1-3-4-2","name":"直列4気筒 2.0L NA","description":"180°フラットクランク、点火順序 1-3-4-2。"}"""

        override fun setControls(
            targetRpm: Float, throttle: Float, throttleLink: Boolean, load: Float, sparkOffsetDeg: Float,
            ignition: Boolean, starter: Boolean, gear: Int, timeScale: Float, cylinderCutMask: Long, autoStart: Boolean,
        ) { controlsCalls++; lastTarget = targetRpm }

        override fun setView(
            preset: Int, presetSerial: Int, mode: Int, yawRate: Float, pitchRate: Float, zoom: Float,
            sectionOffset: Float, sectionAxis: Int, pvChamber: Int,
        ) { lastMode = mode }

        override fun setVolume(gain: Float) {}

        override fun getTelemetry(out: FloatArray): Int {
            out.fill(0f)
            out[Tel.RPM] = 4480f; out[Tel.TARGET_RPM] = 4500f; out[Tel.THROTTLE] = 0.62f
            out[Tel.TORQUE] = 168f; out[Tel.POWER_KW] = 78.8f; out[Tel.BMEP] = 10.6f; out[Tel.MAP_KPA] = 92f
            out[Tel.EGT_K] = 1080f; out[Tel.COOLANT_K] = 362f; out[Tel.OIL_K] = 375f; out[Tel.FUEL_KW] = 228f
            out[Tel.BRAKE_KW] = 78.8f; out[Tel.FRICTION_KW] = 12f; out[Tel.COOLANT_KW] = 47f; out[Tel.EXHAUST_KW] = 90f
            out[Tel.EFFICIENCY] = 0.345f; out[Tel.PEAK_BAR] = 64f; out[Tel.OUTPUT_RPM] = 1162f; out[Tel.GEAR] = 3f
            out[Tel.RUNNING] = 1f
            return Tel.COUNT
        }

        override fun getPV(volumeL: FloatArray, pressureBar: FloatArray): Int {
            // オットーサイクル風の合成ループ
            val n = 360
            for (i in 0 until n) {
                val th = i * 4.0 * PI / n
                val v = 0.05 + 0.5 * (1 - cos(th)) / 2
                volumeL[i] = v.toFloat()
                val comp = (0.55 / v).pow(1.33)
                pressureBar[i] = when {
                    i < n / 4 -> (1.0 + 50 * sin(th / 2).pow(8)).toFloat().coerceAtLeast((comp * 3.2).toFloat())
                    i < n / 2 -> 1.05f
                    i < 3 * n / 4 -> 0.95f
                    else -> comp.toFloat()
                }
            }
            return n
        }
    }

    private fun render(qualifiersLandscape: Boolean, file: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val backend = FakeBackend()
        val telemetry = TelemetryView(activity)
        val viewport = object : View(activity) {
            private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(90, 110, 140); textSize = 28f }
            override fun onDraw(c: android.graphics.Canvas) {
                c.drawColor(Color.rgb(24, 28, 34))
                c.drawText("3D VIEWPORT (GLES3)", 24f, height / 2f, p)
            }
        }
        val consoleHost = FrameLayout(activity)
        val screen = ScreenLayout(activity, viewport, telemetry, consoleHost)
        screen.apply(qualifiersLandscape)
        val console = ConsoleUIController(activity, telemetry, EngineCatalog(activity), backend)
        console.buildInto(consoleHost)
        console.loadDefault()
        console.start()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500))

        val dm = activity.resources.displayMetrics
        val w = dm.widthPixels
        val h = dm.heightPixels
        val root = screen.root
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        val out = File("build/screenshots/$file")
        out.parentFile?.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        console.stop()

        assertTrue("controls pushed", backend.controlsCalls > 0)
        assertEquals("target starts at idle", 750f, backend.lastTarget, 0.5f)
        assertEquals("solid mode", 0, backend.lastMode)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h360dp-land-xhdpi")
    fun phoneLandscape() = render(true, "console_phone_land.png")

    @Test
    @Config(sdk = [35], qualifiers = "w1280dp-h800dp-land-mdpi")
    fun tabletLandscape() = render(true, "console_tablet_land.png")

    @Test
    @Config(sdk = [35], qualifiers = "w800dp-h1280dp-port-mdpi")
    fun tabletPortrait() = render(false, "console_tablet_port.png")
}
