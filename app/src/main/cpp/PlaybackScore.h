#ifndef BEATWAVE_PLAYBACK_SCORE_H
#define BEATWAVE_PLAYBACK_SCORE_H

#include <cstdint>
#include <memory>
#include <vector>

#include "SampleBank.h"

namespace beatwave {

/**
 * One resolved, ready-to-mix loop block placement. Every field here is a
 * plain value or a shared_ptr resolved once at schedule-build time (off the
 * audio thread) -- the realtime callback only ever reads these, it never
 * computes or allocates them.
 *
 * Frame-unit reference:
 *  - blockStartFrame / blockLengthFrames are in TIMELINE (transport) frames
 *    -- the window [blockStartFrame, blockStartFrame + blockLengthFrames)
 *    within which this block is audible (mandate 1 / mandate 4).
 *  - trimStartFrames / trimEndFrames are in SOURCE frames of `sample`
 *    (already resampled to the engine's output rate, mandate 2/3) -- the
 *    trimmed sub-region that repeats.
 *  - loopContentLengthFrames is in TIMELINE frames: how many output frames
 *    one repeat of the trimmed content occupies once pitchRatio is taken
 *    into account (loopContentLengthFrames = round(trimLengthFrames /
 *    pitchRatio)). Deriving it this way is what keeps the trimmed region's
 *    boundaries correct under pitch shift: as a cycle-local timeline frame
 *    `f` ranges over [0, loopContentLengthFrames), `f * pitchRatio` ranges
 *    exactly over [0, trimLengthFrames) -- one full, in-bounds pass over
 *    the trimmed source region per repeat (see mandate 5/6 and MixEngine).
 */
struct ResolvedLoopBlock {
    int64_t blockStartFrame = 0;
    int64_t blockLengthFrames = 0;
    int64_t loopContentLengthFrames = 1; // never 0 -- guarded at build time (used as a modulus)
    int64_t trimStartFrames = 0;
    int64_t trimEndFrames = 0; // exclusive
    float volume = 1.0f;
    double pitchRatio = 1.0;
    std::shared_ptr<const SampleBuffer> sample;
};

struct ResolvedTrack {
    int32_t slot = 0;
    std::vector<ResolvedLoopBlock> blocks;
};

/**
 * Immutable, fully-resolved mix schedule. Built entirely off the audio
 * thread (see ScoreBuilder) and published to the realtime callback via a
 * single atomic shared_ptr swap (mandate 7, see AudioEngine::commitProject).
 * Once constructed, a PlaybackScore is never mutated -- safe to read
 * concurrently from the audio thread while a new one is being built and
 * later swapped in.
 */
struct PlaybackScore {
    int32_t bpm = 0;
    double framesPerGridUnit = 0.0; // mandate 1 -- computed once, off the audio thread
    std::vector<ResolvedTrack> tracks;
};

} // namespace beatwave

#endif // BEATWAVE_PLAYBACK_SCORE_H
