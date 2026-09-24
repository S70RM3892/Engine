#include "Camera.h"

#include <cmath>

namespace es {

void CameraRig::setGoal(const OrbitState& s, bool snap) {
    goal_ = s;
    userYaw_ = 0;
    userPitch_ = 0;
    if (snap) cur_ = s;
}

void CameraRig::update(float dt, float yawRate, float pitchRate, float zoom, const Vec3* follow) {
    userYaw_ += yawRate * dt;
    userPitch_ += pitchRate * dt;
    float totalPitch = goal_.pitch + userPitch_;
    if (totalPitch > 1.45f) userPitch_ = 1.45f - goal_.pitch;
    if (totalPitch < -1.45f) userPitch_ = -1.45f - goal_.pitch;
    zoom_ = clampf(zoom, 0.2f, 5.0f);
    if (follow) goal_.target = *follow;
    // 臨界減衰に近い指数補間 (ジッター無し)
    float k = 1.0f - std::exp(-dt * 6.0f);
    cur_.target = lerp(cur_.target, goal_.target, k);
    // yaw は最短方向で補間
    float dy = std::remainder(goal_.yaw - cur_.yaw, kTwoPi);
    cur_.yaw += dy * k;
    cur_.pitch += (goal_.pitch - cur_.pitch) * k;
    cur_.dist += (goal_.dist - cur_.dist) * k;
}

Vec3 CameraRig::eye() const {
    float y = cur_.yaw + userYaw_;
    float p = clampf(cur_.pitch + userPitch_, -1.5f, 1.5f);
    float d = cur_.dist / zoom_;
    return cur_.target + Vec3{d * std::cos(p) * std::sin(y), d * std::sin(p), d * std::cos(p) * std::cos(y)};
}

Mat4 CameraRig::view() const { return Mat4::lookAt(eye(), cur_.target, {0, 1, 0}); }

Mat4 CameraRig::proj(float aspect) const {
    float d = cur_.dist / zoom_;
    float n = std::max(0.002f, d * 0.02f);
    float f = d * 20.0f + 10.0f;
    const_cast<CameraRig*>(this)->near_ = n;
    const_cast<CameraRig*>(this)->far_ = f;
    return Mat4::perspective(deg2rad(40.0f), aspect, n, f);
}

}  // namespace es
