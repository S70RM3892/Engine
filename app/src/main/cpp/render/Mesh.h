// 手続き的メッシュ生成 (すべて閉じたソリッド: 断面ステンシルキャップの前提)
#pragma once

#include <cstdint>
#include <vector>

#include "../core/MathUtil.h"

namespace es {

struct Vec2 {
    float x = 0, y = 0;
};

struct MeshData {
    std::vector<float> vertices;    // px,py,pz,nx,ny,nz
    std::vector<uint32_t> indices;

    uint32_t vertexCount() const { return static_cast<uint32_t>(vertices.size() / 6); }
    uint32_t addVertex(const Vec3& p, const Vec3& n);
    void addTri(uint32_t a, uint32_t b, uint32_t c) { indices.insert(indices.end(), {a, b, c}); }
    // 別メッシュを変換して追加
    void append(const MeshData& o, const Mat4& m);
    void bounds(Vec3& mn, Vec3& mx) const;
};

namespace meshgen {

// 軸 = +X。x0..x1 の円柱 (両端キャップ付き)
MeshData cylinderX(float radius, float x0, float x1, int segs = 32);
// 軸 = +Y。y0..y1 の円柱
MeshData cylinderY(float radius, float y0, float y1, int segs = 32);
// 軸 = +X の中空円筒
MeshData tubeX(float rIn, float rOut, float x0, float x1, int segs = 40);
MeshData tubeY(float rIn, float rOut, float y0, float y1, int segs = 40);
// 中心 c, 寸法 size の直方体
MeshData box(const Vec3& c, const Vec3& size);
// YZ 平面の星形多角形 (原点から見て単調) を X 方向 x0..x1 に押し出したソリッド
MeshData extrudeStarX(const std::vector<Vec2>& poly, float x0, float x1);
// 内外 2 つの輪郭 (同頂点数) の間を押し出したリング
MeshData extrudeRingX(const std::vector<Vec2>& inner, const std::vector<Vec2>& outer, float x0, float x1);
// 外歯車輪郭 (YZ 平面)
std::vector<Vec2> gearProfile(float radius, int teeth, float toothDepth, int stepsPerTooth = 6);
// 円輪郭
std::vector<Vec2> circleProfile(float radius, int segs, float phase = 0);
// ロッド (大端=原点, 小端=+Y 方向 L) : 軸 X の目玉 2 つとテーパビーム
MeshData connectingRod(float length, float bigR, float smallR, float width, float thickness);

}  // namespace meshgen
}  // namespace es
