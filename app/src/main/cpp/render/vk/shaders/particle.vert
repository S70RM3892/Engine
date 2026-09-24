#version 450
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
layout(location = 2) in vec4 aColor;
layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;
} F;
layout(location = 0) out vec2 vUv;
layout(location = 1) out vec4 vColor;
void main() {
    vUv = aUv;
    vColor = aColor;
    gl_Position = F.viewProj * vec4(aPos, 1.0);
}
