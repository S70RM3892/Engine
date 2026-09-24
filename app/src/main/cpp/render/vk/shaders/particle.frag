#version 450
layout(location = 0) in vec2 vUv;
layout(location = 1) in vec4 vColor;
layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;  // y = 1: スワップチェーンが UNORM (シェーダでガンマ補正)
} F;
layout(location = 0) out vec4 fragColor;
void main() {
    float r = length(vUv * 2.0 - 1.0);
    float m = smoothstep(1.0, 0.0, r);
    m *= m;
    // 発光は線形空間で加算 (sRGB スワップチェーン)
    vec3 c = F.misc.y > 0.5 ? vColor.rgb : pow(vColor.rgb, vec3(2.2)) * 1.6;
    fragColor = vec4(c * m, vColor.a * m);
}
