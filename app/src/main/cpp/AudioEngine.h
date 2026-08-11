#ifndef BEATWAVE_AUDIO_ENGINE_H
#define BEATWAVE_AUDIO_ENGINE_H

#include <oboe/Oboe.h>

namespace beatwave {

/**
 * Phase 0 scaffold: opens a low-latency Oboe output stream and renders
 * silence. This exists only to prove the native module builds, links
 * against Oboe correctly, and can open/close a stream on a real device
 * without crashing.
 *
 * NOT the real mixing engine. Phase 2 replaces the silent callback with
 * multi-track sample-accurate loop mixing; Phase 5 adds a full-duplex input
 * stream alongside this output stream for live mic recording. Both phases
 * should extend this class rather than create a second engine, per the
 * implementation plan's note to design the full-duplex path early.
 */
class AudioEngine : public oboe::AudioStreamDataCallback {
public:
    bool start();
    void stop();

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override;

private:
    std::shared_ptr<oboe::AudioStream> mStream;
};

} // namespace beatwave

#endif // BEATWAVE_AUDIO_ENGINE_H
