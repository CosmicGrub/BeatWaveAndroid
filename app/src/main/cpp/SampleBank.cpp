#include "SampleBank.h"

#include "WavDecoder.h"

namespace beatwave {

namespace {

// Path-shape convention shared with the Kotlin-side importer: a leading '/'
// means an absolute filesystem path (imported sample under filesDir);
// anything else is an AAssetManager-relative bundled asset path. Bundled
// asset paths never start with '/', so the two shapes can never collide.
bool isFilesystemPath(const std::string &path) {
    return !path.empty() && path.front() == '/';
}

} // namespace

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
    const bool decodeOk = isFilesystemPath(assetPath)
            ? WavDecoder::decodeFile(assetPath, &decoded)
            : WavDecoder::decodeAsset(assetManager, assetPath, &decoded);
    if (!decodeOk) {
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
