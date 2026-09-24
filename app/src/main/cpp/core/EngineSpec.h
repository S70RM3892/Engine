// エンジン定義 (データ駆動)。JSON の高レベル "layout" 記述を、
// 汎用キネマティクスが扱える「クランク/スロー/ピストン/燃焼室」へ展開する。
//
// 座標系: クランク軸 = +X (前→後), 上 = +Y。シリンダ軸は YZ 平面内の角度 axisDeg
// (+Y から +Z へ向かって測る) で表す。長さは m, 角度は deg, 圧力は Pa。
#pragma once

#include <string>
#include <vector>

#include "Json.h"

namespace es {

enum class Family { Reciprocating, Wankel, Turbine, Electric };
enum class Cycle { Otto4, Otto2, Diesel4, Diesel2, Steam, Stirling, Wankel4, None };
enum class RodType { Plain, Master, Articulated };
enum class ChamberKind { Combustion, SteamHead, SteamCrank, StirlingHot, StirlingCold };
enum class InductionType { Natural, Turbo, Roots };
enum class TurbineKind { Turbojet, Turbofan, Turboshaft };
enum class MotorKind { Induction, PMSM };

struct CrankDef {
    float y = 0, z = 0;      // YZ 平面内の軸位置
    float dir = 1;           // 回転方向 (+1 / -1)
    float phaseDeg = 0;      // 主クランク角に対する位相
    float radius = 0;        // クランク半径 (= stroke/2)
};

struct ThrowDef {
    int crank = 0;
    float x = 0;             // クランク軸方向位置
    float pinAngleDeg = 0;   // クランクピン角 (クランク座標系)
};

struct PistonDef {
    int throwIdx = 0;
    float axisDeg = 0;       // シリンダ軸方向
    float xOffset = 0;       // 同一ピンを共有する V 型ロッドの軸方向ずれ
    RodType rod = RodType::Plain;
    int master = -1;         // Articulated のとき親マスターロッドのピストン番号
    float knuckleRadius = 0; // ナックルピン半径 (マスターロッド大端中心から)
    float knuckleAngleDeg = 0;
    float rodLength = 0;
    float bore = 0;
    int bank = 0;
    bool crosshead = false;  // 蒸気機関: ピストンロッド+クロスヘッド
    // 導出値 (展開後に数値的に求める)
    float sMin = 0, sMax = 0;  // クランク中心からピストンピンまでの距離の最小/最大
};

struct ChamberDef {
    ChamberKind kind = ChamberKind::Combustion;
    std::vector<int> pistons;  // 室容積を変化させるピストン (対向ピストンは 2 本)
    std::vector<float> signs;  // 各ピストンの寄与符号 (+1: s 増で容積減, -1: s 増で容積増)
    float area = 0;            // 有効受圧面積
    float clearance = 0;       // 隙間容積
    float firingDeg = 0;       // 燃焼上死点 (主クランク角, サイクル内)
    int number = 1;            // 表示用シリンダ番号 (1 始まり)
    int rotor = -1;            // ヴァンケル: ローター番号
    float wankelPhaseDeg = 0;  // ヴァンケル: 最小容積となる偏心軸角
    int bank = 0;
};

struct BankDef {
    float axisDeg = 0;
    int crank = 0;
    std::vector<int> pistons;
};

struct ValveTrainDef {
    int intakePerCyl = 2, exhaustPerCyl = 2;
    // 4 ストローク基準: 燃焼TDC=0 から測ったサイクル角 [0,720) のバルブイベント
    float ivoDeg = 350, ivcDeg = 590, evoDeg = 130, evcDeg = 370;
    float liftIn = 0.010f, liftEx = 0.009f;
    bool overhead = true;      // true: DOHC, false: OHV/プッシュロッド (カム描画を簡略化)
    // 2 ストローク: 排気/掃気ポートが開く角度 (TDC 後, BDC 対称に閉じる)
    float exhaustPortDeg = 95, transferPortDeg = 120;
};

struct WankelDef {
    int rotors = 2;
    float e = 0.015f;          // 偏心量
    float R = 0.105f;          // 創成半径
    float width = 0.080f;
    std::vector<float> rotorPhaseDeg;
};

struct TurbineDef {
    TurbineKind kind = TurbineKind::Turbojet;
    int fanBlades = 0, compressorStages = 8, turbineStages = 2, compressorBlades = 30;
    float n1MaxRpm = 10000, n2MaxRpm = 15000;
    float maxThrustN = 50000, maxShaftPowerW = 0;
    float spoolTimeConst = 2.5f;
    float length = 2.5f, radius = 0.5f;
    float outputReduction = 1.0f;  // ターボシャフトの出力減速比
};

struct MotorDef {
    MotorKind kind = MotorKind::PMSM;
    int polePairs = 4;
    int statorSlots = 48;
    float pwmHz = 8000;
    float ratedTorque = 300, ratedPowerW = 150000, maxRpm = 16000;
    float slipRated = 0.03f;
    float radius = 0.12f, length = 0.2f;
};

struct InductionDef {
    InductionType type = InductionType::Natural;
    float maxBoostBar = 0;     // ゲージ圧
    int compressorBlades = 11;
    float turboMaxRpm = 180000;
    float spoolTimeConst = 0.8f;
    float rootsDriveRatio = 2.0f;
    int rootsLobes = 3;
};

struct ExhaustDef {
    std::vector<float> runnerLengths;  // 各気筒ランナー長 (等長/不等長)
    float collectorLength = 0.8f;
    float tailpipeLength = 1.5f;
    float mufflerVolume = 0.02f;       // m^3
    float gasSpeedOfSound = 520.0f;    // 高温排気中の音速近似
};

struct DrivetrainDef {
    std::vector<float> gears{3.6f, 2.1f, 1.45f, 1.1f, 0.87f, 0.72f};
    float finalDrive = 3.9f;
    bool propeller = false;
    float propReduction = 1.0f;
    int propBlades = 3;
    float propDiameter = 3.0f;
    float propPowerCoeff = 1.0f;  // プロペラ吸収トルク係数 (T = k*(n/nmax)^2*Tmax)
};

struct EngineSpec {
    std::string id, name, category, description;
    Family family = Family::Reciprocating;
    Cycle cycle = Cycle::Otto4;
    float idleRpm = 800, redlineRpm = 7000, limiterRpm = 7200, maxRpm = 8000;
    float bore = 0.086f, stroke = 0.086f, rodLength = 0.143f, compressionRatio = 10.5f;
    float inertia = 0.20f;         // クランク+フライホイール慣性 [kg m^2]
    float reciprocatingMass = 0.5f;// ピストン系往復質量 [kg] (慣性力/応力表示)
    float sparkAdvanceDeg = 20;    // 基準点火進角 (BTDC)
    float burnDurationDeg = 55;
    float boilerPressure = 1.2e6f; // 蒸気: ゲージではなく絶対圧
    float cutoff = 0.35f;          // 蒸気: 締切比
    float stirlingHotK = 900, stirlingColdK = 330, stirlingMeanPressure = 3e6f;
    float peakTorqueEstimate = 200;  // 負荷スライダのスケール (初期化時に上書き)
    int cylinders = 0;
    float displacement = 0;          // m^3

    std::vector<CrankDef> cranks;
    std::vector<ThrowDef> throws;
    std::vector<PistonDef> pistons;
    std::vector<ChamberDef> chambers;
    std::vector<BankDef> banks;
    std::vector<int> firingOrder;    // 1 始まりのシリンダ番号列

    ValveTrainDef valves;
    WankelDef wankel;
    TurbineDef turbine;
    MotorDef motor;
    InductionDef induction;
    ExhaustDef exhaust;
    DrivetrainDef drivetrain;

    float cycleDeg() const {
        switch (cycle) {
            case Cycle::Otto4:
            case Cycle::Diesel4: return 720.0f;
            case Cycle::Wankel4: return 1080.0f;
            default: return 360.0f;
        }
    }
    bool isFourStroke() const { return cycle == Cycle::Otto4 || cycle == Cycle::Diesel4 || cycle == Cycle::Wankel4; }
    bool isDiesel() const { return cycle == Cycle::Diesel4 || cycle == Cycle::Diesel2; }
    bool hasCombustion() const {
        return cycle == Cycle::Otto4 || cycle == Cycle::Otto2 || cycle == Cycle::Diesel4 ||
               cycle == Cycle::Diesel2 || cycle == Cycle::Wankel4;
    }
};

// JSON から展開済みの EngineSpec を構築する
bool loadEngineSpec(const JsonValue& root, EngineSpec& out, std::string& error);
bool loadEngineSpecFromText(const std::string& json, EngineSpec& out, std::string& error);

const char* familyName(Family f);
const char* cycleName(Cycle c);

}  // namespace es
