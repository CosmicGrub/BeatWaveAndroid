#ifndef BEATWAVE_SAMPLE_BANK_H
#define BEATWAVE_SAMPLE_BANK_H

#include <cstddef>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>
#include <android/asset_manager.h>

namespace beatwave {

/**
 * A decoded-and-resampled (to the engine's negotiated output sample rate)
 * sample, cached by source asset path. Built once off the audio thread
 * (mandate 2) and never mutated afterward -- safe to share via shared_ptr
 * across multiple loop blocks/tracks and to read concurrently from the
 * audio thread once published as part of a PlaybackScore.
 */
struct SampleBuffer {
    std::vector<float> interleaved;
    int32_t channelCount = 1;
    int32_t sampleRateHz = 0;

    int64_t frameCount() const {
        return channelCount > 0 ? static_cast<int64_t>(interleaved.size() / static_cast<size_t>(channelCount)) : 0;
    }
};

/**
 * Off-audio-thread cache of decoded+resampled sample buffers, keyed by
 * asset path. See mandate 2: decode + resample to the real negotiated
 * output sample rate happens exactly once per unique sample; the result is
 * reused for every loop block referencing it (even across different pitch/
 * trim/volume settings -- those are per-block and applied at mix time, not
 * baked into this cached buffer).
 *
 * POST-V1 AUDIT/UPGRADE D1 (2026-08-17 engine-upgrades backlog, "SampleBank
 * cache eviction"): bounded by a session-wide memory budget
 * (kDefaultMaxCacheBytes) with least-recently-used eviction on overflow,
 * rather than growing for the whole process lifetime the way it did before
 * this pass -- clear() had been defined but had zero call sites anywhere in
 * the codebase, so nothing ever actually released a cached entry. Evicting a
 * cache ENTRY here is always safe regardless of whether the underlying
 * SampleBuffer is still audible: every ResolvedLoopBlock captures its own
 * shared_ptr<const SampleBuffer> at schedule-build time (see
 * ScoreBuilder/PlaybackScore), so dropping this cache's own reference only
 * stops FUTURE getOrLoad calls for that asset path from reusing the buffer
 * -- it never frees memory a live/retained PlaybackScore (see AudioEngine.h's
 * mRetiredScores) still needs, and never has to touch mScore/mRetiredScores
 * at all.
 *
 * NEVER call getOrLoad from AudioEngine::onAudioReady or anything it
 * transitively calls -- it does file I/O, heap allocation, and (on a cache
 * miss) decode+resample work.
 */
class SampleBank {
public:
    /**
     * assetPath is dispatched by shape: a leading '/' means an absolute
     * filesystem path (e.g. an imported sample copied into app-private
     * storage under filesDir -- decoded via WavDecoder::decodeFile and
     * never touching assetManager), anything else is treated as an
     * AAssetManager-relative bundled asset path (decoded via
     * WavDecoder::decodeAsset, e.g. "loops/kick_basic_01.wav"). Either way
     * the cache below is keyed by the raw path string as given -- the two
     * shapes can never collide since bundled asset paths never start with
     * '/'.
     */
    std::shared_ptr<const SampleBuffer> getOrLoad(
            AAssetManager *assetManager,
            const std::string &assetPath,
            int32_t targetSampleRateHz);

    void clear();

    /** Post-v1 audit D1: overrides the eviction budget. Production code
     *  never calls this -- it exists purely so an instrumented test can
     *  force real LRU eviction deterministically with a handful of small
     *  bundled loop assets instead of needing to load hundreds of megabytes
     *  of real audio to cross the production default. Re-runs eviction
     *  immediately against the new budget. Off-audio-thread only, like every
     *  other SampleBank method. */
    void setMaxCacheBytes(size_t maxCacheBytes);

    /** Total bytes currently held by cached SampleBuffers (sum of each
     *  entry's interleaved float vector size). Off-audio-thread only. */
    size_t currentCacheBytes() const;

    /** Number of distinct assets currently cached. Off-audio-thread only. */
    size_t entryCount() const;

    /**
     * Post-v1 audit D1: the default eviction budget -- 256 MiB of resampled
     * interleaved float32 PCM. Chosen to comfortably hold AudioImporter's
     * own worst-case single import (DEFAULT_MAX_DECODED_PCM_BYTES = 96 MiB
     * of 16-bit PCM in AudioImporter.kt, which becomes roughly double that
     * as resampled float32 -- 4 bytes/sample vs. 2) with real headroom left
     * over for several smaller bundled/imported samples on top of it, while
     * still bounding a long session's total native cache growth across many
     * project switches -- the actual gap this audit closes.
     */
    static constexpr size_t kDefaultMaxCacheBytes = 256ULL * 1024 * 1024;

private:
    struct CacheEntry {
        std::shared_ptr<const SampleBuffer> buffer;
        size_t byteSize = 0;
        uint64_t lastAccessTick = 0;
    };

    /** Evicts least-recently-used entries (by lastAccessTick) while
     *  mCurrentCacheBytes exceeds mMaxCacheBytes -- but never below one
     *  entry: a single asset larger than the whole budget on its own (e.g.
     *  a near-cap AudioImporter import) still gets to occupy the cache alone
     *  rather than being pointlessly re-decoded on every single getOrLoad
     *  call for it. The entry most recently inserted/touched always holds
     *  the freshest (highest) lastAccessTick, so this can only ever select
     *  it once every other entry is already gone -- which the size() > 1
     *  guard below excludes. Caller must already hold mMutex. */
    void evictIfOverBudgetLocked();

    mutable std::mutex mMutex; // only ever locked off the audio thread
    std::unordered_map<std::string, CacheEntry> mCache;
    size_t mCurrentCacheBytes = 0;
    size_t mMaxCacheBytes = kDefaultMaxCacheBytes;
    uint64_t mAccessCounter = 0;
};

} // namespace beatwave

#endif // BEATWAVE_SAMPLE_BANK_H
