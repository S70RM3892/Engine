// プロシージャル物理音響合成。
//   クランク角 → [燃焼室ごとのブローダウンパルス] → [ランナー導波管(櫛形)] → [バンク集合管]
//   → [4 本の部分反射導波管 (マフラー/テールパイプ)] → 排気音
//   + 吸気乱流/吸気パルス, 動弁打音/ピストンスラップ (モーダル共振), 過給機, ギア鳴き,
//     プロペラ BPF, タービン (ジェット騒音/ブレード通過音), モータ (PWM キャリア/電磁音)
//   → 3D パンニング (カメラ方位基準) → DC 除去/ソフトリミッタ
// render() はオーディオスレッドから呼ばれ、ロック・メモリ確保を一切行わない。
#pragma once

#include <atomic>
#include <memory>
#include <vector>

#include "../core/EngineSpec.h"
#include "../sim/SharedState.h"
#include "DspPrimitives.h"

namespace es {

struct AcousticConfig {
    Family family = Family::Reciprocating;
    Cycle cycle = Cycle::Otto4;
    float cycleDeg = 720;
    float redlineRpm = 7000;
    int chambers = 0;
    float cylVolumeL = 0.5f;
    bool ohv = false;
    bool poppetValves = true;

    enum EvType : uint8_t { ExhaustOpen, IntakeOpen, ValveClose, Slap };
    struct Event { float angle; EvType type; uint8_t chamber; };
    std::vector<Event> events;  // 角度昇順
    int bankOf[kMaxChambers] = {};
    int bankCount = 1;
    float runnerDelaySec[kMaxChambers] = {};
    float collectorDelaySec = 0.002f;
    float tailDelaySec = 0.006f;
    float mufflerCutoffHz = 2500;

    InductionType induction = InductionType::Natural;
    int compressorBlades = 11;
    float turboMaxRpm = 180000;
    int rootsLobes = 3;
    float rootsRatio = 2.0f;

    bool propeller = false;
    int propBlades = 3;
    float propReduction = 1;
    int gearTeeth = 29;

    TurbineKind turbineKind = TurbineKind::Turbojet;
    int fanBlades = 0, turbineCompressorBlades = 30;
    float n1MaxRpm = 10000, n2MaxRpm = 15000;

    MotorKind motorKind = MotorKind::PMSM;
    int polePairs = 4, statorSlots = 48;
    float pwmHz = 8000;

    static std::unique_ptr<AcousticConfig> fromSpec(const EngineSpec& spec);
};

class EngineAcousticsDSP {
public:
    EngineAcousticsDSP();
    ~EngineAcousticsDSP();

    void setSampleRate(float sr);
    // 制御スレッドから: 新しい構成を渡す (オーディオスレッドが次のバッファで取り込む)
    void setConfig(std::unique_ptr<AcousticConfig> cfg);
    // オーディオスレッドから: frames 個のステレオ (L,R 交互) サンプルを生成
    void render(float* out, int frames, const AudioFeed& feed);

private:
    struct Pulse { float t = 0, amp = 0, dur = 0; bool active = false; };

    void resetState();
    void triggerEvent(const AcousticConfig::Event& e, const AudioFeed& feed, float rpm);
    float renderCombustion(const AcousticConfig& c, const AudioFeed& feed, float rpm, float& left, float& right);
    void renderTurbine(const AcousticConfig& c, const AudioFeed& feed, float& left, float& right);
    void renderMotor(const AcousticConfig& c, const AudioFeed& feed, float& left, float& right);
    void pan(float x, float azimuthDeg, float& l, float& r) const;

    float sr_ = 48000;
    AcousticConfig* cfg_ = nullptr;  // オーディオスレッド所有
    std::atomic<AcousticConfig*> pending_{nullptr};
    std::atomic<AcousticConfig*> retired_{nullptr};

    // 状態
    double theta_ = 0;
    int nextEvent_ = 0;
    float rpmSm_ = 0, thrSm_ = 0, mapSm_ = 1, boostSm_ = 0, turboSm_ = 0, loadSm_ = 0, torqueSm_ = 0;
    float n1Sm_ = 0, n2Sm_ = 0, outRpmSm_ = 0, ehzSm_ = 0;
    float camYaw_ = 0;
    uint32_t lastBov_ = 0;
    float bovEnv_ = 0;
    Pulse pulse_[kMaxChambers];
    Pulse intake_[kMaxChambers];
    dsp::Delay<2048>* runner_ = nullptr;  // kMaxChambers 本
    dsp::Delay<2048> collector_[2];
    dsp::Delay<8192> tail_[4];
    dsp::OnePoleLP tailLp_[4];
    dsp::Biquad muffler_[2][2], mufflerRes_[2];
    dsp::Biquad intakeBp_, intakeHiss_, throttleWhistle_, flowLp_[2];
    dsp::Biquad mechModes_[5];
    dsp::Biquad turboWhoosh_, bovBp_, jetLp_, jetLp2_, jetHp_, motorWindage_;
    dsp::OnePoleLP rumbleLp_;
    dsp::Osc turboOsc_, rootsOsc_, rootsOsc2_, gearOsc_, propOsc_[4], compOsc_, compOsc2_, fanOsc_, buzzOsc_[6];
    dsp::Osc pwmOsc_[4], emOsc_[4];
    dsp::DCBlocker dc_[2];
    // マスター (聴感補正): 超低域カット → 低域シェルフ → 仮想低音 → 2〜5kHz を控えめに → 急峻なローパス → コンプレッサ
    struct Master {
        dsp::Biquad sub, shelf, presence, lp1, lp2, lp3;
        dsp::Biquad vbLp, vbHp, vbOutHp, vbOutLp;
    } master_[2];
    float compEnv_ = 0;
    void setupMaster();
    void processMaster(float& L, float& R, float bass);
    dsp::Noise noise_;
    float mechExc_[5] = {};
    float slapGain_ = 0;
};

}  // namespace es
