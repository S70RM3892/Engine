// エンジン諸元から描画用シーン (メッシュ + 可動ノード) を構築する。GL 非依存。
// 各ノードは「どの運動量に従って動くか (Bind)」を持ち、毎フレーム KinState から
// ワールド行列を解析的に計算する (物理エンジンの拘束は使わない)。
#pragma once

#include <string>
#include <vector>

#include "../core/EngineSpec.h"
#include "../sim/EngineKinematics.h"
#include "Mesh.h"

namespace es {

struct Material {
    Vec3 base{0.7f, 0.7f, 0.7f};
    float metallic = 1.0f;
    float roughness = 0.4f;
    float coat = 0.0f;  // オイル油膜 (クリアコート的な第 2 スペキュラ)
};

namespace mat {
Material castIron();
Material aluminium();
Material castAluminium();
Material steel();
Material forgedSteel();
Material titanium();
Material copper();
Material brass();
Material blued();
Material nickel();
Material paint(const Vec3& c);
Material gas();
}  // namespace mat

enum class Bind : uint8_t {
    Static,
    Crank,       // index = クランク番号
    Piston,      // index = ピストン番号
    Rod,         // index = ピストン番号
    Valve,       // index = 燃焼室, sub = 0 吸気 / 1 排気
    RotAxis,     // 任意軸まわり回転: angle = ratio * 源角 + phase (源 = crank index or spool sub)
    Rotor,       // index = ヴァンケル・ローター
    SlideValve,  // index = ピストン番号
    GasVolume,   // index = 燃焼室
    OutputShaft, // ギアボックス出力 (ギア選択に追従)
};

enum class HeatSrc : uint8_t { None, Gas, Head, Piston, Liner, Exhaust, Oil, Coolant, Rotor, Combustor, Winding, TurbineHot };
enum class StressSrc : uint8_t { None, Piston, Rod, Crank };

struct SceneNode {
    int mesh = -1;
    Material mat;
    Bind bind = Bind::Static;
    int index = 0;
    int sub = 0;
    Mat4 local = Mat4::identity();
    // RotAxis 用
    Vec3 pivot;
    Vec3 axis{1, 0, 0};
    float ratio = 1.0f;
    float phase = 0.0f;
    int spool = -1;          // >=0 なら源角は映像スプール角, <0 なら crank(index) 角
    // Valve/GasVolume 用
    Vec3 dir{0, 1, 0};
    bool housing = false;    // X 線モードで半透明化する外殻
    bool sectionCut = false; // 断面モードで切断する
    bool gas = false;        // 燃焼ガス可視化 (半透明)
    HeatSrc heat = HeatSrc::None;
    int heatIndex = 0;
    StressSrc stress = StressSrc::None;
    int stressIndex = 0;
    int gearIndex = -1;      // ギアボックスの段 (選択段をハイライト)
    bool engineBody = true;  // カメラのフレーミング対象
};

struct Scene {
    std::vector<MeshData> meshes;
    std::vector<SceneNode> nodes;
    Vec3 center;
    float radius = 1.0f;
    float yzRadius = 1.0f;  // クランク軸に直交する方向の広がり (正面視のフレーミング)
    // カメラ用アンカー
    Vec3 crankCenter;
    Vec3 cyl1Center;       // シリンダ 1 の中央
    Vec3 cyl1Axis{0, 1, 0};
    float cyl1Size = 0.2f;
    int followValveNode = -1;  // バルブトレイン追従対象ノード
    Vec3 followOffset;
    Vec3 outputAnchor;
    float outputSize = 0.3f;
    float sectionX = 0.0f;     // 断面プリセットで使う切断位置 (X)

    int addMesh(MeshData m) {
        meshes.push_back(std::move(m));
        return static_cast<int>(meshes.size()) - 1;
    }
};

// 映像スプール角 (EngineSimulation::visualSpoolAngle と同じ並び)
struct VisualAngles {
    float spool[6] = {0, 0, 0, 0, 0, 0};
    float outputShaft = 0;  // ギアボックス出力軸 (連続角)
};

void buildScene(const EngineSpec& spec, Scene& out);
// ノードのワールド行列を計算
void evaluateScene(const Scene& scene, const EngineSpec& spec, const KinState& ks, const VisualAngles& va,
                   std::vector<Mat4>& world);

}  // namespace es
