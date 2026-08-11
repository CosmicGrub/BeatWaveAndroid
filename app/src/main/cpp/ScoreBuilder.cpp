#include "ScoreBuilder.h"

#include <algorithm>
#include <cmath>
#include <utility>

namespace beatwave {

void ScoreBuilder::begin(int32_t bpm, int32_t sampleRateHz) {
    mScore = PlaybackScore{};
    mScore.bpm = bpm;
    // Mandate 1: 1 grid unit = one 16th note. Computed exactly once, here,
    // off the audio thread -- never recomputed per callback.
    if (bpm > 0 && sampleRateHz > 0) {
        mScore.framesPerGridUnit = (60.0 / static_cast<double>(bpm) / 4.0) * static_cast<double>(sampleRateHz);
    } else {
        mScore.framesPerGridUnit = 0.0;
    }
}

void ScoreBuilder::addTrack(int32_t slot) {
    for (const ResolvedTrack &t : mScore.tracks) {
        if (t.slot == slot) {
            return; // already present
        }
    }
    ResolvedTrack track;
    track.slot = slot;
    mScore.tracks.push_back(std::move(track));
}

bool ScoreBuilder::addLoopBlock(
        int32_t trackSlot,
        std::shared_ptr<const SampleBuffer> sample,
        int32_t startGridUnit,
        int32_t lengthGridUnits,
        float volume,
        int64_t trimStartMs,
        int64_t trimEndMs,
        float pitchSemitones) {
    if (!sample || sample->frameCount() <= 0 || lengthGridUnits <= 0 || mScore.framesPerGridUnit <= 0.0) {
        return false;
    }

    ResolvedTrack *track = nullptr;
    for (ResolvedTrack &t : mScore.tracks) {
        if (t.slot == trackSlot) {
            track = &t;
            break;
        }
    }
    if (track == nullptr) {
        return false; // caller must addTrack(trackSlot) first
    }

    const int64_t sampleFrames = sample->frameCount();
    const int32_t sampleRate = sample->sampleRateHz;

    // Mandate 3: trim (ms) -> frames, resolved once here at the resampled rate.
    int64_t trimStartFrames = static_cast<int64_t>(std::llround((static_cast<double>(trimStartMs) / 1000.0) * sampleRate));
    int64_t trimEndFrames = (trimEndMs < 0)
            ? sampleFrames
            : static_cast<int64_t>(std::llround((static_cast<double>(trimEndMs) / 1000.0) * sampleRate));

    if (trimStartFrames < 0) trimStartFrames = 0;
    if (trimStartFrames > sampleFrames) trimStartFrames = sampleFrames;
    if (trimEndFrames > sampleFrames) trimEndFrames = sampleFrames;
    if (trimEndFrames <= trimStartFrames) {
        return false; // degenerate/empty trim region -- nothing to play
    }
    const int64_t trimLengthFrames = trimEndFrames - trimStartFrames;

    // Mandate 5: pow(2, semitones/12).
    const double pitchRatio = std::pow(2.0, static_cast<double>(pitchSemitones) / 12.0);
    if (!(pitchRatio > 0.0)) {
        return false; // guard against NaN/inf from a pathological pitch value
    }

    // The repeat period as heard on the timeline shrinks/grows with pitch:
    // reading the trimmed region at pitchRatio speed covers it once every
    // trimLengthFrames / pitchRatio output frames. See PlaybackScore.h's
    // doc comment on ResolvedLoopBlock for the full reasoning.
    int64_t loopContentLengthFrames = static_cast<int64_t>(
            std::llround(static_cast<double>(trimLengthFrames) / pitchRatio));
    if (loopContentLengthFrames < 1) {
        loopContentLengthFrames = 1;
    }

    // Mandate 4: the timeline window this block occupies, resolved once here.
    const int64_t blockStartFrame = static_cast<int64_t>(
            std::llround(static_cast<double>(startGridUnit) * mScore.framesPerGridUnit));
    const int64_t blockLengthFrames = static_cast<int64_t>(
            std::llround(static_cast<double>(lengthGridUnits) * mScore.framesPerGridUnit));
    if (blockLengthFrames <= 0) {
        return false;
    }

    ResolvedLoopBlock block;
    block.blockStartFrame = blockStartFrame;
    block.blockLengthFrames = blockLengthFrames;
    block.loopContentLengthFrames = loopContentLengthFrames;
    block.trimStartFrames = trimStartFrames;
    block.trimEndFrames = trimEndFrames;
    // Mandate 8 documents volume as a 0..1 range. Clamp here rather than
    // trusting the caller: an out-of-range value would otherwise pass
    // straight through to MixEngine's `s * block.volume` (only bounded
    // later by the final soft clip), and a negative value would invert that
    // block's phase in the mix instead of attenuating it.
    block.volume = std::clamp(volume, 0.0f, 1.0f);
    block.pitchRatio = pitchRatio;
    block.sample = std::move(sample);

    track->blocks.push_back(std::move(block));
    return true;
}

PlaybackScore ScoreBuilder::build() {
    return std::move(mScore);
}

} // namespace beatwave
