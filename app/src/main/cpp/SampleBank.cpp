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
        it->second.lastAccessTick = ++mAccessCounter; // D1: mark most-recently-used
        return it->second.buffer;
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

    CacheEntry entry;
    const size_t byteSize = buffer->interleaved.size() * sizeof(float);
    entry.buffer = buffer;
    entry.byteSize = byteSize;
    entry.lastAccessTick = ++mAccessCounter;
    mCache.emplace(assetPath, std::move(entry));
    mCurrentCacheBytes += byteSize;

    evictIfOverBudgetLocked();

    return buffer;
}

void SampleBank::evictIfOverBudgetLocked() {
    while (mCurrentCacheBytes > mMaxCacheBytes && mCache.size() > 1) {
        auto lruIt = mCache.begin();
        for (auto it = mCache.begin(); it != mCache.end(); ++it) {
            if (it->second.lastAccessTick < lruIt->second.lastAccessTick) {
                lruIt = it;
            }
        }
        mCurrentCacheBytes -= lruIt->second.byteSize;
        mCache.erase(lruIt);
    }
}

void SampleBank::clear() {
    std::lock_guard<std::mutex> lock(mMutex);
    mCache.clear();
    mCurrentCacheBytes = 0;
}

void SampleBank::setMaxCacheBytes(size_t maxCacheBytes) {
    std::lock_guard<std::mutex> lock(mMutex);
    mMaxCacheBytes = maxCacheBytes;
    evictIfOverBudgetLocked();
}

size_t SampleBank::currentCacheBytes() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mCurrentCacheBytes;
}

size_t SampleBank::entryCount() const {
    std::lock_guard<std::mutex> lock(mMutex);
    return mCache.size();
}

} // namespace beatwave
