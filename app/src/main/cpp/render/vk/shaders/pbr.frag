#version 450
layout(location = 0) in vec3 vPos;
layout(location = 1) in vec3 vNrm;

layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;
} F;

layout(push_constant) uniform Obj {
    float m[12];
    vec4 base;
    vec4 mat;
    vec4 heat;
    vec4 emis;
} O;

layout(location = 0) out vec4 fragColor;

const float PI = 3.14159265;
const vec3 L1 = normalize(vec3(0.45, 0.85, 0.55));
const vec3 L2 = normalize(vec3(-0.6, 0.35, -0.7));
const vec3 C1 = vec3(2.6, 2.5, 2.35);
const vec3 C2 = vec3(0.7, 0.8, 1.0);

vec3 envColor(vec3 d) {
    float t = clamp(d.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 c = mix(vec3(0.07, 0.07, 0.08), vec3(0.62, 0.67, 0.75), smoothstep(0.25, 0.95, t));
    c += vec3(1.4) * pow(max(dot(d, L1), 0.0), 48.0);
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
    vec3 Fr = fSchlick(f0, voh);
    vec3 spec = dGGX(noh, a) * vSmith(nov, nol, a) * Fr;
    vec3 diff = (1.0 - Fr) * albedo / PI;
    float fc = 0.04 + 0.96 * pow(1.0 - voh, 5.0);
    float cspec = coat * fc * dGGX(noh, 0.06) * vSmith(nov, nol, 0.06);
    return (diff + spec + vec3(cspec)) * lc * nol;
}

vec3 aces(vec3 x) { return clamp((x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14), 0.0, 1.0); }

void main() {
    uint flags = uint(O.mat.w + 0.5);
    if ((flags & 1u) != 0u && dot(F.clip.xyz, vPos) + F.clip.w > 0.0) discard;
    vec3 N = normalize(vNrm);
    vec3 V = normalize(F.camPos.xyz - vPos);
    if (!gl_FrontFacing) N = -N;
    vec3 base = mix(O.base.rgb, O.heat.rgb, O.heat.a);
    float metal = mix(O.base.a, 0.1, O.heat.a);
    float rough = clamp(mix(O.mat.x, 0.55, O.heat.a), 0.04, 1.0);
    float a = rough * rough;
    vec3 f0 = mix(vec3(0.04), base, metal);
    vec3 albedo = base * (1.0 - metal);
    vec3 col = lightTerm(N, V, L1, C1, albedo, f0, a, O.mat.y) + lightTerm(N, V, L2, C2, albedo, f0, a, O.mat.y);
    vec3 R = reflect(-V, N);
    vec3 envSpec = mix(envColor(R), vec3(0.3, 0.32, 0.35), rough * rough);
    float nov = max(dot(N, V), 0.0);
    vec3 Fe = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - nov, 5.0);
    vec3 hemi = mix(vec3(0.05, 0.05, 0.06), vec3(0.32, 0.34, 0.38), N.y * 0.5 + 0.5);
    col += albedo * hemi + envSpec * Fe * 0.9;
    col += O.mat.y * 0.25 * envColor(R) * (0.04 + 0.96 * pow(1.0 - nov, 5.0));
    col += O.emis.rgb;
    col += F.flash.rgb * F.flash.a * (0.12 * albedo + 0.05);
    col = aces(col * 0.9);
    // スワップチェーンは sRGB フォーマットを使うのでガンマはハードウェアに任せる
    float alpha = O.mat.z;
    if ((flags & 2u) != 0u) {
        float fr = pow(1.0 - nov, 2.5);
        alpha = clamp(O.mat.z + fr * 0.5, 0.0, 0.85);
        col = mix(col, vec3(0.3, 0.6, 1.0), 0.35 * (1.0 - O.heat.a));
    }
    if (F.misc.y > 0.5) col = pow(col, vec3(1.0 / 2.2));
    fragColor = vec4(col, alpha);
}
