#version 450
layout(location = 0) in vec2 vUv;
layout(set = 0, binding = 0) uniform Frame {
    mat4 viewProj;
    vec4 camPos;
    vec4 clip;
    vec4 flash;
    vec4 misc;
} F;
layout(location = 0) out vec4 fragColor;
vec3 toLinear(vec3 c) { return pow(c, vec3(2.2)); }
void main() {
    vec3 top = vec3(0.16, 0.18, 0.21);
    vec3 bottom = vec3(0.035, 0.038, 0.045);
    vec3 c = mix(bottom, top, smoothstep(0.0, 1.0, vUv.y));
    float v = 1.0 - 0.35 * dot(vUv - 0.5, vUv - 0.5) * 2.0;
    vec2 g = abs(fract(vUv * vec2(24.0, 14.0)) - 0.5);
    float grid = (1.0 - smoothstep(0.0, 0.03, min(g.x, g.y))) * 0.025;
    vec3 outc = c * v + grid + F.flash.rgb * F.flash.a * 0.05;
    fragColor = vec4(F.misc.y > 0.5 ? outc : toLinear(outc), 1.0);
}
