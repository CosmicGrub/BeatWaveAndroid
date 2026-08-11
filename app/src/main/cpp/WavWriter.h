#ifndef BEATWAVE_WAV_WRITER_H
#define BEATWAVE_WAV_WRITER_H

#include <cstdint>
#include <string>

namespace beatwave {

/**
 * Minimal canonical 16-bit PCM RIFF/WAVE writer -- the symmetric counterpart
 * to WavDecoder (see WavDecoder.h/.cpp). Produces the same simple
 * "RIFF/WAVE, one 16-byte 'fmt ' chunk (format 1 = PCM), one 'data' chunk"
 * shape WavDecoder::decodeBytesToPcm already knows how to parse, so a file
 * written here reads back through WavDecoder byte-for-byte consistently
 * (used by Phase 5's recording: the resulting WAV is loaded back for
 * playback through the exact same WavDecoder/SampleBank path any other
 * sample uses, no special-casing).
 *
 * Off-audio-thread only -- this does file I/O (mandate 5 of the Phase 5
 * design doc: AudioEngine::stopRecording calls this from the thread the
 * Kotlin stopRecording() call arrived on, never from onAudioReady).
 */
class WavWriter {
public:
    /**
     * Writes `frameCount` frames of `channelCount`-channel interleaved
     * float32 audio (expected in [-1, 1]; out-of-range samples are clamped
     * rather than wrapped) out to `filePath` as a canonical 16-bit PCM
     * RIFF/WAVE file at `sampleRateHz`. The float->int16 conversion is the
     * exact inverse of WavDecoder's int16 normalization (divide/multiply by
     * 32768.0), so decode(encode(x)) round-trips losslessly at 16-bit
     * resolution.
     *
     * Returns true on success. Returns false (and leaves whatever partial
     * file, if any, that the failed write produced) on any open/write
     * failure or degenerate input (frameCount <= 0, channelCount <= 0,
     * sampleRateHz <= 0, or a null buffer).
     */
    static bool writeFile(
            const std::string &filePath,
            const float *interleaved,
            int64_t frameCount,
            int32_t channelCount,
            int32_t sampleRateHz);
};

} // namespace beatwave

#endif // BEATWAVE_WAV_WRITER_H
