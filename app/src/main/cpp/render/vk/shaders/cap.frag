#version 450
layout(push_constant) uniform Obj {
    float m[12];
    vec4 base;  // キャップ色
    vec4 mat;
    vec4 heat;
    vec4 emis;
} O;
layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;  // y = 1: スワップチェーンが UNORM (シェーダでガンマ補正)
} F;
layout(location = 0) out vec4 fragColor;
void main() {
    float h = step(0.72, fract((gl_FragCoord.x + gl_FragCoord.y) / 9.0));
    vec3 c = mix(O.base.rgb, O.base.rgb * 0.45, h);
    if (F.misc.y > 0.5) c = pow(c, vec3(1.0 / 2.2));
    fragColor = vec4(c, 1.0);
}
