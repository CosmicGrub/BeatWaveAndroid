#include "WavWriter.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <vector>

namespace beatwave {

namespace {

void writeU32LE(std::ofstream &file, uint32_t v) {
    const uint8_t b[4] = {
            static_cast<uint8_t>(v & 0xFF),
            static_cast<uint8_t>((v >> 8) & 0xFF),
            static_cast<uint8_t>((v >> 16) & 0xFF),
            static_cast<uint8_t>((v >> 24) & 0xFF)};
    file.write(reinterpret_cast<const char *>(b), 4);
}

void writeU16LE(std::ofstream &file, uint16_t v) {
    const uint8_t b[2] = {
            static_cast<uint8_t>(v & 0xFF),
            static_cast<uint8_t>((v >> 8) & 0xFF)};
    file.write(reinterpret_cast<const char *>(b), 2);
}

// Exact inverse of WavDecoder.cpp's normalizeSample() for the 16-bit case
// (raw / 32768.0f) -- see WavWriter.h's doc comment on round-tripping.
int16_t floatToInt16(float sample) {
    float clamped = sample;
    if (clamped > 1.0f) clamped = 1.0f;
    if (clamped < -1.0f) clamped = -1.0f;
    int32_t v = static_cast<int32_t>(std::lround(static_cast<double>(clamped) * 32768.0));
    if (v > 32767) v = 32767;
    if (v < -32768) v = -32768;
    return static_cast<int16_t>(v);
}

} // namespace

bool WavWriter::writeFile(
        const std::string &filePath,
        const float *interleaved,
        int64_t frameCount,
        int32_t channelCount,
        int32_t sampleRateHz) {
    if (interleaved == nullptr || frameCount <= 0 || channelCount <= 0 || sampleRateHz <= 0) {
        return false;
    }

    std::ofstream file(filePath, std::ios::binary | std::ios::trunc);
    if (!file.is_open()) {
        return false;
    }

    constexpr int32_t kBitsPerSample = 16;
    constexpr int32_t kBytesPerSample = kBitsPerSample / 8;
    const int32_t blockAlign = channelCount * kBytesPerSample;
    const uint32_t byteRate = static_cast<uint32_t>(sampleRateHz) * static_cast<uint32_t>(blockAlign);
    const uint64_t dataSize64 = static_cast<uint64_t>(frameCount) * static_cast<uint64_t>(channelCount) * static_cast<uint64_t>(kBytesPerSample);
    if (dataSize64 > 0xFFFFFFFFULL - 36ULL) {
        return false; // would overflow the 32-bit RIFF size fields -- not reachable given mandate 3's 3-minute cap, but stay defensive
    }
    const uint32_t dataSize = static_cast<uint32_t>(dataSize64);
    const uint32_t riffSize = 36 + dataSize;

    file.write("RIFF", 4);
    writeU32LE(file, riffSize);
    file.write("WAVE", 4);

    file.write("fmt ", 4);
    writeU32LE(file, 16); // canonical PCM fmt chunk size
    writeU16LE(file, 1);  // audioFormat = 1 (PCM)
    writeU16LE(file, static_cast<uint16_t>(channelCount));
    writeU32LE(file, static_cast<uint32_t>(sampleRateHz));
    writeU32LE(file, byteRate);
    writeU16LE(file, static_cast<uint16_t>(blockAlign));
    writeU16LE(file, static_cast<uint16_t>(kBitsPerSample));

    file.write("data", 4);
    writeU32LE(file, dataSize);

    // Stream the conversion out in bounded chunks rather than building one
    // giant int16 copy of the whole (up to 3-minute) buffer up front.
    const int64_t totalSamples = frameCount * channelCount;
    constexpr int64_t kChunkSamples = 1 << 16;
    std::vector<int16_t> chunk;
    chunk.resize(static_cast<size_t>(std::min<int64_t>(kChunkSamples, totalSamples)));

    int64_t written = 0;
    while (written < totalSamples) {
        const int64_t n = std::min<int64_t>(kChunkSamples, totalSamples - written);
        for (int64_t i = 0; i < n; ++i) {
            chunk[static_cast<size_t>(i)] = floatToInt16(interleaved[written + i]);
        }
        file.write(reinterpret_cast<const char *>(chunk.data()), static_cast<std::streamsize>(n * static_cast<int64_t>(sizeof(int16_t))));
        written += n;
    }

    // Capture stream health from the write loop itself -- file.good() after
    // close() only reflects whether close() succeeded, not the writes.
    const bool ok = file.good();
    file.close();
    return ok;
}

} // namespace beatwave
