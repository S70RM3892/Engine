// GLSL ES 3.00 シェーダ
#pragma once

namespace es::shaders {

inline const char* kPbrVS = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;
uniform mat4 uModel;
uniform mat4 uViewProj;
out vec3 vPos;
out vec3 vNrm;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vPos = w.xyz;
    vNrm = mat3(uModel) * aNrm;
    gl_Position = uViewProj * w;
}
)";

// 物理ベース (GGX / Smith / Schlick, Metallic-Roughness) + オイル油膜の第 2 スペキュラ
// + 半球環境光と擬似環境反射。クリップ平面で断面、X 線はフレネル半透明。
inline const char* kPbrFS = R"(#version 300 es
precision highp float;
in vec3 vPos;
in vec3 vNrm;
uniform vec3 uCamPos;
uniform vec3 uBase;
uniform float uMetal;
uniform float uRough;
uniform float uCoat;
uniform float uAlpha;
uniform vec4 uClip;
uniform int uClipOn;
uniform vec4 uHeat;
uniform vec3 uEmissive;
uniform int uXray;
uniform vec4 uFlash;
out vec4 fragColor;

const float PI = 3.14159265;
const vec3 L1 = normalize(vec3(0.45, 0.85, 0.55));
const vec3 L2 = normalize(vec3(-0.6, 0.35, -0.7));
const vec3 C1 = vec3(2.6, 2.5, 2.35);
const vec3 C2 = vec3(0.7, 0.8, 1.0);

vec3 envColor(vec3 d) {
    float t = clamp(d.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 c = mix(vec3(0.07, 0.07, 0.08), vec3(0.62, 0.67, 0.75), smoothstep(0.25, 0.95, t));
    c += vec3(1.4) * pow(max(dot(d, L1), 0.0), 48.0);
    // スタジオの帯状ライト
    c += vec3(0.8) * smoothstep(0.92, 0.99, 1.0 - abs(d.x)) * step(0.0, d.y);
    return c;
}

float dGGX(float noh, float a) {
    float a2 = a * a;
    float d = noh * noh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d);
}

float vSmith(float nov, float nol, float a) {
    float k = a * 0.5;
    return 0.25 / ((nov * (1.0 - k) + k) * (nol * (1.0 - k) + k));
}

vec3 fSchlick(vec3 f0, float voh) { return f0 + (1.0 - f0) * pow(1.0 - voh, 5.0); }

vec3 lightTerm(vec3 N, vec3 V, vec3 L, vec3 lc, vec3 albedo, vec3 f0, float a, float coat) {
    float nol = max(dot(N, L), 0.0);
    if (nol <= 0.0) return vec3(0.0);
    vec3 H = normalize(V + L);
    float noh = max(dot(N, H), 0.0);
    float nov = max(dot(N, V), 1e-3);
    float voh = max(dot(V, H), 0.0);
    vec3 F = fSchlick(f0, voh);
    vec3 spec = dGGX(noh, a) * vSmith(nov, nol, a) * F;
    vec3 diff = (1.0 - F) * albedo / PI;
    // 油膜: 低ラフネスの誘電体層
    float fc = 0.04 + 0.96 * pow(1.0 - voh, 5.0);
    float cspec = coat * fc * dGGX(noh, 0.06) * vSmith(nov, nol, 0.06);
    return (diff + spec + vec3(cspec)) * lc * nol;
}

vec3 aces(vec3 x) {
    return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0);
}

void main() {
    if (uClipOn == 1 && dot(uClip.xyz, vPos) + uClip.w > 0.0) discard;
    vec3 N = normalize(vNrm);
    vec3 V = normalize(uCamPos - vPos);
    if (!gl_FrontFacing) N = -N;
    vec3 base = mix(uBase, uHeat.rgb, uHeat.a);
    float metal = mix(uMetal, 0.1, uHeat.a);
    float rough = clamp(mix(uRough, 0.55, uHeat.a), 0.04, 1.0);
    float a = rough * rough;
    vec3 f0 = mix(vec3(0.04), base, metal);
    vec3 albedo = base * (1.0 - metal);
    vec3 col = lightTerm(N, V, L1, C1, albedo, f0, a, uCoat) + lightTerm(N, V, L2, C2, albedo, f0, a, uCoat);
    // 環境光: 半球拡散 + ラフネスでぼかした反射
    vec3 R = reflect(-V, N);
    vec3 envSpec = mix(envColor(R), vec3(0.3, 0.32, 0.35), rough * rough);
    float nov = max(dot(N, V), 0.0);
    vec3 Fe = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - nov, 5.0);
    vec3 hemi = mix(vec3(0.05, 0.05, 0.06), vec3(0.32, 0.34, 0.38), N.y * 0.5 + 0.5);
    col += albedo * hemi + envSpec * Fe * 0.9;
    col += uCoat * 0.25 * envColor(R) * (0.04 + 0.96 * pow(1.0 - nov, 5.0));
    col += uEmissive;
    col += uFlash.rgb * uFlash.a * (0.12 * albedo + 0.05);
    col = aces(col * 0.9);
    col = pow(col, vec3(1.0 / 2.2));
    float alpha = uAlpha;
    if (uXray == 1) {
        float fr = pow(1.0 - nov, 2.5);
        alpha = clamp(uAlpha + fr * 0.5, 0.0, 0.85);
        col = mix(col, vec3(0.55, 0.78, 1.0), 0.35 * (1.0 - uHeat.a));
    }
    fragColor = vec4(col, alpha);
}
)";

inline const char* kCapVS = R"(#version 300 es
layout(location = 0) in vec3 aPos;
uniform mat4 uViewProj;
void main() { gl_Position = uViewProj * vec4(aPos, 1.0); }
)";

// 断面キャップ: 製図風のハッチング
inline const char* kCapFS = R"(#version 300 es
precision mediump float;
uniform vec3 uCapColor;
out vec4 fragColor;
void main() {
    float h = step(0.72, fract((gl_FragCoord.x + gl_FragCoord.y) / 9.0));
    vec3 c = mix(uCapColor, uCapColor * 0.45, h);
    fragColor = vec4(pow(c, vec3(1.0 / 2.2)), 1.0);
}
)";

inline const char* kBgVS = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.999, 1.0);
}
)";

inline const char* kBgFS = R"(#version 300 es
precision mediump float;
in vec2 vUv;
uniform vec4 uFlash;
out vec4 fragColor;
void main() {
    vec3 top = vec3(0.16, 0.18, 0.21);
    vec3 bottom = vec3(0.035, 0.038, 0.045);
    vec3 c = mix(bottom, top, smoothstep(0.0, 1.0, vUv.y));
    float v = 1.0 - 0.35 * dot(vUv - 0.5, vUv - 0.5) * 2.0;
    // 薄いグリッド
    vec2 g = abs(fract(vUv * vec2(24.0, 14.0)) - 0.5);
    float grid = (1.0 - smoothstep(0.0, 0.03, min(g.x, g.y))) * 0.025;
    fragColor = vec4(c * v + grid + uFlash.rgb * uFlash.a * 0.05, 1.0);
}
)";

// パーティクル (プリマルチプライド: 加算発光 + α遮蔽の煙を 1 パスで)
inline const char* kParticleVS = R"(#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
uniform mat4 uViewProj;
out vec2 vUv;
out vec4 vColor;
void main() {
    vUv = aUv;
    vColor = aColor;
    gl_Position = uViewProj * vec4(aPos, 1.0);
}
)";

inline const char* kParticleFS = R"(#version 300 es
precision mediump float;
in vec2 vUv;
in vec4 vColor;
out vec4 fragColor;
void main() {
    float r = length(vUv * 2.0 - 1.0);
    float m = smoothstep(1.0, 0.0, r);
    m *= m;
    fragColor = vec4(vColor.rgb * m, vColor.a * m);
}
)";

}  // namespace es::shaders
