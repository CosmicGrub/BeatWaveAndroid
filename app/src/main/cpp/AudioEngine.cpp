#include "AudioEngine.h"

#include <android/log.h>
#include <cstring>

#include "MixEngine.h"

#define TAG "BeatWaveAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace beatwave {

AudioEngine::AudioEngine(int32_t offlineSampleRateHz)
        : mOfflineSampleRateHz(offlineSampleRateHz) {}

void AudioEngine::init(AAssetManager *assetManager) {
    mAssetManager = assetManager;
}

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

void AudioEngine::play() {
    mPlaying.store(true, std::memory_order_relaxed);
}

void AudioEngine::pause() {
    mPlaying.store(false, std::memory_order_relaxed);
}

void AudioEngine::stopTransport() {
    mPlaying.store(false, std::memory_order_relaxed);
    // Mandate 6: this is the ONLY place (besides seekToFrame) the transport
    // counter is ever reset -- never inside onAudioReady/renderOffline.
    mTransportFrame.store(0, std::memory_order_relaxed);
}

void AudioEngine::seekToFrame(int64_t frame) {
    mTransportFrame.store(frame, std::memory_order_relaxed);
}

int64_t AudioEngine::getCurrentFrame() const {
    return mTransportFrame.load(std::memory_order_relaxed);
}

int32_t AudioEngine::getSampleRate() const {
    if (mOfflineSampleRateHz != 0) {
        return mOfflineSampleRateHz;
    }
    if (mStream) {
        return mStream->getSampleRate();
    }
    return 0;
}

void AudioEngine::beginProject(int32_t bpm) {
    const int32_t sampleRate = getSampleRate();
    if (sampleRate <= 0) {
        LOGE("beginProject() called before a sample rate is known -- "
             "call start() (live) or construct with an offline rate first");
        // Explicitly invalidate the builder rather than leaving it holding
        // whatever a prior successful begin()/build() left behind. mScore's
        // scalar fields (bpm, framesPerGridUnit) are plain copies, not
        // zeroed by build()'s std::move -- without this, a caller that
        // misorders beginProject() relative to start()/stop() (e.g. calling
        // it again after stop() and before the next start()) could silently
        // schedule new blocks against a stale, previous project's
        // framesPerGridUnit instead of being rejected by the
        // "framesPerGridUnit <= 0.0" guard in ScoreBuilder::addLoopBlock
        // (mandate 1). ScoreBuilder::begin() is the only path that should
        // ever mutate mScore, so route the invalidation through it too.
        mBuilder.begin(0, 0);
        return;
    }
    mBuilder.begin(bpm, sampleRate);
}

void AudioEngine::addTrack(int32_t slot) {
    mBuilder.addTrack(slot);
}

bool AudioEngine::addLoopBlock(
        int32_t trackSlot,
        const std::string &sampleAssetPath,
        int32_t startGridUnit,
        int32_t lengthGridUnits,
        float volume,
        int64_t trimStartMs,
        int64_t trimEndMs,
        float pitchSemitones) {
    if (mAssetManager == nullptr) {
        LOGE("addLoopBlock() called before init(AssetManager)");
        return false;
    }
    const int32_t sampleRate = getSampleRate();
    if (sampleRate <= 0) {
        LOGE("addLoopBlock() called before a sample rate is known");
        return false;
    }

    // Mandate 2: decode+resample off the audio thread, cached by asset path.
    std::shared_ptr<const SampleBuffer> sample = mSampleBank.getOrLoad(mAssetManager, sampleAssetPath, sampleRate);
    if (!sample) {
        LOGE("Failed to decode sample asset: %s", sampleAssetPath.c_str());
        return false;
    }

    const bool ok = mBuilder.addLoopBlock(
            trackSlot, sample, startGridUnit, lengthGridUnits, volume, trimStartMs, trimEndMs, pitchSemitones);
    if (!ok) {
        LOGE("Rejected loop block on track %d (asset %s) -- unknown track or degenerate parameters",
             trackSlot, sampleAssetPath.c_str());
    }
    return ok;
}

void AudioEngine::commitProject() {
    auto owned = std::make_unique<const PlaybackScore>(mBuilder.build());
    const PlaybackScore *raw = owned.get();

    {
        // Off-audio-thread only: retain ownership for the engine's whole
        // lifetime so the realtime callback can safely hold a bare pointer
        // to it with zero refcounting (see AudioEngine.h's mScore doc
        // comment). This mutex is never touched by onAudioReady/
        // renderOffline.
        std::lock_guard<std::mutex> lock(mRetiredScoresMutex);
        mRetiredScores.push_back(std::move(owned));
    }

    // Mandate 7: publish via a single atomic pointer swap, release-ordered;
    // the realtime callback picks this up with an acquire load. This is the
    // ONLY way a new score reaches onAudioReady/renderOffline.
    mScore.store(raw, std::memory_order_release);
}

oboe::DataCallbackResult AudioEngine::onAudioReady(
        oboe::AudioStream *audioStream,
        void *audioData,
        int32_t numFrames) {
    auto *floatData = static_cast<float *>(audioData);
    const int32_t channelCount = audioStream->getChannelCount();

    if (!mPlaying.load(std::memory_order_relaxed)) {
        // Deliberate, product-level divergence from mandate 6's literal
        // "incremented every single audio callback" wording: while paused,
        // the transport is intentionally NOT advanced, so the arrangement
        // position stays exactly where the user paused it (matches the
        // design spec's "play/pause the full arrangement" -- resuming must
        // not jump forward by however long the pause lasted). This does NOT
        // reintroduce a second per-block phase counter and does not weaken
        // mandate 6's core anti-drift guarantee: mTransportFrame remains the
        // single source of truth every block derives its position from
        // (mandate 6's derivation formula in renderScore is untouched); this
        // callback simply chooses, once per callback, whether that one
        // counter advances at all. See LivePlaybackPauseTest for a
        // regression test that pause actually freezes getCurrentFrame().
        std::memset(floatData, 0, sizeof(float) * static_cast<size_t>(numFrames) * static_cast<size_t>(channelCount));
        return oboe::DataCallbackResult::Continue;
    }

    // Mandate 7: exactly one atomic load of the current score, then a
    // read-only traversal + mixing + soft clip below -- no allocation, no
    // locking, no JNI, no file I/O anywhere in this call.
    const PlaybackScore *score = mScore.load(std::memory_order_acquire);
    const int64_t transportFrameStart = mTransportFrame.load(std::memory_order_relaxed);

    renderScore(score, transportFrameStart, numFrames, channelCount, floatData);

    // Mandate 6: the ONE master transport counter, advanced by exactly
    // numFrames every callback. Nothing else in this engine tracks time.
    mTransportFrame.fetch_add(numFrames, std::memory_order_relaxed);

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::renderOffline(int32_t numFrames, float *scratchBuffer) {
    const PlaybackScore *score = mScore.load(std::memory_order_acquire);
    const int64_t transportFrameStart = mTransportFrame.load(std::memory_order_relaxed);

    // Same function, same derivation path as onAudioReady (mandate 10) --
    // the test cares about internal position state, not this audio content.
    renderScore(score, transportFrameStart, numFrames, kChannelCount, scratchBuffer);

    mTransportFrame.fetch_add(numFrames, std::memory_order_relaxed);
}

const ResolvedLoopBlock *AudioEngine::findBlock(int32_t trackSlot, int32_t blockIndex) const {
    // Safe to read straight through the raw pointer with no lifetime dance:
    // committed scores are retained for the engine's whole lifetime (see
    // AudioEngine.h's mScore doc comment), so this pointer -- if non-null --
    // is always valid.
    const PlaybackScore *score = mScore.load(std::memory_order_acquire);
    if (!score) {
        return nullptr;
    }
    for (const ResolvedTrack &track : score->tracks) {
        if (track.slot == trackSlot) {
            if (blockIndex < 0 || static_cast<size_t>(blockIndex) >= track.blocks.size()) {
                return nullptr;
            }
            return &track.blocks[static_cast<size_t>(blockIndex)];
        }
    }
    return nullptr;
}

int64_t AudioEngine::testGetBlockStartFrame(int32_t trackSlot, int32_t blockIndex) const {
    const ResolvedLoopBlock *block = findBlock(trackSlot, blockIndex);
    return block ? block->blockStartFrame : -1;
}

int64_t AudioEngine::testGetLoopContentLengthFrames(int32_t trackSlot, int32_t blockIndex) const {
    const ResolvedLoopBlock *block = findBlock(trackSlot, blockIndex);
    return block ? block->loopContentLengthFrames : -1;
}

int64_t AudioEngine::testGetLoopLocalFrame(int32_t trackSlot, int32_t blockIndex) const {
    const ResolvedLoopBlock *block = findBlock(trackSlot, blockIndex);
    if (!block) {
        return -1;
    }
    const int64_t transportFrame = mTransportFrame.load(std::memory_order_relaxed);
    const int64_t framesSinceBlockStart = transportFrame - block->blockStartFrame;
    if (framesSinceBlockStart < 0 || framesSinceBlockStart >= block->blockLengthFrames) {
        return -1; // not currently active -- matches renderScore's own activity check
    }
    // Mandate 10: reuses the exact same nonNegativeMod helper renderScore
    // uses (see MixEngine.h) -- no separate/duplicated derivation formula.
    return nonNegativeMod(framesSinceBlockStart, block->loopContentLengthFrames);
}

} // namespace beatwave
