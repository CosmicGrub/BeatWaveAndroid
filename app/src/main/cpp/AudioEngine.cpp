#include "AudioEngine.h"
#include <android/log.h>
#include <cstring>

#define TAG "BeatWaveAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace beatwave {

bool AudioEngine::start() {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setDataCallback(this);

    oboe::Result result = builder.openStream(mStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open stream: %s", oboe::convertToText(result));
        return false;
    }

    result = mStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start stream: %s", oboe::convertToText(result));
        mStream->close();
        mStream.reset();
        return false;
    }

    LOGI("Stream opened OK: sampleRate=%d, framesPerBurst=%d",
         mStream->getSampleRate(), mStream->getFramesPerBurst());
    return true;
}

void AudioEngine::stop() {
    if (mStream) {
        mStream->requestStop();
        mStream->close();
        mStream.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {
    // Phase 0: silence passthrough only. Phase 2 replaces this with the
    // real multi-track mix.
    auto *floatData = static_cast<float *>(audioData);
    std::memset(floatData, 0, sizeof(float) * numFrames * audioStream->getChannelCount());
    return oboe::DataCallbackResult::Continue;
}

} // namespace beatwave
