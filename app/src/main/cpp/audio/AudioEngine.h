// Oboe (AAudio/OpenSL ES) による低遅延ステレオ出力。コールバック内で DSP を直接実行する。
#pragma once

#include <oboe/Oboe.h>

#include <memory>
#include <mutex>

#include "EngineAcousticsDSP.h"

namespace es {

class AudioEngine : public oboe::AudioStreamDataCallback, public oboe::AudioStreamErrorCallback {
public:
    AudioEngine(EngineAcousticsDSP* dsp, const AudioFeed* feed) : dsp_(dsp), feed_(feed) {}
    ~AudioEngine() override { stop(); }

    bool start();
    void stop();
    int sampleRate() const { return sampleRate_; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool openStream();
    EngineAcousticsDSP* dsp_;
    const AudioFeed* feed_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::mutex lock_;
    int sampleRate_ = 48000;
    bool wantRunning_ = false;
};

}  // namespace es
