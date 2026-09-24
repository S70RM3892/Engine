#version 450
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNrm;

layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;
} F;

// DrawItem: float model[12] + base + mat + heat + emissive (112 バイト, std430)
layout(push_constant) uniform Obj {
    float m[12];
    vec4 base;
    vec4 mat;
    vec4 heat;
    vec4 emis;
} O;

layout(location = 0) out vec3 vPos;
layout(location = 1) out vec3 vNrm;

void main() {
    vec4 r0 = vec4(O.m[0], O.m[1], O.m[2], O.m[3]);
    vec4 r1 = vec4(O.m[4], O.m[5], O.m[6], O.m[7]);
    vec4 r2 = vec4(O.m[8], O.m[9], O.m[10], O.m[11]);
    vec4 p = vec4(aPos, 1.0);
    vec3 w = vec3(dot(r0, p), dot(r1, p), dot(r2, p));
    vPos = w;
    vNrm = vec3(dot(r0.xyz, aNrm), dot(r1.xyz, aNrm), dot(r2.xyz, aNrm));
    gl_Position = F.viewProj * vec4(w, 1.0);
}
