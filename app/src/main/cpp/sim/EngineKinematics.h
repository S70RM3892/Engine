// クランク角 θ に対する厳密な幾何解によるキネマティクス。
// 物理エンジンの拘束ソルバは使わず、各リンクを閉形式で解くのでジッターは原理的に生じない。
#pragma once

#include <vector>

#include "../core/EngineSpec.h"
#include "../core/MathUtil.h"

namespace es {

struct PistonPose {
    float s = 0;        // クランク中心→ピストンピン距離 (シリンダ軸方向)
    Vec3 bigEnd;        // ロッド大端 (クランクピン または ナックルピン)
    Vec3 wrist;         // ピストンピン
    Vec3 axis;          // シリンダ軸単位ベクトル
    Vec3 rodDir;        // 大端→小端 単位ベクトル
};

struct KinState {
    double thetaDeg = 0;                 // 主クランク角 [0, cycle)
    std::vector<float> crankAngleRad;    // 各クランク軸の回転角
    std::vector<PistonPose> pistons;
    std::vector<float> chamberVolume;
    std::vector<float> liftIn, liftEx;   // 各燃焼室の吸気/排気バルブリフト [m]
    std::vector<float> slideValve;       // 蒸気機関スライドバルブ変位 [m]
    std::vector<Vec3> rotorCenter;       // ヴァンケル・ローター中心
    std::vector<float> rotorAngleRad;
    float eccentricAngleRad = 0;
};

class EngineKinematics {
public:
    // spec のピストン sMin/sMax・燃焼室面積/隙間容積・点火角を数値的に確定させる
    static void prepare(EngineSpec& spec);

    void init(const EngineSpec* spec);
    const EngineSpec* spec() const { return spec_; }

    void evaluate(double thetaDeg, KinState& out) const;

    // 2D (y,z) 平面でのピストン幾何
    float pistonS(int p, double thetaDeg) const;
    double chamberVolume(int c, double thetaDeg) const;
    double chamberDVdTheta(int c, double thetaDeg) const;  // [m^3/rad]
    float valveLift(int c, bool intake, double thetaDeg) const;
    double crankAngleDeg(int crank, double thetaDeg) const;

private:
    struct Geo2 { float s; float by, bz; float wy, wz; float dy, dz; };
    Geo2 solvePiston(int p, double thetaDeg) const;
    static Geo2 solvePistonImpl(const EngineSpec& spec, int p, double thetaDeg);
    static double chamberVolumeImpl(const EngineSpec& spec, int c, double thetaDeg);
    const EngineSpec* spec_ = nullptr;
};

}  // namespace es
