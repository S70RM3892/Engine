package com.s70rm3892.enginesim.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 手続き生成の BGM (音源ファイル不要)。
 *
 * 適応音楽の 2 つの定番手法を組み合わせる:
 *  - 横の切り替え (horizontal re-sequencing): 夜のガレージ = チルなローファイ、昼のシフト = アップテンポ、ゼロヨン = 速いマイナー。
 *  - 縦の重ね (vertical layering): 昼はコンボが溜まるとアルペジオ、フィーバーでリード、ニトロでフィルターが開いてハイハットが倍に。
 * 高域はローパスで丸め (7〜9kHz 以上はほぼ出さない)、低音 (キック・ベース) を主役にする。
 */
class BgmSynth(private val sr: Int = 44100) {
    enum class Mode(val bpm: Double) { NIGHT(84.0), DAY(126.0), RACE(142.0) }

    @Volatile var mode = Mode.NIGHT
    @Volatile var intensity = 0f      // 0..1 (昼: コンボ)
    @Volatile var fever = false
    @Volatile var nitro = false
    @Volatile var volume = 0.6f

    private var cur = Mode.NIGHT
    private var master = 0f            // フェード用
    private var switching = false
    private var step = 0               // 16 分音符の通し番号
    private var stepPos = 0.0          // 今のステップ内の経過サンプル
    private var t = 0.0

    // 層のゲイン (目標へ滑らかに)
    private var gArp = 0f
    private var gLead = 0f
    private var gNitro = 0f

    // ボイス
    private var kickT = 1e9; private var clapT = 1e9; private var hatT = 1e9; private var hatAmp = 0f
    private var bassF = 55.0; private var bassT = 1e9; private var bassLen = 0.2; private var bassPh = 0.0
    private val padF = DoubleArray(3); private val padPh = DoubleArray(6); private var padEnv = 0f
    private var arpF = 440.0; private var arpT = 1e9; private var arpPh = 0.0; private var arpPan = 0.5f
    private var leadF = 440.0; private var leadT = 1e9; private var leadPh = 0.0; private var leadOn = false
    private var epF = 0.0; private var epT = 1e9; private var epPh = 0.0
    private var kickPh = 0.0

    // フィルター状態 (1 次ローパス)
    private var lpBass = 0f; private var lpPad = 0f; private var lpArp = 0f; private var lpHatA = 0f; private var lpHatB = 0f
    private var lpClap = 0f; private var hpClap = 0f
    private var lpOutL = 0f; private var lpOutR = 0f; private var lpOutL2 = 0f; private var lpOutR2 = 0f
    private var noise = 0x2545F491

    // コード進行 (ルートの MIDI ノートと構成音の半音)
    private data class Chord(val root: Int, val tones: IntArray)
    private val dayProg = listOf(Chord(48, intArrayOf(0, 4, 7)), Chord(43, intArrayOf(0, 4, 7)), Chord(45, intArrayOf(0, 3, 7)), Chord(41, intArrayOf(0, 4, 7)))
    private val nightProg = listOf(Chord(45, intArrayOf(0, 3, 7, 10)), Chord(41, intArrayOf(0, 4, 7, 11)), Chord(48, intArrayOf(0, 4, 7, 11)), Chord(43, intArrayOf(0, 4, 7, 9)))
    private val raceProg = listOf(Chord(45, intArrayOf(0, 3, 7)), Chord(41, intArrayOf(0, 4, 7)), Chord(43, intArrayOf(0, 4, 7)), Chord(40, intArrayOf(0, 3, 7)))
    // リードの旋律 (コード構成音の番号, -1 = 休符)。2 小節で一周
    private val melody = intArrayOf(2, -1, 1, 2, -1, 3, 2, -1, 1, -1, 0, 1, 2, -1, -1, -1, 3, -1, 2, 3, -1, 4, 3, -1, 2, -1, 1, 0, 1, -1, -1, -1)

    private fun hz(midi: Double) = 440.0 * 2.0.pow((midi - 69) / 12.0)
    private fun rnd(): Float {
        noise = noise xor (noise shl 13); noise = noise xor (noise ushr 17); noise = noise xor (noise shl 5)
        return noise / 2147483648f
    }
    private fun lpCoef(hz: Double) = (1 - exp(-2 * PI * hz / sr)).toFloat()
    private fun saw(ph: Double) = (2 * (ph - floor(ph + 0.5))).toFloat()
    private fun sq(ph: Double, w: Double = 0.5) = if (ph - floor(ph) < w) 1f else -1f
    private fun tri(ph: Double): Float { val x = ph - floor(ph); return (if (x < 0.5) 4 * x - 1 else 3 - 4 * x).toFloat() }

    private fun prog() = when (cur) { Mode.DAY -> dayProg; Mode.NIGHT -> nightProg; Mode.RACE -> raceProg }

    /** ステップの頭で各楽器を鳴らす */
    private fun onStep() {
        val s = step % 16
        val bar = (step / 16) % 4
        val ch = prog()[bar]
        val tones = ch.tones
        when (cur) {
            Mode.NIGHT -> {
                // ローファイ: キック 1 と 2.5 拍、スネア (リム) 2・4 拍、ゆったりしたベースとエレピ
                if (s == 0 || s == 10) kickT = 0.0
                if (s == 4 || s == 12) clapT = 0.0
                if (s % 4 == 2) { hatT = 0.0; hatAmp = 0.35f }
                if (s == 0 || s == 7 || s == 11) { bassF = hz(ch.root - 12.0 + if (s == 11) 7 else 0); bassT = 0.0; bassLen = if (s == 0) 0.9 else 0.35 }
                if (s == 0 || s == 3 || s == 6 || s == 10 || s == 14) {
                    epF = hz(ch.root + 12.0 + tones[(s / 3 + bar) % tones.size]); epT = 0.0
                }
            }
            Mode.DAY, Mode.RACE -> {
                // 4 つ打ち + 2・4 拍のクラップ + 裏のハイハット、オクターブで跳ねるベース
                if (s % 4 == 0) kickT = 0.0
                if (s == 4 || s == 12) clapT = 0.0
                if (s % 4 == 2 || (nitro || fever || cur == Mode.RACE) && s % 2 == 1) { hatT = 0.0; hatAmp = if (s % 4 == 2) 1f else 0.5f }
                if (s % 2 == 0) {
                    bassF = hz(ch.root - 12.0 + if (s % 4 == 2) 12 else 0); bassT = 0.0; bassLen = 0.16
                }
                // アルペジオ (コンボ層): 16 分でコード構成音を上下
                val order = intArrayOf(0, 1, 2, 1, 0, 2, 1, 2)
                arpF = hz(ch.root + 24.0 + tones[order[s % 8] % tones.size] + if (s >= 8) 12 else 0)
                arpT = 0.0
                arpPan = if (s % 2 == 0) 0.3f else 0.7f
                // リード (フィーバー層)
                val m = melody[(step % 32)]
                if (m >= 0) {
                    val oct = m / tones.size
                    leadF = hz(ch.root + 24.0 + tones[m % tones.size] + 12 * oct); leadT = 0.0; leadOn = true
                } else leadOn = false
            }
        }
        // パッドはコードが変わる小節頭で更新
        if (s == 0) for (i in 0 until 3) padF[i] = hz(ch.root + 12.0 + tones[i % tones.size])
    }

    /** frames 個のステレオサンプル (L,R 交互) を out に書く */
    fun render(out: FloatArray, frames: Int) {
        val inv = 1.0 / sr
        val stepLen = sr * 60.0 / cur.bpm / 4.0
        val nightLike = cur == Mode.NIGHT
        val padCut = lpCoef(if (nitro) 3000.0 else if (nightLike) 1300.0 else 2000.0)
        val bassCut = lpCoef(if (nitro) 1800.0 else 1100.0)
        val arpCut = lpCoef(if (nitro) 4500.0 else 2800.0)
        val hatCutA = lpCoef(4000.0)
        val hatCutB = lpCoef(7500.0)
        val outCut = lpCoef(8500.0)
        val tgtArp = if (cur != Mode.NIGHT) min(1f, max(0f, (intensity - 0.25f) / 0.4f)) else 0f
        val tgtLead = if (cur != Mode.NIGHT && (fever || cur == Mode.RACE)) 1f else 0f
        val tgtNitro = if (nitro) 1f else 0f
        val layerK = (1 - exp(-inv / 0.6)).toFloat()
        for (i in 0 until frames) {
            // 曲の切り替え: フェードアウト → 小節頭から新しい曲 → フェードイン
            if (mode != cur && !switching) switching = true
            if (switching) {
                master -= (inv / 0.5).toFloat()
                if (master <= 0f) { master = 0f; cur = mode; switching = false; step = 0; stepPos = 0.0; onStep() }
            } else master = min(1f, master + (inv / 0.8).toFloat())

            stepPos += 1.0
            if (stepPos >= stepLen) { stepPos -= stepLen; step++; onStep() }
            gArp += (tgtArp - gArp) * layerK
            gLead += (tgtLead - gLead) * layerK
            gNitro += (tgtNitro - gNitro) * layerK

            var l = 0f
            var r = 0f
            // キック: 周波数が 125→45Hz に落ちるサイン
            if (kickT < 0.45) {
                val f = 45.0 + 80.0 * exp(-kickT * 28)
                kickPh += f * inv
                val v = (sin(2 * PI * kickPh) * exp(-kickT * (if (nightLike) 7.0 else 9.0))).toFloat() * (if (nightLike) 0.6f else 0.75f)
                l += v; r += v
                kickT += inv
            }
            // クラップ/リム: 帯域を絞ったノイズ
            if (clapT < 0.25) {
                val n = rnd()
                lpClap += (n - lpClap) * lpCoef(2200.0)
                hpClap += (lpClap - hpClap) * lpCoef(700.0)
                val bp = lpClap - hpClap
                val env = (exp(-clapT * 22) + 0.6 * exp(-(clapT - 0.012).let { if (it < 0) 1e9 else it } * 30)).toFloat()
                val v = bp * env * (if (nightLike) 0.9f else 1.6f)
                l += v; r += v
                clapT += inv
            }
            // ハイハット: 4〜7.5kHz 付近に絞ったノイズ (耳障りな高域は出さない)
            if (hatT < 0.08) {
                val n = rnd()
                lpHatA += (n - lpHatA) * hatCutA
                lpHatB += (n - lpHatB) * hatCutB
                val v = (lpHatB - lpHatA) * exp(-hatT * 55).toFloat() * hatAmp * (if (nightLike) 0.25f else 0.4f)
                l += v * 0.8f; r += v
                hatT += inv
            }
            // ベース: ノコギリ + 矩形をローパス
            if (bassT < bassLen + 0.05) {
                bassPh += bassF * inv
                val raw = 0.6f * saw(bassPh) + 0.4f * sq(bassPh)
                lpBass += (raw - lpBass) * bassCut
                val env = (if (bassT < bassLen) 1.0 else exp(-(bassT - bassLen) * 60)) * (1 - exp(-bassT * 400))
                val v = lpBass * env.toFloat() * (if (nightLike) 0.45f else 0.5f)
                l += v; r += v
                bassT += inv
            }
            // パッド: 3 音 × デチューン 2 本のノコギリ
            padEnv = min(1f, padEnv + (inv / 0.4).toFloat())
            var pad = 0f
            for (k in 0 until 3) {
                if (padF[k] <= 0) continue
                padPh[2 * k] += padF[k] * 1.003 * inv
                padPh[2 * k + 1] += padF[k] * 0.997 * inv
                pad += saw(padPh[2 * k]) + saw(padPh[2 * k + 1])
            }
            lpPad += (pad - lpPad) * padCut
            val padV = lpPad * padEnv * (if (nightLike) 0.13f else 0.10f) * (1f + 0.5f * gNitro)
            l += padV * 1.1f; r += padV * 0.9f
            // エレピ (夜): サイン + ベル成分
            if (nightLike && epT < 1.2) {
                epPh += epF * inv
                val v = ((sin(2 * PI * epPh) + 0.25 * sin(4 * PI * epPh) * exp(-epT * 6)) * exp(-epT * 2.6)).toFloat() * 0.26f
                l += v * 0.8f; r += v
                epT += inv
            }
            // アルペジオ (コンボ層)
            if (gArp > 0.01f && arpT < 0.2) {
                arpPh += arpF * inv
                val raw = sq(arpPh, 0.3)
                lpArp += (raw - lpArp) * arpCut
                val v = lpArp * exp(-arpT * 16).toFloat() * 0.15f * gArp
                l += v * (1 - arpPan) * 2; r += v * arpPan * 2
                arpT += inv
            }
            // リード (フィーバー層): 三角波 + ビブラート
            if (gLead > 0.01f && leadOn) {
                leadPh += leadF * (1 + 0.006 * sin(2 * PI * 5.5 * t)) * inv
                val v = tri(leadPh) * min(1.0, leadT * 60).toFloat() * exp(-leadT * 1.5).toFloat() * 0.22f * gLead
                l += v; r += v
                leadT += inv
            }
            // 全体: 柔らかいクリップ + 2 段ローパス (8.5kHz)
            var ol = tanh((l * 0.8f).toDouble()).toFloat()
            var or = tanh((r * 0.8f).toDouble()).toFloat()
            lpOutL += (ol - lpOutL) * outCut; lpOutL2 += (lpOutL - lpOutL2) * outCut
            lpOutR += (or - lpOutR) * outCut; lpOutR2 += (lpOutR - lpOutR2) * outCut
            ol = lpOutL2 * volume * master
            or = lpOutR2 * volume * master
            out[2 * i] = if (ol.isFinite()) ol else 0f
            out[2 * i + 1] = if (or.isFinite()) or else 0f
            t += inv
        }
    }

    /** テスト用: 状態をまとめて進める */
    fun renderSeconds(sec: Double): FloatArray {
        val n = (sec * sr).toInt()
        val out = FloatArray(n * 2)
        var i = 0
        val block = 512
        val buf = FloatArray(block * 2)
        while (i < n) {
            val m = min(block, n - i)
            render(buf, m)
            System.arraycopy(buf, 0, out, i * 2, m * 2)
            i += m
        }
        return out
    }
}

/** BGM を AudioTrack で流す (専用スレッド)。エンジン音 (Oboe) とは別ストリーム */
class BgmPlayer {
    val synth = BgmSynth(44100)
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "Bgm").apply { priority = Thread.MAX_PRIORITY - 1; start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    private fun loop() {
        val sr = 44100
        val min = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(max(min, 4096 * 8))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        val frames = 1024
        val buf = FloatArray(frames * 2)
        runCatching {
            track.play()
            while (running) {
                synth.render(buf, frames)
                track.write(buf, 0, buf.size, AudioTrack.WRITE_BLOCKING)
            }
            track.stop()
        }
        track.release()
    }
}
