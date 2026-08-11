#include "WavDecoder.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace beatwave {

namespace {

uint16_t readU16LE(const uint8_t *p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}

uint32_t readU32LE(const uint8_t *p) {
    return static_cast<uint32_t>(p[0]) | (static_cast<uint32_t>(p[1]) << 8) |
           (static_cast<uint32_t>(p[2]) << 16) | (static_cast<uint32_t>(p[3]) << 24);
}

int32_t readSampleAsInt(const uint8_t *p, int bytesPerSample) {
    switch (bytesPerSample) {
        case 1:
            return static_cast<int32_t>(p[0]) - 128; // 8-bit PCM is unsigned
        case 2:
            return static_cast<int16_t>(readU16LE(p));
        case 3: {
            int32_t v = p[0] | (p[1] << 8) | (p[2] << 16);
            if (v & 0x00800000) v |= static_cast<int32_t>(0xFF000000); // sign-extend 24-bit
            return v;
        }
        case 4:
            return static_cast<int32_t>(readU32LE(p));
        default:
            return 0;
    }
}

float normalizeSample(int32_t raw, int bytesPerSample) {
    switch (bytesPerSample) {
        case 1: return static_cast<float>(raw) / 128.0f;
        case 2: return static_cast<float>(raw) / 32768.0f;
        case 3: return static_cast<float>(raw) / 8388608.0f;
        case 4: return static_cast<float>(raw) / 2147483648.0f;
        default: return 0.0f;
    }
}

} // namespace

bool WavDecoder::decodeAsset(AAssetManager *assetManager, const std::string &assetPath, DecodedPcm *out) {
    if (assetManager == nullptr || out == nullptr) {
        return false;
    }

    AAsset *asset = AAssetManager_open(assetManager, assetPath.c_str(), AASSET_MODE_BUFFER);
    if (asset == nullptr) {
        return false;
    }

    const off_t length = AAsset_getLength(asset);
    std::vector<uint8_t> bytes(static_cast<size_t>(length > 0 ? length : 0));
    off_t totalRead = 0;
    while (totalRead < length) {
        int n = AAsset_read(asset, bytes.data() + totalRead, static_cast<size_t>(length - totalRead));
        if (n <= 0) break;
        totalRead += n;
    }
    AAsset_close(asset);

    if (totalRead != length || length < 44) {
        return false; // too short to be a valid WAV, or a truncated read
    }

    const uint8_t *data = bytes.data();
    if (std::memcmp(data, "RIFF", 4) != 0 || std::memcmp(data + 8, "WAVE", 4) != 0) {
        return false;
    }

    int32_t numChannels = 0;
    int32_t sampleRateHz = 0;
    int32_t bitsPerSample = 0;
    bool haveFmt = false;

    const uint8_t *dataChunkStart = nullptr;
    size_t dataChunkSize = 0;

    size_t pos = 12; // past "RIFF" + size + "WAVE"
    while (pos + 8 <= bytes.size()) {
        char chunkId[5] = {0, 0, 0, 0, 0};
        std::memcpy(chunkId, data + pos, 4);
        const uint32_t chunkSize = readU32LE(data + pos + 4);
        const size_t chunkDataStart = pos + 8;

        // Clamp against truncated/malformed size fields rather than trust
        // them blindly.
        const size_t available = bytes.size() > chunkDataStart ? bytes.size() - chunkDataStart : 0;
        const size_t usableSize = std::min(static_cast<size_t>(chunkSize), available);

        if (std::memcmp(chunkId, "fmt ", 4) == 0 && usableSize >= 16) {
            const uint8_t *fmt = data + chunkDataStart;
            // audioFormat at fmt[0..1]: 1 = PCM, 0xFFFE = EXTENSIBLE. Either
            // way we trust channels/rate/bitsPerSample below -- sufficient
            // for the integer-PCM WAV loops BeatWave targets.
            numChannels = readU16LE(fmt + 2);
            sampleRateHz = static_cast<int32_t>(readU32LE(fmt + 4));
            bitsPerSample = readU16LE(fmt + 14);
            haveFmt = true;
        } else if (std::memcmp(chunkId, "data", 4) == 0) {
            dataChunkStart = data + chunkDataStart;
            dataChunkSize = usableSize;
        }

        const size_t advance = chunkSize + (chunkSize % 2); // chunks are word-aligned
        if (advance == 0) break; // avoid an infinite loop on a malformed 0-size chunk
        pos = chunkDataStart + advance;
    }

    if (!haveFmt || dataChunkStart == nullptr || numChannels <= 0 || sampleRateHz <= 0) {
        return false;
    }
    const int bytesPerSample = bitsPerSample / 8;
    if (bytesPerSample != 1 && bytesPerSample != 2 && bytesPerSample != 3 && bytesPerSample != 4) {
        return false; // unsupported bit depth
    }

    const size_t frameSizeBytes = static_cast<size_t>(bytesPerSample) * static_cast<size_t>(numChannels);
    const int64_t numFrames = frameSizeBytes > 0
            ? static_cast<int64_t>(dataChunkSize / frameSizeBytes)
            : 0;
    if (numFrames <= 0) {
        return false;
    }

    out->channelCount = numChannels;
    out->sampleRateHz = sampleRateHz;
    out->interleaved.assign(static_cast<size_t>(numFrames) * static_cast<size_t>(numChannels), 0.0f);

    const uint8_t *src = dataChunkStart;
    for (int64_t frame = 0; frame < numFrames; ++frame) {
        for (int32_t ch = 0; ch < numChannels; ++ch) {
            const uint8_t *samplePtr = src
                    + (static_cast<size_t>(frame) * frameSizeBytes)
                    + (static_cast<size_t>(ch) * static_cast<size_t>(bytesPerSample));
            const int32_t raw = readSampleAsInt(samplePtr, bytesPerSample);
            out->interleaved[static_cast<size_t>(frame) * numChannels + ch] = normalizeSample(raw, bytesPerSample);
        }
    }

    return true;
}

std::vector<float> resampleLinear(
        const std::vector<float> &src, int32_t channelCount, int32_t srcRateHz, int32_t dstRateHz) {
    if (channelCount <= 0 || srcRateHz <= 0 || dstRateHz <= 0 || src.empty()) {
        return {};
    }
    const int64_t srcFrames = static_cast<int64_t>(src.size() / static_cast<size_t>(channelCount));
    if (srcFrames <= 0) {
        return {};
    }
    if (srcRateHz == dstRateHz) {
        return src;
    }

    const double ratio = static_cast<double>(srcRateHz) / static_cast<double>(dstRateHz);
    int64_t dstFrames = static_cast<int64_t>(std::llround(
            static_cast<double>(srcFrames) * static_cast<double>(dstRateHz) / static_cast<double>(srcRateHz)));
    if (dstFrames < 1) {
        dstFrames = 1;
    }

    std::vector<float> dst(static_cast<size_t>(dstFrames) * static_cast<size_t>(channelCount), 0.0f);
    for (int64_t i = 0; i < dstFrames; ++i) {
        const double srcPos = static_cast<double>(i) * ratio;
        int64_t idx0 = static_cast<int64_t>(srcPos);
        if (idx0 > srcFrames - 1) idx0 = srcFrames - 1;
        int64_t idx1 = idx0 + 1;
        if (idx1 > srcFrames - 1) idx1 = srcFrames - 1;
        const double frac = srcPos - static_cast<double>(idx0);
        for (int32_t ch = 0; ch < channelCount; ++ch) {
            const float a = src[static_cast<size_t>(idx0) * channelCount + ch];
            const float b = src[static_cast<size_t>(idx1) * channelCount + ch];
            dst[static_cast<size_t>(i) * channelCount + ch] = static_cast<float>(a + frac * (b - a));
        }
    }
    return dst;
}

} // namespace beatwave
