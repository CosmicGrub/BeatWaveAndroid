#include "AudioEngine.h"

#include <algorithm>
#include <android/log.h>
#include <chrono>
#include <cstring>
#include <thread>

#include "MixEngine.h"
#include "WavWriter.h"

#define TAG "BeatWaveAudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace beatwave {

namespace {
// Bounded spin-wait parameters for waitForInputReadQuiescence(): a real
// output-callback period is a few milliseconds, so polling every 200us
// and capping at 2000 iterations (~400ms) is generous headroom while still
// guaranteeing a stuck/dead audio thread can't hang the caller forever.
constexpr auto kQuiescencePollInterval = std::chrono::microseconds(200);
constexpr int kQuiescenceMaxPolls = 2000;
} // namespace

AudioEngine::AudioEngine(int32_t offlineSampleRateHz)
        : mOfflineSampleRateHz(offlineSampleRateHz) {}

AudioEngine::~AudioEngine() {
    // Best-effort cleanup if a recording was somehow still active when the
    // engine is destroyed. Never runs on the audio thread (nothing here is
    // called from onAudioReady). Unpublish, THEN wait for quiescence, THEN
    // close -- see mInputReadInFlight's doc comment for why the wait is
    // required and not just the unpublish (a data race / use-after-free
    // hazard on close() otherwise).
    if (mInputStream) {
        mInputStreamPtr.store(nullptr, std::memory_order_release);
        waitForInputReadQuiescence();
        mInputStream->requestStop();
        mInputStream->close();
        mInputStream.reset();
    }
}

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

    // Phase 5, mandate 1: while a recording is active, read exactly once
    // from the (already-open, blocking-mode) input stream for this
    // callback's numFrames and feed the result through the same
    // captureRecordingFrames derivation the offline/test path uses (mandate
    // 8). Skipped entirely when not recording -- the input stream is never
    // opened or read otherwise (lazy-open contract, see startRecording()).
    if (mRecording.load(std::memory_order_acquire)) {
        // Publish "a read may be in flight" BEFORE loading mInputStreamPtr,
        // and only clear it after the read (or the null-pointer check) is
        // fully done -- this is the quiescence signal stopRecording()/
        // ~AudioEngine() wait on before they close() the stream (see
        // mInputReadInFlight's doc comment in AudioEngine.h). Must bracket
        // the pointer load too, not just the read() call: otherwise the
        // window between loading a still-valid pointer and setting the flag
        // would be unprotected.
        mInputReadInFlight.store(true, std::memory_order_release);
        oboe::AudioStream *input = mInputStreamPtr.load(std::memory_order_acquire);
        if (input != nullptr) {
            const int32_t inputChannelCount = input->getChannelCount();
#ifndef NDEBUG
            if (numFrames > kMaxInputScratchFrames) {
                // Nit-level guard, debug builds only: an unconditional LOGE
                // here would itself be real-time I/O in the release-build
                // callback (the exact class of bug the sibling findings on
                // this same block are about) for a condition not expected on
                // any real device/perf-mode this app targets (observed
                // framesPerBurst=960 at 48kHz vs. kMaxInputScratchFrames=
                // 4096) -- but Oboe/AAudio doesn't strictly guarantee a
                // callback's numFrames can never exceed that under
                // scheduling pressure, so surface a real occurrence during
                // development rather than letting dropped input silently
                // masquerade as "the mic went quiet" in a real recording
                // (see captureRecordingFrames' silence-fill fallback below).
                LOGE("onAudioReady: numFrames (%d) exceeds kMaxInputScratchFrames (%d) -- "
                     "%d input frames this callback will be captured as silence",
                     numFrames, kMaxInputScratchFrames, numFrames - kMaxInputScratchFrames);
            }
#endif
            const int32_t framesToRead = std::min(numFrames, kMaxInputScratchFrames);
            // Zero timeout -- matches Oboe's own FullDuplexStream::readInput()
            // reference implementation exactly (see oboe/FullDuplexStream.h:
            // "getInputStream()->read(mInputBuffer.get(), numFrames,
            // 0 /* timeout */)"). A non-zero timeout would block THIS
            // real-time OUTPUT callback thread for up to that duration if the
            // input stream is even momentarily short on buffered frames
            // (e.g. the first few callbacks after startRecording() opens the
            // input stream, before input/output reach equilibrium) --
            // exactly the class of bug mandate 7 ("zero locking/IO in the
            // callback") exists to prevent, and it would also widen the
            // use-after-free race window this quiescence mechanism closes. A
            // short/zero read is not an error: captureRecordingFrames()
            // already treats "fewer than numFrames valid" as "no new input
            // this callback" and fills the gap with explicit silence.
            oboe::ResultWithValue<int32_t> result =
                    input->read(mInputScratchBuffer.data(), framesToRead, 0 /* timeoutNanoseconds */);
            const int32_t validInputFrames = result ? std::max(0, result.value()) : 0;
            captureRecordingFrames(
                    transportFrameStart, numFrames, validInputFrames,
                    mInputScratchBuffer.data(), inputChannelCount);
        }
        mInputReadInFlight.store(false, std::memory_order_release);
    }

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

    // Phase 5, mandate 8: if a test recording is active (testStartRecording()
    // was called), feed silence through the exact same captureRecordingFrames
    // derivation the live callback uses -- no real hardware, no separate
    // implementation of the position math to audit.
    if (mRecording.load(std::memory_order_acquire)) {
        captureRecordingFrames(transportFrameStart, numFrames, /*validInputFrames=*/0, /*inputInterleaved=*/nullptr, /*inputChannelCount=*/0);
    }

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

// ============================================================================
// Phase 5: recording
// ============================================================================

void AudioEngine::beginRecordingCommon() {
    const int32_t sampleRate = getSampleRate();
    const int64_t capacityFrames = static_cast<int64_t>(sampleRate) * static_cast<int64_t>(kMaxRecordingSeconds);

    // Mandate 3: pre-allocate (and re-zero, so a reused buffer from a prior
    // take never leaks stale samples into gaps of THIS take -- see
    // captureRecordingFrames' doc comment on why unwritten indices must read
    // back as silence) here, off the audio thread, once per recording start
    // -- never touched again until the NEXT startRecording()/
    // testStartRecording() call.
    mRecordingBuffer.assign(static_cast<size_t>(capacityFrames) * static_cast<size_t>(kChannelCount), 0.0f);
    mRecordingCapacityFrames = capacityFrames;

    mRecordingCapReached.store(false, std::memory_order_relaxed);
    mRecordedFrameCount.store(0, std::memory_order_relaxed);
    // Mandate 4: recordingStartFrame is itself just a read of the SAME
    // absolute transport counter mandate 6 already maintains -- no separate
    // clock, no separate synchronization problem.
    mRecordingStartFrame.store(mTransportFrame.load(std::memory_order_relaxed), std::memory_order_relaxed);

    // Published last, release-ordered: onAudioReady/renderOffline only ever
    // acquire-load mRecording, so everything above is guaranteed visible to
    // them once they observe it flip to true.
    mRecording.store(true, std::memory_order_release);
}

bool AudioEngine::startRecording() {
    if (mRecording.load(std::memory_order_relaxed)) {
        LOGE("startRecording() called while a recording is already in progress");
        return false;
    }
    const int32_t sampleRate = getSampleRate();
    if (sampleRate <= 0) {
        LOGE("startRecording() called before a sample rate is known -- call start() first");
        return false;
    }

    // Mandate 1: lazily open the input stream -- only here, only on the
    // first (or first-since-the-last-stop) startRecording() call, never at
    // init()/start() time. BLOCKING mode (no setDataCallback()) so
    // onAudioReady can read() from it synchronously.
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
            ->setSharingMode(oboe::SharingMode::Exclusive)
            ->setFormat(oboe::AudioFormat::Float)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(sampleRate);

    std::shared_ptr<oboe::AudioStream> inputStream;
    oboe::Result result = builder.openStream(inputStream);
    if (result != oboe::Result::OK) {
        LOGE("Failed to open input stream: %s", oboe::convertToText(result));
        return false;
    }

    result = inputStream->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("Failed to start input stream: %s", oboe::convertToText(result));
        inputStream->close();
        return false;
    }

    // Pre-allocated once, off the audio thread, before publishing -- the
    // callback only ever reads into this, never resizes it (mandate 7).
    mInputScratchBuffer.assign(static_cast<size_t>(kMaxInputScratchFrames) * static_cast<size_t>(kChannelCount), 0.0f);

    // mInputStream (ownership) assigned, THEN mInputStreamPtr published,
    // BOTH before beginRecordingCommon()'s mRecording release store below --
    // see AudioEngine.h's doc comment on why the input stream needs a
    // distinct atomic raw pointer rather than a plain shared_ptr read from
    // the audio thread. Sequencing this before beginRecordingCommon() means
    // a single acquire-load of mRecording==true on the audio thread is
    // sufficient to safely observe mInputStreamPtr too (transitively, via
    // mRecording's own release store) -- one clean publish point rather than
    // two independent ones the audio thread would have to reason about.
    mInputStream = inputStream;
    mInputStreamPtr.store(mInputStream.get(), std::memory_order_release);

    beginRecordingCommon();

    // Mandate 7: reuse play()'s existing logic rather than duplicating it --
    // starting a recording also starts (or continues) transport playback.
    play();

    return true;
}

void AudioEngine::waitForInputReadQuiescence() {
    // MUST be called after mInputStreamPtr has already been unpublished
    // (store nullptr) by the caller -- see mInputReadInFlight's doc comment
    // in AudioEngine.h. Bounded spin-wait: this runs off the audio thread,
    // in a rare, user-driven event (stopping a recording / tearing down the
    // engine), not a hot path, so a short sleep-based poll is an acceptable
    // trade for not needing a condition variable the real-time audio thread
    // would have to touch.
    for (int i = 0; i < kQuiescenceMaxPolls; ++i) {
        if (!mInputReadInFlight.load(std::memory_order_acquire)) {
            return;
        }
        std::this_thread::sleep_for(kQuiescencePollInterval);
    }
    LOGE("waitForInputReadQuiescence() timed out after %dus x %d polls -- "
         "proceeding to close() the input stream anyway",
         static_cast<int>(kQuiescencePollInterval.count()), kQuiescenceMaxPolls);
}

int64_t AudioEngine::stopRecording(const std::string &outputFilePath) {
    if (!mRecording.exchange(false, std::memory_order_acq_rel)) {
        LOGE("stopRecording() called while no recording was in progress");
        return -1;
    }

    // Unpublish first so no callback that hasn't already grabbed the
    // pointer will start a new read() on a stream we're about to close.
    mInputStreamPtr.store(nullptr, std::memory_order_release);

    // Then wait for any read() the audio thread had ALREADY started (having
    // loaded the pointer before the unpublish above) to actually finish --
    // unlike mStream's teardown, Oboe gives no cross-thread safety guarantee
    // for a foreign thread's ad hoc read() on a stream with no data callback
    // of its own (see mInputReadInFlight's doc comment in AudioEngine.h).
    // Only once this returns is it safe to close().
    waitForInputReadQuiescence();

    if (mInputStream) {
        mInputStream->requestStop();
        mInputStream->close();
        mInputStream.reset();
    }

    return finishRecordingCommon(outputFilePath);
}

bool AudioEngine::isRecording() const {
    return mRecording.load(std::memory_order_relaxed);
}

int64_t AudioEngine::getRecordingStartFrame() const {
    return mRecordingStartFrame.load(std::memory_order_relaxed);
}

int64_t AudioEngine::getRecordedFrameCount() const {
    return mRecordedFrameCount.load(std::memory_order_acquire);
}

double AudioEngine::getInputLatencyMillis() const {
    oboe::AudioStream *input = mInputStreamPtr.load(std::memory_order_acquire);
    if (input == nullptr) {
        return -1.0;
    }
    oboe::ResultWithValue<double> result = input->calculateLatencyMillis();
    return result ? result.value() : -1.0;
}

double AudioEngine::getOutputLatencyMillis() const {
    if (!mStream) {
        return -1.0;
    }
    oboe::ResultWithValue<double> result = mStream->calculateLatencyMillis();
    return result ? result.value() : -1.0;
}

bool AudioEngine::isRecordingCapReached() const {
    return mRecordingCapReached.load(std::memory_order_relaxed);
}

void AudioEngine::testStartRecording() {
    // Mandate 8: same pre-allocation/start-frame-capture logic as
    // startRecording(), just never opens a real input stream -- mInputStream
    // / mInputStreamPtr stay null, so onAudioReady is irrelevant here; this
    // offline engine is driven via renderOffline() instead.
    beginRecordingCommon();
}

int64_t AudioEngine::testStopRecording(const std::string &outputFilePath) {
    if (!mRecording.exchange(false, std::memory_order_acq_rel)) {
        LOGE("testStopRecording() called while no test recording was in progress");
        return -1;
    }
    return finishRecordingCommon(outputFilePath);
}

int64_t AudioEngine::finishRecordingCommon(const std::string &outputFilePath) {
    // Acquire load: happens-before paired with captureRecordingFrames'
    // release store (see its doc comment) -- guarantees every write to
    // mRecordingBuffer[0, frameCount) already happened-before this load, so
    // reading that range here (from a thread that isn't the audio thread) is
    // safe with zero locking.
    const int64_t frameCount = mRecordedFrameCount.load(std::memory_order_acquire);
    if (frameCount <= 0) {
        return -1;
    }
    const bool ok = WavWriter::writeFile(outputFilePath, mRecordingBuffer.data(), frameCount, kChannelCount, getSampleRate());
    return ok ? frameCount : -1;
}

void AudioEngine::captureRecordingFrames(
        int64_t transportFrameStart,
        int32_t numFrames,
        int32_t validInputFrames,
        const float *inputInterleaved,
        int32_t inputChannelCount) {
    if (mRecordingCapReached.load(std::memory_order_relaxed)) {
        return; // mandate 3: already hit the cap -- stop capturing, don't crash, don't reallocate
    }

    const int64_t recordingStartFrame = mRecordingStartFrame.load(std::memory_order_relaxed);
    const int64_t capacityFrames = mRecordingCapacityFrames;
    int64_t highWaterMark = mRecordedFrameCount.load(std::memory_order_relaxed);
    bool capHit = false;

    for (int32_t i = 0; i < numFrames; ++i) {
        // Mandate 4 -- mirrors mandate 6's core derivation EXACTLY: every
        // frame's recording-buffer index is derived fresh, every callback,
        // from the SAME single absolute transport counter driving playback
        // (transportFrameStart + i) minus the fixed recordingStartFrame
        // captured once at start. No separately incrementally-advanced
        // per-recording counter is ever stored.
        const int64_t transportFrame = transportFrameStart + i;
        const int64_t recordFrameIndex = transportFrame - recordingStartFrame;
        if (recordFrameIndex < 0) {
            continue; // recording hadn't started yet at this frame
        }
        if (recordFrameIndex >= capacityFrames) {
            capHit = true;
            break;
        }

        float *dst = mRecordingBuffer.data() + static_cast<size_t>(recordFrameIndex) * static_cast<size_t>(kChannelCount);
        if (i < validInputFrames && inputInterleaved != nullptr && inputChannelCount > 0) {
            const float *src = inputInterleaved + static_cast<size_t>(i) * static_cast<size_t>(inputChannelCount);
            for (int32_t ch = 0; ch < kChannelCount; ++ch) {
                const int32_t srcCh = (inputChannelCount <= 1) ? 0 : (ch % inputChannelCount);
                dst[ch] = src[srcCh];
            }
        } else {
            // Offline/test path (mandate 8), or a live callback where the
            // input read came up short this callback (e.g. a momentary
            // underrun) -- explicit silence rather than leaving whatever was
            // last in the buffer, so the recording's timeline still
            // corresponds 1:1 to elapsed transport frames even across a gap.
            for (int32_t ch = 0; ch < kChannelCount; ++ch) {
                dst[ch] = 0.0f;
            }
        }

        if (recordFrameIndex + 1 > highWaterMark) {
            highWaterMark = recordFrameIndex + 1;
        }
    }

    if (highWaterMark > mRecordedFrameCount.load(std::memory_order_relaxed)) {
        // Publish via release -- mirrors mScore's atomic-publish pattern
        // (see AudioEngine.h's mScore doc comment): every mRecordingBuffer
        // write for index < highWaterMark happens-before this store, so any
        // thread that acquire-loads mRecordedFrameCount and observes a value
        // >= highWaterMark is guaranteed to see fully-written data for those
        // indices -- this is exactly what lets stopRecording()/
        // testStopRecording() read the buffer lock-free from off the audio
        // thread (see finishRecordingCommon()).
        mRecordedFrameCount.store(highWaterMark, std::memory_order_release);
    }
    if (capHit) {
        mRecordingCapReached.store(true, std::memory_order_relaxed);
    }
}

} // namespace beatwave
