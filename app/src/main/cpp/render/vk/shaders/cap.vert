#version 450
layout(location = 0) in vec3 aPos;
layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;
} F;
void main() { gl_Position = F.viewProj * vec4(aPos, 1.0); }
