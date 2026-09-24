// 実時間物理: 単一ゾーン熱力学 (第一法則 + Wiebe 燃焼) による燃焼室圧力、
// 仮想仕事 T = Σ p dV/dθ によるクランクトルク、クランク回転の運動方程式、
// 過給機/冷却系/ガバナ、タービン/電動機のモデルをまとめる。
#pragma once

#include <vector>

#include "../core/EngineSpec.h"
#include "EngineKinematics.h"
#include "SharedState.h"

namespace es {

class EngineSimulation {
public:
    static constexpr int kTableBins = 360;  // 1 サイクルあたりの記録ビン数

    void init(const EngineSpec& spec, AudioFeed* feed);
    void setControls(const Controls& c) { controls_ = c; }
    const Controls& controls() const { return controls_; }

    // 実時間 dt [s] だけ物理を進める (映像のスローモーションとは独立)
    void step(double dt);
    // 映像用クランク角を dt*timeScale だけ進める
    void advanceVisual(double dt);

    const EngineSpec& spec() const { return spec_; }
    const EngineKinematics& kinematics() const { return kin_; }
    const Telemetry& telemetry() const { return tel_; }

    double visualThetaDeg() const { return visTheta_; }
    float visualSpoolAngle(int i) const { return visSpool_[i]; }  // 0:N1/ファン 1:N2/コア 2:出力軸 3:過給機 4:プロペラ 5:モータ
    // 直近サイクルの記録からの参照 (映像用)
    float chamberPressure(int c, double thetaDeg) const;
    float chamberTemperature(int c, double thetaDeg) const;
    float chamberBurn(int c, double thetaDeg) const;
    // p-V 線図 (燃焼室 c の直近サイクル)
    int pvDiagram(int c, float* volumeL, float* pressureBar, int maxN) const;
    // 応力表示用: ピストン p の軸力 [N] (ガス力 + 往復慣性力) の相対値 0..1
    float pistonLoadFraction(int p, double thetaDeg) const;
    float pistonTempK() const { return pistonTempK_; }
    float headTempK() const { return headTempK_; }
    // 演出用: 燃焼室 c の直近の排気弁開イベント (シミュレーション時刻 [s], 強さ [bar])
    double simTime() const { return simTime_; }
    double lastExhaustTime(int c) const { return c < static_cast<int>(lastEvoTime_.size()) ? lastEvoTime_[c] : -1.0; }
    float lastExhaustAmp(int c) const { return c < static_cast<int>(lastEvoAmp_.size()) ? lastEvoAmp_[c] : 0.0f; }
    bool vehicleActive() const;
    int effectiveGear() const;

private:
    struct ChamberState {
        double p = 1.0e5, T = 320, m = 0, V = 0;
        double qTotal = 0, burnPrev = 0;
        double fresh = 1.0;  // 筒内ガスのうち新気の質量割合
        double lastLocal = 0;
        float pMax = 0;
        bool fueled = false;
        std::vector<float> pTab, tTab, bTab;
    };

    void stepReciprocating(double dt);
    void stepTurbine(double dt);
    void stepElectric(double dt);
    void updateCommon(double dt, double brakeTorque, double fuelW, double indicatedW, double frictionW);
    double stepChamber(int c, double theta0, double theta1, double h, double& qReleased);
    void stirlingPressure();
    double governor(double dt);
    double idleControl(double dt);
    double frictionTorque() const;
    double frictionTorqueBase() const;
    double exhaustBackPressure() const;
    void publishAudio();

    EngineSpec spec_;
    EngineKinematics kin_;
    Controls controls_;
    Telemetry tel_;
    AudioFeed* feed_ = nullptr;

    std::vector<ChamberState> ch_;
    std::vector<double> v0Ref_;  // 各燃焼室の最大容積
    double theta_ = 0;       // 物理クランク角 [deg] (0..cycle)
    double omega_ = 0;       // [rad/s]
    double visTheta_ = 0;
    float visSpool_[6] = {0, 0, 0, 0, 0, 0};

    double throttle_ = 0, govInteg_ = 0, govPrevRpm_ = 0, govRate_ = 0, iscInteg_ = 0.05;
    double map_ = 1.0e5, boost_ = 0, turboFrac_ = 0, prevThrottle_ = 0;
    uint32_t bovCount_ = 0;
    double coolantK_ = 355, oilK_ = 365, egtK_ = 700;
    float pistonTempK_ = 450, headTempK_ = 420;
    bool limiterCut_ = false;
    bool stalled_ = false;
    double torqueEma_ = 0, fuelEma_ = 0, indEma_ = 0, fricEma_ = 0, coolEma_ = 0;
    double cycleEnergy_ = 0;
    double peakTorqueRef_ = 200;

    // タービン
    double ncore_ = 0, nfan_ = 0, egtBump_ = 0;
    // モータ
    double motorTorque_ = 0;
    // スターリング
    double stirlingMass_ = 0, stirlingHotK_ = 900;
    // 車両 / 変速機
    double vehicleCoupling(double omegaE, double h);
    void updateTransmission(double dt);
    double totalRatio(int gear) const;
    double vehV_ = 0, vehDist_ = 0;
    int autoGear_ = 1, lastManualGear_ = 0, lastDriveMode_ = 0;
    double shiftTimer_ = 0, shiftCut_ = 0, slip_ = 0;
    bool lockup_ = false;
    uint32_t shiftCount_ = 0, afterfireCount_ = 0, afterfireSeed_ = 12345;
    double simTime_ = 0;
    std::vector<double> lastEvoTime_;
    std::vector<float> lastEvoAmp_;
    double outputAngleRate_ = 0;
};

}  // namespace es
