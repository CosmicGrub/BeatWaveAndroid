#ifndef BEATWAVE_AUDIO_ENGINE_H
#define BEATWAVE_AUDIO_ENGINE_H

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>
#include <android/asset_manager.h>

#include "PlaybackScore.h"
#include "SampleBank.h"
#include "ScoreBuilder.h"

namespace beatwave {

/**
 * The BeatWave native audio engine.
 *
 * Phase 0 gave this a silence-passthrough Oboe output stream, just to prove
 * the module builds/links/opens a stream. Phase 2 (this) adds real
 * multi-track, sample-accurate, drift-free loop mixing on top of that same
 * stream lifecycle -- see the numbered engineering mandates in the Phase 2
 * plan docs, most importantly mandate 6 (absolute-position derivation from
 * a single master transport counter, no per-block phase state) and
 * mandate 7 (onAudioReady does zero allocation/locking/IO/JNI).
 *
 * An AudioEngine instance runs in one of two modes:
 *  - "live" mode (default-constructed, driven via start()/stop()/
 *    onAudioReady): opens a real Oboe output stream and mixes into it.
 *  - "offline/test" mode (constructed with an explicit sample rate, driven
 *    via renderOffline()): never touches real audio hardware. Used by
 *    instrumented tests (mandate 10) to advance the transport and inspect
 *    internal scheduling state without a live output stream.
 * Both modes share the exact same scheduling (ScoreBuilder) and mixing
 * (renderScore, see MixEngine.h) code -- only "where do the mixed frames
 * end up, and does a real transport counter run against a real clock"
 * differs.
 *
 * Threading contract: beginProject/addTrack/addLoopBlock/commitProject (and
 * their offline-mode equivalents driven from JNI, see audio_engine_jni.cpp)
 * do asset decode/resample/heap-allocation and MUST be called from a
 * background thread only -- never the main/UI thread, and never from
 * onAudioReady. play/pause/stopTransport/seekToFrame/getCurrentFrame/
 * getSampleRate are cheap atomic ops, safe from any thread.
 */
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    AudioEngine() = default;

    /** Constructs an engine pre-configured for offline/test use at a fixed
     *  sample rate, never opening real audio hardware (mandate 10). */
    explicit AudioEngine(int32_t offlineSampleRateHz);

    /** Must be called once, off the audio thread, before any sample can be
     *  loaded (addLoopBlock decodes assets through this). */
    void init(AAssetManager *assetManager);

    // --- Real-hardware stream lifecycle (live mode only) ---
    bool start();
    void stop();

    // --- Transport controls (mandate 9) ---
    void play();
    void pause();
    /** Resets the transport counter to 0 (mandate 9's "stop()"). Named
     *  stopTransport() here to not collide with stop(), which closes the
     *  real hardware stream and predates Phase 2. */
    void stopTransport();
    void seekToFrame(int64_t frame);
    int64_t getCurrentFrame() const;
    /** The engine's real negotiated output sample rate: the live stream's
     *  (once opened) in live mode, or the fixed offline rate in test mode.
     *  0 if neither is known yet. */
    int32_t getSampleRate() const;

    // --- Schedule building (off the audio thread only -- mandate 7) ---
    void beginProject(int32_t bpm);
    void addTrack(int32_t slot);
    /** Triggers decode+resample+cache of sampleAssetPath (if not already
     *  cached) and schedules the resolved block. trimEndMs < 0 means "to
     *  the end of the sample". Returns false if the asset failed to load or
     *  the resulting block was degenerate. */
    bool addLoopBlock(
            int32_t trackSlot,
            const std::string &sampleAssetPath,
            int32_t startGridUnit,
            int32_t lengthGridUnits,
            float volume,
            int64_t trimStartMs,
            int64_t trimEndMs,
            float pitchSemitones);
    /** Finalizes and atomically publishes the new score to the realtime
     *  mixing path (mandate 7). */
    void commitProject();

    // --- Offline/test-only rendering (mandate 10) ---
    /** Renders numFrames through the exact same mix function the real
     *  callback uses, into scratchBuffer (interleaved, must hold at least
     *  numFrames*2 floats), and advances this engine's own transport
     *  counter by numFrames -- same derivation path as onAudioReady, always
     *  advancing (no play/pause gating; offline mode has no such concept). */
    void renderOffline(int32_t numFrames, float *scratchBuffer);

    // --- Test-only introspection (mandate 10) ---
    /** Returns -1 if trackSlot/blockIndex doesn't resolve to a scheduled block. */
    int64_t testGetBlockStartFrame(int32_t trackSlot, int32_t blockIndex) const;
    int64_t testGetLoopContentLengthFrames(int32_t trackSlot, int32_t blockIndex) const;
    /** Current loopLocalFrame for that block, derived fresh via the same
     *  nonNegativeMod formula MixEngine::renderScore uses (mandate 6), from
     *  this engine's current transport position. -1 if the block doesn't
     *  resolve or isn't currently active. */
    int64_t testGetLoopLocalFrame(int32_t trackSlot, int32_t blockIndex) const;

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> mStream;
    AAssetManager *mAssetManager = nullptr;
    int32_t mOfflineSampleRateHz = 0; // 0 => "live" mode, derive rate from mStream
    static constexpr int32_t kChannelCount = 2; // matches the live stream's fixed Stereo setup

    SampleBank mSampleBank;
    ScoreBuilder mBuilder; // staging area -- touched only off the audio thread

    // Mandate 7: the ONLY thing the realtime callback reads to get the
    // current schedule. A new score is published via a single atomic
    // *raw pointer* swap (release store / acquire load) -- NOT
    // std::atomic<std::shared_ptr<T>>: this NDK's libc++ does not implement
    // that partial specialization (it requires the wrapped type to be
    // trivially copyable, which shared_ptr never is, so instantiating it is
    // a hard compile error here). std::atomic<const PlaybackScore*> is a
    // plain pointer -- always lock-free on every ABI this app targets -- so
    // the callback's read is exactly the "one atomic load, then read-only
    // traversal, no allocation, no locking, ever" mandate 7 calls for, with
    // no refcounting overhead at all.
    //
    // Reclamation trade-off: because the callback holds only a raw
    // pointer (no shared ownership), a published PlaybackScore must stay
    // alive for as long as the audio thread could still be reading it. This
    // engine sidesteps that safely by never freeing a committed score at
    // all -- every one is retained in mRetiredScores for the engine's
    // whole lifetime and only released on destruction. commitProject() is a
    // rare, user-driven event (arrangement edits), not a per-frame one, so
    // this is a deliberate, bounded trade of a little retained memory for
    // zero locking/refcounting in the realtime path.
    std::atomic<const PlaybackScore *> mScore{nullptr};
    std::mutex mRetiredScoresMutex; // guards mRetiredScores -- only ever locked off the audio thread, from commitProject()
    std::vector<std::unique_ptr<const PlaybackScore>> mRetiredScores;

    // Mandate 6: the ONE master transport frame counter. Relaxed ordering
    // is fine (mandate 6 explicitly allows it) -- it's just a position, not
    // a synchronization point for other data.
    std::atomic<int64_t> mTransportFrame{0};
    std::atomic<bool> mPlaying{false};

    const ResolvedLoopBlock *findBlock(int32_t trackSlot, int32_t blockIndex) const;
};

} // namespace beatwave

#endif // BEATWAVE_AUDIO_ENGINE_H
