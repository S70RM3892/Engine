// フロントエンド (カメラ・表示モード・色付け・パーティクル) が作る 1 フレーム分の描画記述。
// Vulkan / GLES の各バックエンドはこれを描くだけにする (API 非依存)。
#pragma once

#include <cstdint>
#include <vector>

#include "../core/MathUtil.h"

namespace es {

// 1 ノード分のパラメータ。Vulkan ではそのまま push constant (112 バイト) になる。
// 先頭 112 バイト (model〜emissive) を std430 の push constant として送る。vec4 が 16 バイト境界に並ぶ順にしてある。
struct DrawItem {
    float model[12];    // アフィン変換の上 3 行 (行優先)
    float base[4];      // rgb, metallic
    float mat[4];       // roughness, coat, alpha, flags (bit0: クリップ有効, bit1: X 線フレネル)
    float heat[4];      // rgb, weight
    float emissive[4];  // rgb, 0
    int32_t mesh = 0;
};
constexpr uint32_t kPushBytes = 112;
static_assert(sizeof(float) * 28 == kPushBytes, "DrawItem push layout");

enum DrawFlags : uint32_t { kFlagClip = 1, kFlagXray = 2 };

struct ParticleVertex {
    float pos[3];
    float uv[2];
    float color[4];  // 加算合成用 (プリマルチプライド)
};

struct FrameData {
    Mat4 viewProj;           // OpenGL 規約 (z: -1..1, y 上向き)
    Vec3 camPos;
    float clip[4] = {0, 0, 0, 0};
    float flash[4] = {0, 0, 0, 0};  // 画面全体の閃光 (rgb, 強さ) ゲーム演出
    float time = 0;
    bool section = false;
    std::vector<DrawItem> opaque;
    std::vector<DrawItem> stencil;        // 断面キャップ用の偶奇カウント描画
    std::vector<uint8_t> stencilGroup;    // 各 stencil 描画のビット番号 (0..7)
    float capQuad[12] = {};               // 切断平面を覆う四角形 (TRIANGLE_STRIP 4 頂点)
    Vec3 capColor[8];
    std::vector<DrawItem> transparent;    // 燃焼ガス → 外殻の順
    std::vector<ParticleVertex> particles;  // 三角形リスト (1 粒子 6 頂点)

    void clearLists() {
        opaque.clear();
        stencil.clear();
        stencilGroup.clear();
        transparent.clear();
        particles.clear();
    }
};

}  // namespace es
