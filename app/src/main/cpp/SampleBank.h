#ifndef BEATWAVE_SAMPLE_BANK_H
#define BEATWAVE_SAMPLE_BANK_H

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

private:
    std::mutex mMutex; // only ever locked off the audio thread
    std::unordered_map<std::string, std::shared_ptr<const SampleBuffer>> mCache;
};

} // namespace beatwave

#endif // BEATWAVE_SAMPLE_BANK_H
