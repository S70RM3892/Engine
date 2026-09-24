#include "AudioEngine.h"

#include <android/log.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "EngineSimAudio", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EngineSimAudio", __VA_ARGS__)

namespace es {

bool AudioEngine::openStream() {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)
        ->setContentType(oboe::ContentType::Sonification)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) {
        LOGE("openStream failed: %s", oboe::convertToText(r));
        return false;
    }
    sampleRate_ = stream_->getSampleRate();
    dsp_->setSampleRate(static_cast<float>(sampleRate_));
    // バッファは 2 バースト分 (低遅延と途切れにくさの折衷)
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    LOGI("audio stream: %d Hz, burst %d, api %d", sampleRate_, stream_->getFramesPerBurst(),
         static_cast<int>(stream_->getAudioApi()));
    return stream_->requestStart() == oboe::Result::OK;
}

bool AudioEngine::start() {
    std::lock_guard<std::mutex> g(lock_);
    wantRunning_ = true;
    if (stream_) return true;
    return openStream();
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> g(lock_);
    wantRunning_ = false;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) {
    float* out = static_cast<float*>(audioData);
    dsp_->render(out, numFrames, *feed_);
    (void)stream;
    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // ヘッドホン抜き差しなどでストリームが切断されたら開き直す
    LOGE("stream error %s, reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> g(lock_);
    stream_.reset();
    if (wantRunning_) openStream();
}

}  // namespace es
