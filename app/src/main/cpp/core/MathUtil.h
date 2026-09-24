// 軽量ベクトル/行列/クォータニオン。レンダラとキネマティクスで共用する。
#pragma once

#include <cmath>
#include <cstdint>
#include <algorithm>

namespace es {

constexpr float kPi = 3.14159265358979323846f;
constexpr float kTwoPi = 2.0f * kPi;
constexpr double kPiD = 3.14159265358979323846;
constexpr double kTwoPiD = 2.0 * kPiD;

inline float deg2rad(float d) { return d * (kPi / 180.0f); }
inline double deg2radD(double d) { return d * (kPiD / 180.0); }
inline float rad2deg(float r) { return r * (180.0f / kPi); }
inline float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
inline float lerpf(float a, float b, float t) { return a + (b - a) * t; }
inline float smoothstepf(float e0, float e1, float x) {
    float t = clampf((x - e0) / (e1 - e0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}
// [0, period) へ折り返す
inline double wrapPos(double v, double period) {
    double r = std::fmod(v, period);
    return r < 0.0 ? r + period : r;
}
// [-period/2, period/2) へ折り返す
inline double wrapSym(double v, double period) {
    return wrapPos(v + 0.5 * period, period) - 0.5 * period;
}

struct Vec3 {
    float x = 0, y = 0, z = 0;
    constexpr Vec3() = default;
    constexpr Vec3(float x_, float y_, float z_) : x(x_), y(y_), z(z_) {}
    Vec3 operator+(const Vec3& o) const { return {x + o.x, y + o.y, z + o.z}; }
    Vec3 operator-(const Vec3& o) const { return {x - o.x, y - o.y, z - o.z}; }
    Vec3 operator*(float s) const { return {x * s, y * s, z * s}; }
    Vec3 operator-() const { return {-x, -y, -z}; }
    Vec3& operator+=(const Vec3& o) { x += o.x; y += o.y; z += o.z; return *this; }
    Vec3& operator-=(const Vec3& o) { x -= o.x; y -= o.y; z -= o.z; return *this; }
    Vec3& operator*=(float s) { x *= s; y *= s; z *= s; return *this; }
};
inline float dot(const Vec3& a, const Vec3& b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
inline Vec3 cross(const Vec3& a, const Vec3& b) {
    return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
inline float length(const Vec3& v) { return std::sqrt(dot(v, v)); }
inline Vec3 normalize(const Vec3& v) {
    float l = length(v);
    return l > 1e-12f ? v * (1.0f / l) : Vec3{0, 0, 0};
}
inline Vec3 lerp(const Vec3& a, const Vec3& b, float t) { return a + (b - a) * t; }

struct Quat {
    float x = 0, y = 0, z = 0, w = 1;
    static Quat axisAngle(const Vec3& axis, float rad) {
        Vec3 n = normalize(axis);
        float s = std::sin(rad * 0.5f);
        return {n.x * s, n.y * s, n.z * s, std::cos(rad * 0.5f)};
    }
    Quat operator*(const Quat& b) const {
        return {w * b.x + x * b.w + y * b.z - z * b.y,
                w * b.y - x * b.z + y * b.w + z * b.x,
                w * b.z + x * b.y - y * b.x + z * b.w,
                w * b.w - x * b.x - y * b.y - z * b.z};
    }
    Vec3 rotate(const Vec3& v) const {
        Vec3 q{x, y, z};
        Vec3 t = cross(q, v) * 2.0f;
        return v + t * w + cross(q, t);
    }
};

// 列優先 (OpenGL と同じ) 4x4 行列
struct Mat4 {
    float m[16];
    static Mat4 identity() {
        Mat4 r{};
        for (int i = 0; i < 16; ++i) r.m[i] = 0;
        r.m[0] = r.m[5] = r.m[10] = r.m[15] = 1;
        return r;
    }
    float& at(int row, int col) { return m[col * 4 + row]; }
    float at(int row, int col) const { return m[col * 4 + row]; }
    Mat4 operator*(const Mat4& b) const {
        Mat4 r{};
        for (int c = 0; c < 4; ++c)
            for (int rr = 0; rr < 4; ++rr) {
                float s = 0;
                for (int k = 0; k < 4; ++k) s += at(rr, k) * b.at(k, c);
                r.at(rr, c) = s;
            }
        return r;
    }
    Vec3 transformPoint(const Vec3& p) const {
        return {at(0, 0) * p.x + at(0, 1) * p.y + at(0, 2) * p.z + at(0, 3),
                at(1, 0) * p.x + at(1, 1) * p.y + at(1, 2) * p.z + at(1, 3),
                at(2, 0) * p.x + at(2, 1) * p.y + at(2, 2) * p.z + at(2, 3)};
    }
    static Mat4 translate(const Vec3& t) {
        Mat4 r = identity();
        r.at(0, 3) = t.x; r.at(1, 3) = t.y; r.at(2, 3) = t.z;
        return r;
    }
    static Mat4 scale(const Vec3& s) {
        Mat4 r = identity();
        r.at(0, 0) = s.x; r.at(1, 1) = s.y; r.at(2, 2) = s.z;
        return r;
    }
    static Mat4 rotation(const Quat& q) {
        Mat4 r = identity();
        float xx = q.x * q.x, yy = q.y * q.y, zz = q.z * q.z;
        float xy = q.x * q.y, xz = q.x * q.z, yz = q.y * q.z;
        float wx = q.w * q.x, wy = q.w * q.y, wz = q.w * q.z;
        r.at(0, 0) = 1 - 2 * (yy + zz); r.at(0, 1) = 2 * (xy - wz);     r.at(0, 2) = 2 * (xz + wy);
        r.at(1, 0) = 2 * (xy + wz);     r.at(1, 1) = 1 - 2 * (xx + zz); r.at(1, 2) = 2 * (yz - wx);
        r.at(2, 0) = 2 * (xz - wy);     r.at(2, 1) = 2 * (yz + wx);     r.at(2, 2) = 1 - 2 * (xx + yy);
        return r;
    }
    static Mat4 trs(const Vec3& t, const Quat& q, const Vec3& s = {1, 1, 1}) {
        return translate(t) * rotation(q) * scale(s);
    }
    static Mat4 perspective(float fovyRad, float aspect, float zn, float zf) {
        Mat4 r{};
        for (float& v : r.m) v = 0;
        float f = 1.0f / std::tan(fovyRad * 0.5f);
        r.at(0, 0) = f / aspect;
        r.at(1, 1) = f;
        r.at(2, 2) = (zf + zn) / (zn - zf);
        r.at(2, 3) = 2 * zf * zn / (zn - zf);
        r.at(3, 2) = -1;
        return r;
    }
    static Mat4 lookAt(const Vec3& eye, const Vec3& target, const Vec3& up) {
        Vec3 f = normalize(target - eye);
        Vec3 s = normalize(cross(f, up));
        Vec3 u = cross(s, f);
        Mat4 r = identity();
        r.at(0, 0) = s.x; r.at(0, 1) = s.y; r.at(0, 2) = s.z;
        r.at(1, 0) = u.x; r.at(1, 1) = u.y; r.at(1, 2) = u.z;
        r.at(2, 0) = -f.x; r.at(2, 1) = -f.y; r.at(2, 2) = -f.z;
        r.at(0, 3) = -dot(s, eye);
        r.at(1, 3) = -dot(u, eye);
        r.at(2, 3) = dot(f, eye);
        return r;
    }
    // 回転+平行移動のみ(スケールは一様)を仮定した法線行列 3x3 を取り出す
    void normalMatrix3(float out[9]) const {
        for (int c = 0; c < 3; ++c)
            for (int r = 0; r < 3; ++r) out[c * 3 + r] = at(r, c);
    }
};

// 2 ベクトル a→b を合わせる最小回転
inline Quat rotationBetween(const Vec3& a, const Vec3& b) {
    Vec3 na = normalize(a), nb = normalize(b);
    float d = dot(na, nb);
    if (d > 0.999999f) return {};
    if (d < -0.999999f) {
        Vec3 axis = cross({1, 0, 0}, na);
        if (length(axis) < 1e-4f) axis = cross({0, 1, 0}, na);
        return Quat::axisAngle(axis, kPi);
    }
    Vec3 c = cross(na, nb);
    float s = std::sqrt((1 + d) * 2);
    float inv = 1.0f / s;
    return {c.x * inv, c.y * inv, c.z * inv, s * 0.5f};
}

}  // namespace es
