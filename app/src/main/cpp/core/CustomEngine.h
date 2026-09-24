// ユーザー設定 (レイアウト/気筒数/寸法) からエンジン定義 JSON を生成する。
// クランクピン配置は等間隔点火になるよう自動設計する。
#pragma once

#include <string>

#include "Json.h"

namespace es {

struct CustomEngineParams {
    std::string name = "カスタムエンジン";
    std::string layout = "inline";  // inline | v | flat | radial | opposed | wankel
    int cylinders = 4;              // radial では 1 列あたりの気筒数, wankel ではローター数
    int rows = 1;                   // radial の列数
    float bankAngle = 90;           // v
    bool evenFire = true;           // v: スプリットピンで等間隔点火にする
    bool crossplane = false;        // v8 のみ: クロスプレーン
    std::string cycle = "otto4";    // otto4 | diesel4 | otto2
    float boreMm = 86, strokeMm = 86;
    float rodRatio = 1.65f;         // コンロッド長 / ストローク
    float compressionRatio = 10.5f;
    float idleRpm = 800, redlineRpm = 7000;
    std::string induction = "natural";  // natural | turbo | roots
    float boostBar = 1.0f;
    int valvesPerCyl = 4;
};

// 制約: 燃焼室数 ≤ 64 (音響の上限)
bool parseCustomParams(const JsonValue& j, CustomEngineParams& out, std::string& err);
bool buildCustomEngineJson(const CustomEngineParams& p, std::string& json, std::string& err);

}  // namespace es
