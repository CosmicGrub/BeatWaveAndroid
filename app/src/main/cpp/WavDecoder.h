#ifndef BEATWAVE_WAV_DECODER_H
#define BEATWAVE_WAV_DECODER_H

#include <cstdint>
#include <string>
#include <vector>
#include <android/asset_manager.h>

namespace beatwave {

/** Fully-decoded PCM audio at its file's native sample rate/channel count,
 *  converted to float32 in [-1, 1] and interleaved by channel. */
struct DecodedPcm {
    std::vector<float> interleaved;
    int32_t channelCount = 1;
    int32_t sampleRateHz = 0;
};

/**
 * Minimal WAV/PCM decoder sufficient for BeatWave's bundled/imported loop
 * assets: canonical RIFF/WAVE container, PCM (format 1) or WAVE_FORMAT_
 * EXTENSIBLE, 8/16/24/32-bit integer samples.
 *
 * Off-audio-thread only -- this does asset I/O and heap allocation (mandate
 * 2). Never call from AudioEngine::onAudioReady or anything it transitively
 * calls.
 */
class WavDecoder {
public:
    /** Reads assetPath via assetManager and fully decodes it to float32.
     *  Returns false (leaving *out untouched) on any parse/read failure. */
    static bool decodeAsset(AAssetManager *assetManager, const std::string &assetPath, DecodedPcm *out);
};

/**
 * Linear-interpolation resample of `src` (interleaved, channelCount
 * channels, srcRateHz) to `dstRateHz`. Called exactly once per unique
 * sample at load time (mandate 2) and the result cached by SampleBank --
 * never call this from the audio callback.
 */
std::vector<float> resampleLinear(
        const std::vector<float> &src,
        int32_t channelCount,
        int32_t srcRateHz,
        int32_t dstRateHz);

} // namespace beatwave

#endif // BEATWAVE_WAV_DECODER_H
