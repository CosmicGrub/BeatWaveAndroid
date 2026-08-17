package com.beatwave.android.audio

import kotlin.math.abs
import kotlin.math.min

/**
 * Waveform-visualization upgrade: extracts a fixed-size list of normalized
 * peak amplitudes ([0, 1]) from a canonical RIFF/WAVE byte buffer, for
 * [com.beatwave.android.data.model.Sample.waveformPeaks] (a field that has
 * existed since Phase 1 but was never populated -- see that field's own doc
 * comment).
 *
 * Deliberately a generic, chunk-scanning parser -- mirroring
 * `app/src/main/cpp/WavDecoder.cpp`'s `decodeBytesToPcm` exactly (same chunk
 * loop, same word-alignment/malformed-size handling, same 8/16/24/32-bit PCM
 * support/normalization) -- rather than assuming the simple fixed 44-byte
 * header [com.beatwave.android.audio.PlaybackEngine]'s own `WavWriter`
 * output always has. Bundled loop-pack assets are real-world WAV files, not
 * guaranteed to be free of extra chunks (LIST/fact/etc.) or an extended fmt
 * chunk -- this must decode the SAME bytes the native engine will actually
 * play back, correctly, for every source (bundled/imported/recorded), or
 * the displayed waveform could visually mismatch what's heard.
 *
 * Pure Kotlin, no Android dependency -- unit-testable on the plain JVM
 * without a device, per this codebase's existing "narrow, dependency-free
 * math/parsing utility" precedent ([GridConstants]).
 */
object WaveformPeaksExtractor {

    /** Default peak count: enough resolution to look like a real waveform at
     *  the sizes this app renders blocks/editors at, small enough to store
     *  cheaply in every persisted [com.beatwave.android.data.model.Sample]. */
    const val DEFAULT_PEAK_COUNT = 200

    /**
     * Parses [wavBytes] as a canonical RIFF/WAVE file and returns
     * [peakCount] normalized peak amplitudes (max absolute sample value
     * across all channels, per bucket of frames, bucketed evenly across the
     * whole file) -- or an empty list if [wavBytes] isn't a valid/parseable
     * WAV, mirroring [com.beatwave.android.data.library.AudioImporter]'s own
     * "never throws, degrade gracefully" contract rather than propagating a
     * parse failure into a UI crash.
     */
    fun extract(wavBytes: ByteArray, peakCount: Int = DEFAULT_PEAK_COUNT): List<Float> {
        if (peakCount <= 0) return emptyList()
        val decoded = decodeToInterleavedPcm(wavBytes) ?: return emptyList()
        return bucketPeaks(decoded, peakCount)
    }

    /**
     * Post-v1 audits/upgrades backlog item A1 (import size/DoS hardening):
     * a second entry point for callers that already hold raw, already-
     * decoded 16-bit interleaved PCM in memory -- [com.beatwave.android.data.library.AudioImporter]
     * specifically, whose `decodeToPcm` output IS this exact shape before it
     * is ever written to a WAV file. Skips [extract]'s WAV-header/chunk-
     * scanning step entirely, going straight from raw PCM bytes to peaks.
     *
     * This exists to remove a REDUNDANT full-size allocation from
     * AudioImporter's import path: without it, computing peaks required
     * re-reading the just-written WAV file's bytes back off disk (a second
     * full-size copy of data the caller already had in memory), stacked on
     * top of the transient copies [maxDecodedPcmBytes]-style caps already
     * have to account for. Not a replacement for [extract] -- callers that
     * only have WAV-FILE bytes (bundled assets, on-disk recordings) still
     * need the generic chunk-scanning parser, since THEIR files may carry
     * extra chunks/an extended fmt chunk this shortcut deliberately doesn't
     * handle (this function assumes exactly the canonical, header-free,
     * always-16-bit shape AudioImporter's own decode output guarantees, not
     * an arbitrary real-world WAV file).
     *
     * Same "never throws, degrade gracefully" contract as [extract]: a
     * [channelCount] that doesn't evenly divide [pcm], or is non-positive,
     * degrades to an all-zero/empty result rather than throwing.
     */
    fun extractFromInterleavedPcm16(
        pcm: ByteArray,
        channelCount: Int,
        peakCount: Int = DEFAULT_PEAK_COUNT
    ): List<Float> {
        if (peakCount <= 0 || channelCount <= 0) return emptyList()
        val bytesPerSample = 2 // AudioImporter's decodeToPcm always produces 16-bit PCM
        val frameSizeBytes = bytesPerSample * channelCount
        val numFrames = pcm.size / frameSizeBytes
        if (numFrames <= 0) return List(peakCount) { 0f }

        val interleaved = FloatArray(numFrames * channelCount)
        for (frame in 0 until numFrames) {
            for (ch in 0 until channelCount) {
                val sampleOffset = frame * frameSizeBytes + ch * bytesPerSample
                val raw = readSampleAsInt(pcm, sampleOffset, bytesPerSample)
                interleaved[frame * channelCount + ch] = normalizeSample(raw, bytesPerSample)
            }
        }
        return bucketPeaks(DecodedPcm(interleaved, channelCount), peakCount)
    }

    private class DecodedPcm(val interleaved: FloatArray, val channelCount: Int)

    /** Mirrors WavDecoder.cpp's decodeBytesToPcm exactly -- see this
     *  object's class doc comment for why this can't just assume a fixed
     *  44-byte header. */
    private fun decodeToInterleavedPcm(bytes: ByteArray): DecodedPcm? {
        if (bytes.size < 44) return null
        if (!bytes.regionMatches(0, RIFF_TAG) || !bytes.regionMatches(8, WAVE_TAG)) return null

        var channelCount = 0
        var bitsPerSample = 0
        var haveFmt = false
        var dataChunkStart = -1
        var dataChunkSize = 0

        var pos = 12 // past "RIFF" + size + "WAVE"
        while (pos + 8 <= bytes.size) {
            val chunkId = String(bytes, pos, 4, Charsets.US_ASCII)
            val chunkSize = readU32LE(bytes, pos + 4)
            val chunkDataStart = pos + 8

            val available = if (bytes.size > chunkDataStart) bytes.size - chunkDataStart else 0
            val usableSize = min(chunkSize, available.toLong()).toInt()

            when {
                chunkId == "fmt " && usableSize >= 16 -> {
                    channelCount = readU16LE(bytes, chunkDataStart + 2)
                    bitsPerSample = readU16LE(bytes, chunkDataStart + 14)
                    haveFmt = true
                }
                chunkId == "data" -> {
                    dataChunkStart = chunkDataStart
                    dataChunkSize = usableSize
                }
            }

            // Chunks are word-aligned -- an odd-sized chunk has one byte of
            // padding after it that isn't reflected in its own size field.
            val advance = chunkSize + (chunkSize % 2)
            if (advance <= 0L) break // malformed 0-size chunk -- avoid an infinite loop
            pos = chunkDataStart + advance.toInt()
        }

        if (!haveFmt || dataChunkStart < 0 || channelCount <= 0) return null
        val bytesPerSample = bitsPerSample / 8
        if (bytesPerSample !in 1..4) return null // unsupported bit depth

        val frameSizeBytes = bytesPerSample * channelCount
        if (frameSizeBytes <= 0) return null
        val numFrames = dataChunkSize / frameSizeBytes
        if (numFrames <= 0) return null

        val interleaved = FloatArray(numFrames * channelCount)
        for (frame in 0 until numFrames) {
            for (ch in 0 until channelCount) {
                val sampleOffset = dataChunkStart + frame * frameSizeBytes + ch * bytesPerSample
                val raw = readSampleAsInt(bytes, sampleOffset, bytesPerSample)
                interleaved[frame * channelCount + ch] = normalizeSample(raw, bytesPerSample)
            }
        }
        return DecodedPcm(interleaved, channelCount)
    }

    /** Buckets [decoded]'s frames evenly into [peakCount] buckets (the same
     *  float-indexed bucket-boundary approach WavDecoder.cpp's own
     *  resampleLinear uses for its src-position math), taking the max
     *  absolute amplitude across all channels within each bucket. Works for
     *  any frame count relative to [peakCount], including audio shorter
     *  than [peakCount] frames (adjacent buckets then legitimately share
     *  frames). */
    private fun bucketPeaks(decoded: DecodedPcm, peakCount: Int): List<Float> {
        val numFrames = decoded.interleaved.size / decoded.channelCount
        if (numFrames <= 0) return List(peakCount) { 0f }

        return List(peakCount) { bucketIndex ->
            val startFrame = (bucketIndex.toLong() * numFrames / peakCount).toInt()
            val endFrame = ((bucketIndex + 1).toLong() * numFrames / peakCount).toInt().coerceAtLeast(startFrame + 1)
            var peak = 0f
            for (frame in startFrame until endFrame.coerceAtMost(numFrames)) {
                for (ch in 0 until decoded.channelCount) {
                    val amplitude = abs(decoded.interleaved[frame * decoded.channelCount + ch])
                    if (amplitude > peak) peak = amplitude
                }
            }
            peak.coerceIn(0f, 1f)
        }
    }

    private fun ByteArray.regionMatches(offset: Int, tag: ByteArray): Boolean {
        if (offset + tag.size > size) return false
        for (i in tag.indices) {
            if (this[offset + i] != tag[i]) return false
        }
        return true
    }

    private fun readU16LE(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readU32LE(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xFF) or
            ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
            ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
            ((bytes[offset + 3].toLong() and 0xFF) shl 24)

    private fun readSampleAsInt(bytes: ByteArray, offset: Int, bytesPerSample: Int): Int = when (bytesPerSample) {
        1 -> (bytes[offset].toInt() and 0xFF) - 128 // 8-bit PCM is unsigned
        2 -> readU16LE(bytes, offset).toShort().toInt() // sign-extend via Short
        3 -> {
            var v = (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16)
            if (v and 0x00800000 != 0) v = v or -0x1000000 // sign-extend 24-bit (0xFF000000)
            v
        }
        4 -> readU32LE(bytes, offset).toInt()
        else -> 0
    }

    private fun normalizeSample(raw: Int, bytesPerSample: Int): Float = when (bytesPerSample) {
        1 -> raw / 128.0f
        2 -> raw / 32768.0f
        3 -> raw / 8388608.0f
        4 -> raw / 2147483648.0f
        else -> 0.0f
    }

    private val RIFF_TAG = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WAVE_TAG = "WAVE".toByteArray(Charsets.US_ASCII)
}
