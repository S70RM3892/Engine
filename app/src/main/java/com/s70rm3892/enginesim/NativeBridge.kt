package com.s70rm3892.enginesim

/** コンソールが使うエンジン側 API (ネイティブ実装とテスト用フェイクを差し替え可能にする)。 */
interface ConsoleBackend {
    /** エンジン定義 JSON を読み込み、要約 JSON (または {"error":...}) を返す。適用は次フレーム。 */
    fun loadEngine(json: String): String

    fun setControls(
        targetRpm: Float, throttle: Float, throttleLink: Boolean, load: Float, sparkOffsetDeg: Float,
        ignition: Boolean, starter: Boolean, gear: Int, timeScale: Float, cylinderCutMask: Long, autoStart: Boolean,
        driveMode: Int, brake: Float, grade: Float,
    )

    /** カスタムエンジンのパラメータ JSON からエンジン定義 JSON を生成 (失敗時 {"error":...})。 */
    fun buildCustomEngine(paramsJson: String): String

    fun setView(
        preset: Int, presetSerial: Int, mode: Int, yawRate: Float, pitchRate: Float, zoom: Float,
        sectionOffset: Float, sectionAxis: Int, pvChamber: Int,
    )

    fun setVolume(gain: Float)

    /** [Tel] の並びで値を詰める。戻り値は書き込んだ要素数。 */
    fun getTelemetry(out: FloatArray): Int

    fun getPV(volumeL: FloatArray, pressureBar: FloatArray): Int
}

/**
 * ネイティブのエンジン/描画はプロセスに 1 つだけなので、最後に読み込んだ画面 (シミュレータ or ゲーム) を記録する。
 * 画面復帰時に自分が所有者でなければエンジンを読み込み直す。
 */
object EngineOwner {
    @JvmStatic var token: Any? = null
}

/** ネイティブエンジン (C++20) への JNI 境界。 */
object NativeBridge : ConsoleBackend {
    init {
        System.loadLibrary("enginesim")
    }

    external override fun loadEngine(json: String): String

    external override fun setControls(
        targetRpm: Float, throttle: Float, throttleLink: Boolean, load: Float, sparkOffsetDeg: Float,
        ignition: Boolean, starter: Boolean, gear: Int, timeScale: Float, cylinderCutMask: Long, autoStart: Boolean,
        driveMode: Int, brake: Float, grade: Float,
    )

    external override fun buildCustomEngine(paramsJson: String): String

    external override fun setView(
        preset: Int, presetSerial: Int, mode: Int, yawRate: Float, pitchRate: Float, zoom: Float,
        sectionOffset: Float, sectionAxis: Int, pvChamber: Int,
    )

    external override fun setVolume(gain: Float)

    external override fun getTelemetry(out: FloatArray): Int

    external override fun getPV(volumeL: FloatArray, pressureBar: FloatArray): Int

    /** Vulkan が使える端末か (インスタンスとグラフィックスキューの有無) */
    external fun vkSupported(): Boolean
    /** 戻り値: サーフェス世代 (>0)、失敗時 0 */
    external fun vkSurfaceCreated(surface: android.view.Surface): Int
    external fun vkSurfaceDestroyed(gen: Int)
    external fun backendName(): String
    /** ゲーム演出の強さ (0 = シミュレータ) */
    external fun setEffects(level: Float)
    /** カメラ自動周回 [rad/s] */
    external fun setAutoOrbit(rate: Float)

    external fun surfaceCreated()
    /** gen: 0 = GLES, >0 = Vulkan サーフェス世代 */
    external fun surfaceChanged(width: Int, height: Int, gen: Int)
    external fun drawFrame(dtSec: Float, gen: Int)

    external fun startAudio()
    external fun stopAudio()
}

/** ネイティブ getTelemetry の配列インデックス。 */
object Tel {
    const val RPM = 0
    const val TARGET_RPM = 1
    const val THROTTLE = 2
    const val TORQUE = 3
    const val TORQUE_INST = 4
    const val POWER_KW = 5
    const val BMEP = 6
    const val MAP_KPA = 7
    const val BOOST_BAR = 8
    const val TURBO_RPM = 9
    const val EGT_K = 10
    const val COOLANT_K = 11
    const val OIL_K = 12
    const val FUEL_KW = 13
    const val BRAKE_KW = 14
    const val FRICTION_KW = 15
    const val COOLANT_KW = 16
    const val EXHAUST_KW = 17
    const val EFFICIENCY = 18
    const val PEAK_BAR = 19
    const val OUTPUT_RPM = 20
    const val OUTPUT_TORQUE = 21
    const val GEAR = 22
    const val N1 = 23
    const val N2 = 24
    const val THRUST_KN = 25
    const val ELEC_HZ = 26
    const val CURRENT_A = 27
    const val SLIP = 28
    const val LOAD_NM = 29
    const val LIMITER = 30
    const val RUNNING = 31
    const val THETA = 32
    const val RENDER_MODE = 33
    const val SPEED_KMH = 34
    const val EFF_GEAR = 35
    const val SHIFTING = 36
    const val LOCKUP = 37
    const val SLIP_RATIO = 38
    const val SHIFT_COUNT = 39
    const val AFTERFIRE_COUNT = 40
    const val DISTANCE_M = 41
    const val COUNT = 42
}
