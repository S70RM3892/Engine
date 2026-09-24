// シミュレーション/UI/オーディオ間で共有する状態。
// オーディオスレッドはロックを取らないため、音響へ渡す値はすべて atomic で公開する。
#pragma once

#include <atomic>
#include <cstdint>

namespace es {

constexpr int kMaxChambers = 64;

struct Controls {
    float targetRpm = 800;
    float throttle = 0.0f;        // 手動スロットル (0..1)
    bool throttleLink = true;     // true: RPM 指令に対し PI ガバナがスロットルを操作
    float load = 0.0f;            // ダイナモ負荷 (0..1, 推定最大トルク比)
    float sparkOffsetDeg = 0.0f;  // 基準点火進角への加算
    bool ignition = true;
    bool starter = false;
    bool autoStart = true;        // エンスト時に自動でスタータを回す
    int gear = 0;                 // 0 = N, 1.. = 前進段
    float timeScale = 1.0f;       // 映像のみのスローモーション倍率
    uint64_t cylinderCutMask = 0; // ビットが立った気筒 (燃焼室番号-1) を失火させる
    int driveMode = 0;            // 0 = ダイナモ (台上), 1 = 車両 MT, 2 = 車両 AT (オートマ)
    float brake = 0.0f;           // 車両ブレーキ (0..1)
    float grade = 0.0f;           // 路面勾配 (0..1 → 0..15%)
};

struct Telemetry {
    float rpm = 0, targetRpm = 0, throttle = 0;
    float torqueNm = 0, torqueInstNm = 0, powerKW = 0, bmepBar = 0;
    float mapKPa = 0, boostBar = 0, turboRpm = 0;
    float egtK = 0, coolantK = 0, oilK = 0;
    float fuelKW = 0, brakeKW = 0, frictionKW = 0, coolantKW = 0, exhaustKW = 0, efficiency = 0;
    float peakPressureBar = 0;
    float outputRpm = 0, outputTorqueNm = 0;
    int gear = 0;
    float n1 = 0, n2 = 0, thrustKN = 0;      // タービン
    float electricalHz = 0, currentA = 0, slip = 0;  // モータ
    float loadNm = 0;
    bool limiter = false, running = false;
    float thetaDeg = 0;
    // 車両
    float speedKmh = 0;
    int effectiveGear = 0;          // 実際に噛み合っている段 (AT では自動選択された段)
    bool shifting = false;
    bool lockup = false;
    float slipRatio = 0;            // トルコン/クラッチ滑り (0..1)
    uint32_t shiftCount = 0;
    uint32_t afterfireCount = 0;    // 燃料カット/リミッタ時の後燃え (演出用)
    float distanceM = 0;
};

// オーディオスレッド向け (lock-free)
struct AudioFeed {
    std::atomic<float> rpm{0};
    std::atomic<float> throttle{0};
    std::atomic<float> mapBar{1};
    std::atomic<float> boostBar{0};
    std::atomic<float> turboFrac{0};
    std::atomic<float> loadFrac{0};
    std::atomic<float> outputRpm{0};
    std::atomic<float> n1Frac{0};
    std::atomic<float> n2Frac{0};
    std::atomic<float> torqueFrac{0};  // モータ/ギア鳴き用 |トルク|/定格
    std::atomic<float> electricalHz{0};
    std::atomic<float> slip{0};
    std::atomic<int> gear{0};
    std::atomic<uint32_t> bovCount{0};
    std::atomic<uint32_t> shiftCount{0};
    std::atomic<uint32_t> afterfireCount{0};
    std::atomic<bool> running{false};
    std::atomic<float> cameraYaw{0};
    std::atomic<float> masterGain{0.8f};
    std::atomic<float> pulseAmp[kMaxChambers];
    std::atomic<float> intakeAmp[kMaxChambers];
    AudioFeed() {
        for (auto& a : pulseAmp) a.store(0.0f);
        for (auto& a : intakeAmp) a.store(0.0f);
    }
};

}  // namespace es
