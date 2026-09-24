// Kotlin ⇔ ネイティブの境界。
// スレッド: UI スレッド (制御/テレメトリ取得), GL スレッド (物理ステップ + 描画), オーディオスレッド (DSP)。
// UI と GL の共有値は mutex で保護し、オーディオへは AudioFeed の atomic で渡す (ロックフリー)。
#include <android/log.h>
#include <android/native_window_jni.h>
#include <jni.h>

#include <memory>
#include <mutex>
#include <string>

#include "../audio/AudioEngine.h"
#include "../core/CustomEngine.h"
#include "../audio/EngineAcousticsDSP.h"
#include "../render/Renderer.h"
#include "../sim/EngineSimulation.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EngineSim", __VA_ARGS__)

namespace {

using namespace es;

struct EngineApp {
    std::mutex m;
    AudioFeed feed;
    EngineAcousticsDSP dsp;
    std::unique_ptr<AudioEngine> audio;
    // GL スレッド専有
    EngineSimulation sim;
    Renderer renderer;
    bool simReady = false;
    // 共有 (m で保護)
    std::unique_ptr<EngineSpec> pendingSpec;
    Controls controls;
    ViewInput view;
    Telemetry tel;
    float pvV[EngineSimulation::kTableBins] = {};
    float pvP[EngineSimulation::kTableBins] = {};
    int pvN = 0;
    int pvChamber = 0;
    int effectiveMode = 0;
    std::string specName;
    ANativeWindow* window = nullptr;  // Vulkan サーフェス
    float effects = 0;
    // 描画資源 (renderer/sim) の排他。シミュレータとゲームの 2 画面が切り替わる瞬間に、
    // 新しい画面の描画スレッドと古い画面の破棄処理が重なっても壊れないようにする。
    std::mutex renderMutex;
    int vkGen = 0;  // 現在の Vulkan サーフェスの世代 (古い画面からの破棄/描画を無視する)
};

EngineApp* g_app = nullptr;

EngineApp& app() {
    if (!g_app) g_app = new EngineApp();
    return *g_app;
}

std::string jsonEscape(const std::string& s) {
    std::string o;
    for (char c : s) {
        if (c == '"' || c == '\\') { o += '\\'; o += c; }
        else if (c == '\n') o += "\\n";
        else o += c;
    }
    return o;
}

std::string specSummary(const EngineSpec& s) {
    char buf[1024];
    std::string fo;
    for (size_t i = 0; i < s.firingOrder.size(); ++i) fo += (i ? "-" : "") + std::to_string(s.firingOrder[i]);
    std::snprintf(buf, sizeof buf,
                  "{\"id\":\"%s\",\"family\":\"%s\",\"cycle\":\"%s\",\"cylinders\":%d,\"displacementL\":%.3f,"
                  "\"boreMm\":%.1f,\"strokeMm\":%.1f,\"rodMm\":%.1f,\"compressionRatio\":%.2f,\"idleRpm\":%.0f,"
                  "\"redlineRpm\":%.0f,\"limiterRpm\":%.0f,\"peakTorque\":%.1f,\"gears\":%d,\"propeller\":%s,"
                  "\"induction\":%d,\"chambers\":%d,\"firingOrder\":\"%s\",",
                  jsonEscape(s.id).c_str(), familyName(s.family), cycleName(s.cycle), s.cylinders, s.displacement * 1000.0f,
                  s.bore * 1000.0f, s.stroke * 1000.0f, s.rodLength * 1000.0f, s.compressionRatio, s.idleRpm, s.redlineRpm,
                  s.limiterRpm, s.peakTorqueEstimate, static_cast<int>(s.drivetrain.gears.size()),
                  s.drivetrain.propeller ? "true" : "false", static_cast<int>(s.induction.type), static_cast<int>(s.chambers.size()),
                  fo.c_str());
    return std::string(buf) + "\"name\":\"" + jsonEscape(s.name) + "\",\"description\":\"" + jsonEscape(s.description) + "\"}";
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_s70rm3892_enginesim_NativeBridge_loadEngine(JNIEnv* env, jobject, jstring json) {
    const char* c = env->GetStringUTFChars(json, nullptr);
    std::string text(c);
    env->ReleaseStringUTFChars(json, c);
    auto spec = std::make_unique<EngineSpec>();
    std::string err;
    if (!loadEngineSpecFromText(text, *spec, err)) {
        LOGE("engine load failed: %s", err.c_str());
        return env->NewStringUTF(("{\"error\":\"" + jsonEscape(err) + "\"}").c_str());
    }
    // 推定最大トルクは sim.init で決まるので一時的に計算して要約に含める
    EngineSimulation probe;
    probe.init(*spec, nullptr);
    std::string summary = specSummary(probe.spec());
    EngineApp& a = app();
    {
        std::lock_guard<std::mutex> g(a.m);
        a.pendingSpec = std::move(spec);
    }
    return env->NewStringUTF(summary.c_str());
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_setControls(JNIEnv*, jobject, jfloat targetRpm, jfloat throttle,
                                                                             jboolean link, jfloat load, jfloat spark,
                                                                             jboolean ignition, jboolean starter, jint gear,
                                                                             jfloat timeScale, jlong cutMask, jboolean autoStart,
                                                                             jint driveMode, jfloat brake, jfloat grade) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> g(a.m);
    Controls& c = a.controls;
    c.targetRpm = targetRpm;
    c.throttle = throttle;
    c.throttleLink = link;
    c.load = load;
    c.sparkOffsetDeg = spark;
    c.ignition = ignition;
    c.starter = starter;
    c.gear = gear;
    c.timeScale = timeScale;
    c.cylinderCutMask = static_cast<uint64_t>(cutMask);
    c.autoStart = autoStart;
    c.driveMode = driveMode;
    c.brake = brake;
    c.grade = grade;
}

JNIEXPORT jstring JNICALL Java_com_s70rm3892_enginesim_NativeBridge_buildCustomEngine(JNIEnv* env, jobject, jstring params) {
    const char* c = env->GetStringUTFChars(params, nullptr);
    std::string text(c);
    env->ReleaseStringUTFChars(params, c);
    JsonValue j;
    std::string err, json;
    CustomEngineParams p;
    if (!parseJson(text, j, err) || !parseCustomParams(j, p, err) || !buildCustomEngineJson(p, json, err))
        return env->NewStringUTF(("{\"error\":\"" + jsonEscape(err) + "\"}").c_str());
    return env->NewStringUTF(json.c_str());
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_setView(JNIEnv*, jobject, jint preset, jint serial, jint mode,
                                                                         jfloat yawRate, jfloat pitchRate, jfloat zoom,
                                                                         jfloat sectionOffset, jint sectionAxis, jint pvChamber) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> g(a.m);
    a.view.preset = preset;
    a.view.presetSerial = serial;
    a.view.mode = mode;
    a.view.yawRate = yawRate;
    a.view.pitchRate = pitchRate;
    a.view.zoom = zoom;
    a.view.sectionOffset = sectionOffset;
    a.view.sectionAxis = sectionAxis;
    a.pvChamber = pvChamber;
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_setVolume(JNIEnv*, jobject, jfloat v) {
    app().feed.masterGain.store(v);
}

// テレメトリを配列に詰める (並びは Kotlin 側 Telemetry と一致させる)
JNIEXPORT jint JNICALL Java_com_s70rm3892_enginesim_NativeBridge_getTelemetry(JNIEnv* env, jobject, jfloatArray out) {
    EngineApp& a = app();
    Telemetry t;
    int mode;
    {
        std::lock_guard<std::mutex> g(a.m);
        t = a.tel;
        mode = a.effectiveMode;
    }
    float v[48] = {t.rpm, t.targetRpm, t.throttle, t.torqueNm, t.torqueInstNm, t.powerKW, t.bmepBar, t.mapKPa,
                   t.boostBar, t.turboRpm, t.egtK, t.coolantK, t.oilK, t.fuelKW, t.brakeKW, t.frictionKW,
                   t.coolantKW, t.exhaustKW, t.efficiency, t.peakPressureBar, t.outputRpm, t.outputTorqueNm,
                   static_cast<float>(t.gear), t.n1, t.n2, t.thrustKN, t.electricalHz, t.currentA,
                   t.slip, t.loadNm, t.limiter ? 1.0f : 0.0f, t.running ? 1.0f : 0.0f, t.thetaDeg,
                   static_cast<float>(mode), t.speedKmh, static_cast<float>(t.effectiveGear), t.shifting ? 1.0f : 0.0f,
                   t.lockup ? 1.0f : 0.0f, t.slipRatio, static_cast<float>(t.shiftCount), static_cast<float>(t.afterfireCount),
                   t.distanceM};
    jsize n = std::min<jsize>(env->GetArrayLength(out), 42);
    env->SetFloatArrayRegion(out, 0, n, v);
    return n;
}

JNIEXPORT jint JNICALL Java_com_s70rm3892_enginesim_NativeBridge_getPV(JNIEnv* env, jobject, jfloatArray vol, jfloatArray pres) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> g(a.m);
    jsize n = std::min<jsize>(std::min(env->GetArrayLength(vol), env->GetArrayLength(pres)), a.pvN);
    env->SetFloatArrayRegion(vol, 0, n, a.pvV);
    env->SetFloatArrayRegion(pres, 0, n, a.pvP);
    return n;
}

JNIEXPORT jboolean JNICALL Java_com_s70rm3892_enginesim_NativeBridge_vkSupported(JNIEnv*, jobject) {
    static int cached = -1;
    if (cached < 0) cached = VkBackend::probe() ? 1 : 0;
    return cached == 1;
}

// Vulkan: 描画スレッドから呼ぶ
// 戻り値: サーフェス世代 (>0)。失敗時 0。
JNIEXPORT jint JNICALL Java_com_s70rm3892_enginesim_NativeBridge_vkSurfaceCreated(JNIEnv* env, jobject, jobject surface) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> rg(a.renderMutex);
    a.renderer.releaseVulkan();
    if (a.window) ANativeWindow_release(a.window);
    a.window = ANativeWindow_fromSurface(env, surface);
    ++a.vkGen;
    if (!a.window) return 0;
    bool ok = a.renderer.initVulkan(a.window);
    if (!ok) {
        LOGE("Vulkan init failed");
        ANativeWindow_release(a.window);
        a.window = nullptr;
        return 0;
    }
    return a.vkGen;
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_vkSurfaceDestroyed(JNIEnv*, jobject, jint gen) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> rg(a.renderMutex);
    if (gen != a.vkGen) return;  // すでに別の画面がサーフェスを持っている
    a.renderer.releaseVulkan();
    if (a.window) ANativeWindow_release(a.window);
    a.window = nullptr;
}

JNIEXPORT jstring JNICALL Java_com_s70rm3892_enginesim_NativeBridge_backendName(JNIEnv* env, jobject) {
    return env->NewStringUTF(app().renderer.backendName().c_str());
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_setEffects(JNIEnv*, jobject, jfloat level) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> g(a.m);
    a.effects = level;
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_setAutoOrbit(JNIEnv*, jobject, jfloat rate) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> g(a.m);
    a.view.autoOrbit = rate;
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_surfaceCreated(JNIEnv*, jobject) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> rg(a.renderMutex);
    a.renderer.releaseGL();  // 旧コンテキストの名前は無効
    if (!a.renderer.initGL()) LOGE("GLES renderer init failed");
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_surfaceChanged(JNIEnv*, jobject, jint w, jint h, jint gen) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> rg(a.renderMutex);
    if (gen != 0 && gen != a.vkGen) return;
    a.renderer.resize(w, h);
}

// gen: 0 = GLES, >0 = Vulkan サーフェス世代 (古い世代の描画スレッドは何もしない)
JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_drawFrame(JNIEnv*, jobject, jfloat dt, jint gen) {
    EngineApp& a = app();
    std::lock_guard<std::mutex> rg(a.renderMutex);
    if (gen != 0 && gen != a.vkGen) return;
    Controls controls;
    ViewInput view;
    int pvChamber;
    float effects;
    std::unique_ptr<EngineSpec> spec;
    {
        std::lock_guard<std::mutex> g(a.m);
        spec = std::move(a.pendingSpec);
        controls = a.controls;
        view = a.view;
        pvChamber = a.pvChamber;
        effects = a.effects;
    }
    a.renderer.setEffects(effects);
    if (spec) {
        a.sim.init(*spec, &a.feed);
        a.renderer.setEngine(a.sim.spec());
        a.dsp.setConfig(AcousticConfig::fromSpec(a.sim.spec()));
        a.simReady = true;
    }
    if (!a.simReady) return;
    a.sim.setControls(controls);
    a.sim.step(dt);
    a.sim.advanceVisual(dt);
    a.feed.cameraYaw.store(a.renderer.cameraYawDeg());
    a.renderer.render(a.sim, dt, view);
    {
        std::lock_guard<std::mutex> g(a.m);
        a.tel = a.sim.telemetry();
        a.effectiveMode = a.renderer.effectiveMode();
        int c = std::clamp(pvChamber, 0, std::max(0, static_cast<int>(a.sim.spec().chambers.size()) - 1));
        a.pvN = a.sim.spec().chambers.empty() ? 0 : a.sim.pvDiagram(c, a.pvV, a.pvP, EngineSimulation::kTableBins);
    }
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_startAudio(JNIEnv*, jobject) {
    EngineApp& a = app();
    if (!a.audio) a.audio = std::make_unique<AudioEngine>(&a.dsp, &a.feed);
    if (!a.audio->start()) LOGE("audio start failed");
}

JNIEXPORT void JNICALL Java_com_s70rm3892_enginesim_NativeBridge_stopAudio(JNIEnv*, jobject) {
    EngineApp& a = app();
    if (a.audio) a.audio->stop();
}

}  // extern "C"
