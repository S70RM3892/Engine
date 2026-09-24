// コンソール操作のみで動くオービットカメラ (タッチジェスチャは使わない)。
#pragma once

#include "../core/MathUtil.h"

namespace es {

enum class CameraPreset : int { Overview = 0, CylinderSection = 1, CrankFront = 2, ValveFollow = 3, DriveOutput = 4 };

struct OrbitState {
    Vec3 target;
    float yaw = 0.6f, pitch = 0.35f, dist = 1.0f;
};

class CameraRig {
public:
    void setGoal(const OrbitState& s, bool snap);
    // パン/チルト速度 [rad/s], ズーム倍率 (0.25..4), 目標の追従 (バルブ追従用)
    void update(float dt, float yawRate, float pitchRate, float zoom, const Vec3* followTarget);
    Mat4 view() const;
    Mat4 proj(float aspect) const;
    Vec3 eye() const;
    float yaw() const { return cur_.yaw + userYaw_; }
    float nearPlane() const { return near_; }

private:
    OrbitState goal_, cur_;
    float userYaw_ = 0, userPitch_ = 0, zoom_ = 1;
    float near_ = 0.01f, far_ = 100;
};

}  // namespace es
