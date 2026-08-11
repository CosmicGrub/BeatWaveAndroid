#ifndef BEATWAVE_MIX_ENGINE_H
#define BEATWAVE_MIX_ENGINE_H

#include <cstdint>

#include "PlaybackScore.h"

namespace beatwave {

/**
 * Non-negative modulo: `a mod m` with the result always in [0, m). `a` may
 * be negative (mandate 6 -- a block's framesSinceBlockStart is negative
 * before the block starts). `m` must be > 0.
 *
 * Exposed (not file-local) so both MixEngine::renderScore and
 * AudioEngine's test-only introspection natives (mandate 10) use this exact
 * same derivation helper rather than two copies of the formula.
 */
inline int64_t nonNegativeMod(int64_t a, int64_t m) {
    const int64_t r = a % m;
    return r < 0 ? r + m : r;
}

/**
 * Pure, real-time-safe mixing function shared by the live Oboe callback
 * (AudioEngine::onAudioReady) and the offline test-advance path
 * (AudioEngine::renderOffline, mandate 10) -- both call this exact function
 * so there is only ever one implementation of the mix/derivation logic to
 * audit.
 *
 * Renders `numFrames` frames of `channelCount`-channel interleaved float
 * audio starting at absolute transport frame `transportFrameStart` into
 * `outFloatBuffer` (caller-owned, must hold numFrames*channelCount floats).
 *
 * Mandate 7 (real-time safety): performs ZERO heap allocation, ZERO mutex
 * locking, ZERO file I/O, and ZERO JNI/JVM calls. `score` may be null
 * (nothing committed yet) -- renders silence in that case. Every position
 * used here is derived fresh from `transportFrameStart` + the in-callback
 * frame index (mandate 6) -- nothing here reads or writes any persisted
 * per-block phase/cursor state.
 */
void renderScore(
        const PlaybackScore *score,
        int64_t transportFrameStart,
        int32_t numFrames,
        int32_t channelCount,
        float *outFloatBuffer);

} // namespace beatwave

#endif // BEATWAVE_MIX_ENGINE_H
