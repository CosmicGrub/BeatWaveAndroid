#ifndef BEATWAVE_SCORE_BUILDER_H
#define BEATWAVE_SCORE_BUILDER_H

#include <cstdint>
#include <memory>
#include <string>

#include "PlaybackScore.h"
#include "SampleBank.h"

namespace beatwave {

/**
 * Mutable staging area that resolves a project's grid-unit/millisecond loop
 * placements into a finished, immutable PlaybackScore. Used entirely off
 * the audio thread, driven by AudioEngine's beginProject/addTrack/
 * addLoopBlock/commitProject (mandate 9) -- both the live engine and the
 * offline/test engine (mandate 10) share this exact class so there is only
 * one implementation of the scheduling math to audit.
 */
class ScoreBuilder {
public:
    /** Starts a new score. sampleRateHz is the engine's real negotiated
     *  output rate (mandate 1's framesPerGridUnit is computed once, here). */
    void begin(int32_t bpm, int32_t sampleRateHz);

    /** Idempotent -- adding the same slot twice is a no-op. */
    void addTrack(int32_t slot);

    /**
     * Resolves and schedules one loop block onto trackSlot (which must have
     * already been added via addTrack). Returns false (and schedules
     * nothing) if trackSlot is unknown or the resulting block would be
     * degenerate (e.g. zero-length trim/window) -- callers should log and
     * treat that as a rejected placement rather than crash.
     */
    bool addLoopBlock(
            int32_t trackSlot,
            std::shared_ptr<const SampleBuffer> sample,
            int32_t startGridUnit,
            int32_t lengthGridUnits,
            float volume,
            int64_t trimStartMs,
            int64_t trimEndMs, // < 0 means "to the end of the sample"
            float pitchSemitones);

    /** Finalizes the in-progress score and returns it by value (moved out),
     *  ready for AudioEngine::commitProject to take ownership of it and
     *  publish it to the realtime callback. Leaves this builder ready to
     *  start a new score via begin(). */
    PlaybackScore build();

private:
    PlaybackScore mScore; // mutable while building
};

} // namespace beatwave

#endif // BEATWAVE_SCORE_BUILDER_H
