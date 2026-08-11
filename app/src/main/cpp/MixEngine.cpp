#include "MixEngine.h"

#include <cmath>
#include <cstring>

namespace beatwave {

namespace {

inline float softClip(float x) {
    // Mandate 8: a simple bounded soft clip so several simultaneous loud
    // loops round off toward +-1 instead of hard digital clipping.
    return std::tanh(x);
}

} // namespace

void renderScore(
        const PlaybackScore *score,
        int64_t transportFrameStart,
        int32_t numFrames,
        int32_t channelCount,
        float *outFloatBuffer) {
    const size_t totalSamples = static_cast<size_t>(numFrames) * static_cast<size_t>(channelCount);
    std::memset(outFloatBuffer, 0, sizeof(float) * totalSamples);

    if (score == nullptr) {
        return; // nothing committed yet -- silence
    }

    for (const ResolvedTrack &track : score->tracks) {
        for (const ResolvedLoopBlock &block : track.blocks) {
            if (!block.sample || block.loopContentLengthFrames <= 0 || block.blockLengthFrames <= 0) {
                continue; // degenerate block -- ScoreBuilder should already exclude these, but stay defensive
            }

            const SampleBuffer &buf = *block.sample;
            const int32_t srcChannelCount = buf.channelCount > 0 ? buf.channelCount : 1;
            const int64_t trimStartFrames = block.trimStartFrames;
            const int64_t trimEndFrames = block.trimEndFrames;
            if (trimEndFrames <= trimStartFrames) {
                continue;
            }

            for (int32_t i = 0; i < numFrames; ++i) {
                // Mandate 6 -- THE core derivation: every output frame's
                // read position is computed fresh from the single absolute
                // transport counter (transportFrameStart + i), via plain
                // integer subtraction and modulo. No per-block phase/cursor
                // is ever stored or incremented across callbacks or frames.
                const int64_t transportFrame = transportFrameStart + i;
                const int64_t framesSinceBlockStart = transportFrame - block.blockStartFrame;
                if (framesSinceBlockStart < 0 || framesSinceBlockStart >= block.blockLengthFrames) {
                    continue; // this block is not active at this frame
                }
                const int64_t loopLocalFrame = nonNegativeMod(framesSinceBlockStart, block.loopContentLengthFrames);

                // Mandate 5 -- fresh multiplication every frame, never an
                // incrementally-accumulated running phase.
                const double srcPos = static_cast<double>(loopLocalFrame) * block.pitchRatio;
                int64_t srcIdx = static_cast<int64_t>(srcPos); // srcPos >= 0 always
                const double frac = srcPos - static_cast<double>(srcIdx);

                int64_t frameA = trimStartFrames + srcIdx;
                if (frameA < trimStartFrames) frameA = trimStartFrames;
                if (frameA >= trimEndFrames) frameA = trimEndFrames - 1;
                int64_t frameB = frameA + 1;
                if (frameB >= trimEndFrames) frameB = trimStartFrames; // wrap -> a seamless loop boundary

                const size_t idxA = static_cast<size_t>(frameA) * static_cast<size_t>(srcChannelCount);
                const size_t idxB = static_cast<size_t>(frameB) * static_cast<size_t>(srcChannelCount);

                float *outFrame = outFloatBuffer + static_cast<size_t>(i) * static_cast<size_t>(channelCount);
                for (int32_t ch = 0; ch < channelCount; ++ch) {
                    const int32_t srcCh = (srcChannelCount == 1) ? 0 : (ch % srcChannelCount);
                    const float a = buf.interleaved[idxA + static_cast<size_t>(srcCh)];
                    const float b = buf.interleaved[idxB + static_cast<size_t>(srcCh)];
                    const float s = a + static_cast<float>(frac) * (b - a);
                    outFrame[ch] += s * block.volume;
                }
            }
        }
    }

    // Mandate 8: bounded soft clip applied to the summed mix before it goes
    // to the output.
    for (size_t n = 0; n < totalSamples; ++n) {
        outFloatBuffer[n] = softClip(outFloatBuffer[n]);
    }
}

} // namespace beatwave
