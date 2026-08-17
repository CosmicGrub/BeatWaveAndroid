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

    /** Phase 5: ensures a still-open input (recording) stream is stopped and
     *  closed if the engine is destroyed while a recording happens to still
     *  be active (e.g. app teardown without an explicit stopRecording()).
     *  Mirrors stop()'s existing requestStop()/close() pattern for mStream,
     *  applied to the input stream. Never runs on the audio thread. */
    ~AudioEngine() override;

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
     *  advancing (no play/pause gating; offline mode has no such concept).
     *  Phase 5 (mandate 8): if a test recording is currently active (see
     *  testStartRecording() below), this ALSO feeds silence through the
     *  exact same captureRecordingFrames() derivation the live callback
     *  uses, so an offline/instrumented test can exercise the real
     *  recording-position math deterministically without real hardware. */
    void renderOffline(int32_t numFrames, float *scratchBuffer);

    // --- Recording (Phase 5) ---
    //
    // Full-duplex capture against this SAME live engine instance, driven
    // from inside the existing output callback (see AudioEngine.cpp's
    // onAudioReady and the Phase 5 design doc's mandate 1-2): the input
    // stream is opened lazily, only on the first startRecording() call, in
    // BLOCKING mode (no data callback) so onAudioReady can read() from it
    // synchronously once per callback -- this is Oboe's own documented
    // pattern for output-callback-driven full-duplex capture (see
    // oboe::FullDuplexStream, which does exactly this: read() on the input
    // stream from inside the output stream's onAudioReady). Recording
    // position is derived fresh every callback from the SAME absolute
    // mTransportFrame counter mandate 6 already maintains for playback
    // (mandate 4: recordFrameIndex = transportFrame - recordingStartFrame),
    // so a recorded take is time-aligned with whatever is already playing
    // by construction.

    /** Begins capture: lazily opens a blocking-mode input stream matching
     *  the output stream's format as closely as the device allows,
     *  pre-allocates a fixed-capacity recording buffer (mandate 3, sized for
     *  maxRecordingSeconds at the engine's negotiated sample rate -- see
     *  beginRecordingCommon()'s doc comment on why this is caller-supplied
     *  rather than a native-side constant), captures the current transport
     *  frame as the recording's start position (mandate 4), and starts
     *  transport playback if it isn't already running (mandate 7 -- reuses
     *  play(), does not duplicate its logic). Returns false if a recording
     *  is already in progress, the output sample rate isn't known yet
     *  (start() hasn't been called), or the input stream fails to open
     *  (e.g. RECORD_AUDIO not granted, no usable input device).
     *  Off-audio-thread only -- opens a stream and allocates. */
    bool startRecording(int32_t maxRecordingSeconds);

    /** Stops further capture, closes the input stream, and writes the valid
     *  captured portion of the recording buffer out as a canonical 16-bit
     *  PCM RIFF/WAVE file at outputFilePath (mandate 5, via WavWriter).
     *  Returns the number of frames written, or -1 on failure (including
     *  "wasn't recording" / "nothing was ever captured"). Off-audio-thread
     *  only -- closes a stream and does file I/O. */
    int64_t stopRecording(const std::string &outputFilePath);

    bool isRecording() const;

    /** The absolute transport frame recording began at (mandate 4/6) --
     *  meaningful once startRecording() has succeeded; -1 before that. */
    int64_t getRecordingStartFrame() const;

    /** Live progress: frames captured so far in the current (or most
     *  recently finished) recording. Safe to poll from any thread the same
     *  way getCurrentFrame() already is (see its doc comment on the
     *  atomic-publish pattern this relies on for lock-free safety). */
    int64_t getRecordedFrameCount() const;

    /** oboe::AudioStream::calculateLatencyMillis() on the currently-open
     *  input stream, or -1.0 if it isn't open or the query failed. Per
     *  Oboe's own header docs this should NOT be called from a data
     *  callback -- this is an off-audio-thread-only query, safe from any
     *  other thread. */
    double getInputLatencyMillis() const;

    /** Same as getInputLatencyMillis() but for the output stream. */
    double getOutputLatencyMillis() const;

    /** True once the current (or most recently finished) recording hit the
     *  caller-supplied maxRecordingSeconds pre-allocated buffer cap and
     *  capture was stopped (mandate 3). The Kotlin side should poll this
     *  the same way it polls getRecordedFrameCount() and, on seeing it
     *  become true, auto-stop the recording gracefully with whatever was
     *  captured. Reset to false at the start of the next startRecording()/
     *  testStartRecording(). */
    bool isRecordingCapReached() const;

    // --- Offline/test-only recording (mandate 8) ---
    /** Simulates starting a recording at this offline engine's CURRENT
     *  simulated transport frame (not necessarily grid-aligned) -- shares
     *  the exact same buffer-allocation/start-frame-capture logic as
     *  startRecording(), just never opens a real Oboe input stream. Pair
     *  with renderOffline() (which feeds silence through the same
     *  derivation function while a test recording is active) and
     *  testStopRecording(). */
    void testStartRecording(int32_t maxRecordingSeconds);

    /** Finalizes a simulated recording via the exact same WAV-writing code
     *  path as stopRecording(). */
    int64_t testStopRecording(const std::string &outputFilePath);

    // --- Test-only introspection (mandate 10) ---
    /** Returns -1 if trackSlot/blockIndex doesn't resolve to a scheduled block. */
    int64_t testGetBlockStartFrame(int32_t trackSlot, int32_t blockIndex) const;
    int64_t testGetLoopContentLengthFrames(int32_t trackSlot, int32_t blockIndex) const;
    /** Current loopLocalFrame for that block, derived fresh via the same
     *  nonNegativeMod formula MixEngine::renderScore uses (mandate 6), from
     *  this engine's current transport position. -1 if the block doesn't
     *  resolve or isn't currently active. */
    int64_t testGetLoopLocalFrame(int32_t trackSlot, int32_t blockIndex) const;

    /** Post-v1 audit D1 (SampleBank cache eviction): overrides the sample
     *  cache's eviction budget so an instrumented test can force real LRU
     *  eviction deterministically with only a handful of small bundled loop
     *  assets, instead of needing to load hundreds of megabytes of real
     *  audio to cross the production default (see SampleBank::
     *  kDefaultMaxCacheBytes). Production code never calls this.
     *  Off-audio-thread only. */
    void testSetSampleBankMaxCacheBytes(int64_t maxCacheBytes);

    /** Total bytes currently held by the sample cache. Off-audio-thread only. */
    int64_t testGetSampleBankCacheBytes() const;

    /** Number of distinct sample assets currently cached. Off-audio-thread only. */
    int32_t testGetSampleBankCacheEntryCount() const;

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

    // --- Phase 5: recording state ---
    //
    // mInputStream (ownership, RAII-closed) is touched only off the audio
    // thread (startRecording/stopRecording/destructor) -- exactly like
    // mStream above. Unlike mStream, though, onAudioReady itself must read
    // the CURRENT input stream to call read() on it (Oboe hands the output
    // stream in as a callback parameter, but there is no equivalent
    // parameter for an independently-opened input stream) -- so a plain
    // shared_ptr read from the audio thread while stopRecording() might
    // concurrently reset() the SAME shared_ptr object on another thread
    // would be a data race on the shared_ptr object itself (distinct from,
    // and in addition to, the underlying oboe::AudioStream's own thread
    // safety). mInputStreamPtr sidesteps exactly that hazard the same way
    // mScore sidesteps it for PlaybackScore: publish a plain atomic raw
    // pointer (release store after mInputStream is fully assigned / after
    // requestStart() succeeds; release nullptr store before closing) that
    // the audio thread only ever acquire-loads and dereferences, never
    // copies or mutates. NOTE: closing the underlying stream while
    // onAudioReady might concurrently be mid-read() is NOT the same safe
    // situation as the OUTPUT stream's stop() (called from a different
    // thread than the callback) -- Oboe's requestStop()/close() are
    // cross-thread-safe with respect to a stream's OWN registered data
    // callback (Oboe has bookkeeping for that -- see kMinDelayBeforeCloseMillis
    // in Oboe's own AudioStream), but mInputStream has no data callback of
    // its own; the "caller" of its read() is a foreign thread (the output
    // stream's callback) Oboe knows nothing about, so it gets none of that
    // protection. See mInputReadInFlight below for the real quiescence
    // mechanism this engine uses to make closing mInputStream safe.
    std::shared_ptr<oboe::AudioStream> mInputStream;
    std::atomic<oboe::AudioStream *> mInputStreamPtr{nullptr};

    // Publishing mInputStreamPtr=nullptr (see stopRecording()/~AudioEngine())
    // only prevents a FUTURE onAudioReady invocation from starting a new
    // input->read() -- it does nothing about a read() call that had already
    // begun (the audio thread loaded the old, still-valid pointer moments
    // earlier and is currently mid-call inside AudioStream::read()) at the
    // exact moment the other thread proceeds to requestStop()/close() the
    // same stream. Unlike mStream's teardown (safe because Oboe itself owns
    // and synchronizes against ITS OWN registered data callback), Oboe has
    // no bookkeeping at all for this ad hoc synchronous read() made by a
    // foreign thread (the output stream's callback) -- requestStop()/close()
    // give no cross-thread safety guarantee for that case. mInputReadInFlight
    // is a real quiescence signal: onAudioReady sets it true BEFORE loading
    // mInputStreamPtr and clears it AFTER the read() call (or after finding
    // the pointer already null) completes; stopRecording()/~AudioEngine()
    // spin-wait on it going false -- AFTER unpublishing mInputStreamPtr, so
    // no new read() can start, and BEFORE close(), so no still-in-flight
    // read() can be mid-call when close() runs. Since onAudioReady is Oboe's
    // single serialized callback for mStream, only one thread ever sets/
    // clears this flag, so a plain bool (not a counter) suffices.
    std::atomic<bool> mInputReadInFlight{false};

    /** Blocks (briefly) until any input->read() call already in flight on
     *  the audio thread has finished -- see mInputReadInFlight's doc
     *  comment. MUST be called after mInputStreamPtr has been unpublished
     *  (store nullptr) and before the input stream is closed. Off-audio-
     *  thread only. Bounded so a stuck/dead audio thread can't hang the
     *  caller forever. */
    void waitForInputReadQuiescence();

    // Post-v1 audit/bugfix B1: the recording cap used to be this same
    // native-side constant, hardcoded at 180s ("~3 minutes, matches the
    // design spec's own max song length") while the REAL app-level song
    // length cap (GridConstants.MAX_SONG_LENGTH_SECONDS, Kotlin) was 240s --
    // a 25% undershoot that silently auto-stopped recordings a full minute
    // before the app's own advertised limit. Fixed by making the caller
    // (Kotlin) supply the real cap explicitly at startRecording()/
    // testStartRecording() time instead of duplicating it here, so the two
    // can never drift apart again. This constant is now only a defensive
    // sanity ceiling -- clamped against in beginRecordingCommon() -- against
    // a pathological/garbage caller-supplied value (e.g. an accidental
    // future GridConstants change) causing a runaway allocation; 20 minutes
    // is a generous multiple of any currently-planned song length cap and is
    // not expected to ever legitimately bind.
    static constexpr int32_t kRecordingCapacitySafetyCeilingSeconds = 1200;
    static constexpr int32_t kMaxInputScratchFrames = 4096; // generous vs. real Oboe callback burst sizes; pre-allocated, never resized in the callback

    std::atomic<bool> mRecording{false};
    std::atomic<int64_t> mRecordingStartFrame{-1};
    std::atomic<int64_t> mRecordedFrameCount{0};
    std::atomic<bool> mRecordingCapReached{false};
    int64_t mRecordingCapacityFrames = 0; // set only off the audio thread, before mRecording's release store -- see beginRecordingCommon()

    std::vector<float> mRecordingBuffer;    // capacityFrames * kChannelCount, pre-allocated/zeroed in beginRecordingCommon()
    std::vector<float> mInputScratchBuffer; // kMaxInputScratchFrames * kChannelCount, pre-allocated in startRecording(); onAudioReady's read() target

    /** Shared by startRecording() and testStartRecording() (mandate 8):
     *  pre-allocates/zeroes mRecordingBuffer (sized for maxRecordingSeconds,
     *  clamped to (0, kRecordingCapacitySafetyCeilingSeconds] -- see B1's
     *  doc comment above kRecordingCapacitySafetyCeilingSeconds), resets the
     *  recording counters, captures the current transport frame as
     *  mRecordingStartFrame (mandate 4), and publishes mRecording=true last
     *  (release). Does NOT touch mInputStream/mInputStreamPtr -- callers
     *  handle the real-vs-test input source themselves. Off-audio-thread
     *  only. */
    void beginRecordingCommon(int32_t maxRecordingSeconds);

    /** Shared by stopRecording() and testStopRecording(): acquire-loads the
     *  published frame count (happens-before paired with
     *  captureRecordingFrames' release store, see its doc comment) and, if
     *  non-zero, writes it out via WavWriter. Returns frames written or -1.
     *  Off-audio-thread only. */
    int64_t finishRecordingCommon(const std::string &outputFilePath);

    /** THE shared recording-position derivation (mandate 4, mirroring
     *  mandate 6): used identically by the real live callback (fed real
     *  captured input) and the offline/test path (fed silence, mandate 8) --
     *  see AudioEngine.cpp for the full derivation/cap-handling logic.
     *  inputInterleaved may be null (offline/test path); only the first
     *  validInputFrames of numFrames are read from it, the rest are written
     *  as explicit silence. Real-time-safe: zero allocation, zero locking,
     *  called from onAudioReady with recording active. */
    void captureRecordingFrames(
            int64_t transportFrameStart,
            int32_t numFrames,
            int32_t validInputFrames,
            const float *inputInterleaved,
            int32_t inputChannelCount);
};

} // namespace beatwave

#endif // BEATWAVE_AUDIO_ENGINE_H
