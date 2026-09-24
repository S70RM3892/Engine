package com.s70rm3892.enginesim.game

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** BGM: 各場面を鳴らして、音量・クリップ・高域 (12kHz 以上) を確認し、試聴用 WAV を書き出す */
class BgmTest {
    private fun wav(name: String, x: FloatArray, sr: Int) {
        val f = File("build/bgm/$name.wav")
        f.parentFile.mkdirs()
        DataOutputStream(FileOutputStream(f)).use { o ->
            fun i32(v: Int) { o.write(v and 255); o.write((v shr 8) and 255); o.write((v shr 16) and 255); o.write((v shr 24) and 255) }
            fun i16(v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
            o.writeBytes("RIFF"); i32(36 + x.size * 2); o.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2); i32(sr); i32(sr * 4); i16(4); i16(16)
            o.writeBytes("data"); i32(x.size * 2)
            for (v in x) i16((v.coerceIn(-1f, 1f) * 32767).toInt())
        }
    }

    /** 12kHz 以上のエネルギー比 (ハン窓 4096 点の DFT をいくつかの区間で平均) */
    private fun hfRatio(x: FloatArray, sr: Int): Double {
        val n = 4096
        var hi = 0.0
        var tot = 0.0
        var start = 0
        while (start + n * 2 <= x.size && start < x.size / 2 + n * 8) {
            for (k in 1 until n / 2 step 3) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until n) {
                    val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                    val v = x[start + 2 * i] * w
                    re += v * cos(2 * PI * k * i / n)
                    im -= v * sin(2 * PI * k * i / n)
                }
                val p = re * re + im * im
                tot += p
                if (k * sr.toDouble() / n >= 12000) hi += p
            }
            start += n * 8
        }
        return hi / max(1e-12, tot)
    }

    @Test
    fun scenesSoundRightAndStaySoft() {
        val sr = 44100
        val s = BgmSynth(sr)
        s.volume = 0.8f
        val scenes = listOf(
            "night" to { s.mode = BgmSynth.Mode.NIGHT },
            "day" to { s.mode = BgmSynth.Mode.DAY; s.intensity = 0.1f },
            "day_combo" to { s.mode = BgmSynth.Mode.DAY; s.intensity = 1f },
            "day_fever_nitro" to { s.mode = BgmSynth.Mode.DAY; s.intensity = 1f; s.fever = true; s.nitro = true },
            "race" to { s.mode = BgmSynth.Mode.RACE; s.fever = false; s.nitro = false },
        )
        val report = StringBuilder()
        for ((name, setup) in scenes) {
            setup()
            s.renderSeconds(1.5)                 // 曲の切り替え (フェード) を済ませる
            val x = s.renderSeconds(8.0)
            wav(name, x, sr)
            var sum = 0.0
            var peak = 0.0
            for (v in x) { assertTrue(v.isFinite()); sum += v * v; peak = max(peak, abs(v).toDouble()) }
            val rms = sqrt(sum / x.size)
            val hf = if (name == "day_fever_nitro" || name == "night") hfRatio(x, sr) else -1.0
            report.append("%-16s rms %.3f peak %.3f hf %.2e\n".format(name, rms, peak, hf))
            assertTrue("$name audible ($rms)", rms > 0.03)
            assertTrue("$name not clipping ($peak)", peak < 0.99)
            if (hf >= 0) assertTrue("$name little energy above 12 kHz ($hf)", hf < 1e-3)
        }
        File("build/bgm/report.txt").writeText(report.toString())
    }
}
