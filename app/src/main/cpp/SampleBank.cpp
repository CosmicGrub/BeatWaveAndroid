#include "SampleBank.h"

#include "WavDecoder.h"

namespace beatwave {

std::shared_ptr<const SampleBuffer> SampleBank::getOrLoad(
        AAssetManager *assetManager, const std::string &assetPath, int32_t targetSampleRateHz) {
    std::lock_guard<std::mutex> lock(mMutex);

    // Cache is keyed by asset path only -- resample target rate is stable
    // for the lifetime of a given engine/stream, and pitch/trim/volume are
    // per-loop-block properties applied at mix time, not part of this key.
    auto it = mCache.find(assetPath);
    if (it != mCache.end()) {
        return it->second;
    }

    DecodedPcm decoded;
    if (!WavDecoder::decodeAsset(assetManager, assetPath, &decoded)) {
        return nullptr;
    }

    auto buffer = std::make_shared<SampleBuffer>();
    buffer->channelCount = decoded.channelCount;
    buffer->sampleRateHz = targetSampleRateHz;
    buffer->interleaved = resampleLinear(
            decoded.interleaved, decoded.channelCount, decoded.sampleRateHz, targetSampleRateHz);

    if (buffer->interleaved.empty()) {
        return nullptr;
    }

    mCache.emplace(assetPath, buffer);
    return buffer;
}

void SampleBank::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mCache.clear();
}

} // namespace beatwave
