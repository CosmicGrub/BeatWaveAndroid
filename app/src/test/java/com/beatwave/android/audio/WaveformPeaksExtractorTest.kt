package com.beatwave.android.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for [WaveformPeaksExtractor] -- no device/Robolectric
 * needed, per this codebase's "narrow, dependency-free math/parsing utility"
 * testing precedent (mirrors how [GridConstants]'s formulas are exercised
 * without hardware). Hand-builds minimal WAV byte arrays rather than relying
 * on any bundled asset file, so every case here is exact and self-contained.
 */
class WaveformPeaksExtractorTest {

    @Test
    fun extract_simpleMono16Bit_producesExpectedPeakCountAndValues() {
        // 4 frames: amplitudes 0.0, 0.5, 1.0 (clamped from an over-range
        // input), 0.25 -- as 16-bit PCM.
        val samples = intArrayOf(0, 16384, 32767, 8192)
        val wav = buildWav(channelCount = 1, sampleRateHz = 8000, bitsPerSample = 16, samples16 = samples)

        val peaks = WaveformPeaksExtractor.extract(wav, peakCount = 4)

        assertEquals(4, peaks.size)
        assertEquals(0.0f, peaks[0], TOLERANCE)
        assertEquals(0.5f, peaks[1], TOLERANCE)
        assertTrue("expected peak[2] close to 1.0, got ${peaks[2]}", peaks[2] > 0.99f)
        assertEquals(0.25f, peaks[3], TOLERANCE)
    }

    @Test
    fun extract_stereoWithLoudSampleOnOneChannelOnly_stillCapturesIt() {
        // Frame 0: left=loud, right=silent. Frame 1: left=silent, right=loud.
        // The extractor takes the max across channels, so both frames
        // should read as loud regardless of which channel carries it.
        val interleaved = intArrayOf(32767, 0, 0, 32767)
        val wav = buildWav(channelCount = 2, sampleRateHz = 8000, bitsPerSample = 16, samples16 = interleaved)

        val peaks = WaveformPeaksExtractor.extract(wav, peakCount = 2)

        assertEquals(2, peaks.size)
        assertTrue("expected peak[0] to reflect the loud LEFT channel, got ${peaks[0]}", peaks[0] > 0.99f)
        assertTrue("expected peak[1] to reflect the loud RIGHT channel, got ${peaks[1]}", peaks[1] > 0.99f)
    }

    @Test
    fun extract_withExtraChunkBeforeData_stillParsesCorrectly() {
        // A real-world bundled asset isn't guaranteed to be free of extra
        // chunks (LIST/fact/etc.) between "fmt " and "data" -- the whole
        // reason this is a generic chunk scanner, not a fixed-offset parser.
        // An odd-sized extra chunk also exercises the word-alignment padding
        // path (advance = chunkSize + chunkSize % 2).
        val samples = intArrayOf(32767, 0, -32768, 0)
        val wav = buildWav(
            channelCount = 1, sampleRateHz = 8000, bitsPerSample = 16, samples16 = samples,
            extraChunkId = "LIST", extraChunkBytes = byteArrayOf(1, 2, 3) // odd size -> 1 padding byte
        )

        val peaks = WaveformPeaksExtractor.extract(wav, peakCount = 4)

        assertEquals(4, peaks.size)
        assertTrue("expected peak[0] near 1.0 despite the preceding LIST chunk, got ${peaks[0]}", peaks[0] > 0.99f)
        assertEquals(0.0f, peaks[1], TOLERANCE)
        assertTrue("expected peak[2] near 1.0 (full-scale negative sample), got ${peaks[2]}", peaks[2] > 0.99f)
    }

    @Test
    fun extract_audioShorterThanPeakCount_returnsFullPeakCountWithoutCrashing() {
        val samples = intArrayOf(32767) // a single frame
        val wav = buildWav(channelCount = 1, sampleRateHz = 8000, bitsPerSample = 16, samples16 = samples)

        val peaks = WaveformPeaksExtractor.extract(wav, peakCount = 50)

        assertEquals(50, peaks.size)
        assertTrue("expected every bucket to fall back to the single available frame", peaks.all { it > 0.99f })
    }

    @Test
    fun extract_notAWavFile_returnsEmptyListRatherThanThrowing() {
        val garbage = byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(emptyList<Float>(), WaveformPeaksExtractor.extract(garbage))
    }

    @Test
    fun extract_truncatedMidDataChunk_clampsRatherThanReadingPastTheBuffer() {
        val samples = intArrayOf(32767, 32767, 32767, 32767)
        val wav = buildWav(channelCount = 1, sampleRateHz = 8000, bitsPerSample = 16, samples16 = samples)
        // Declares 4 frames in the data-chunk size field but the buffer
        // itself is truncated to 2 -- the extractor must clamp to what's
        // actually present, not read/crash past the real array bounds.
        val truncated = wav.copyOf(wav.size - 4)

        val peaks = WaveformPeaksExtractor.extract(truncated, peakCount = 2)

        assertEquals(2, peaks.size)
    }

    @Test
    fun extract_zeroPeakCount_returnsEmptyList() {
        val wav = buildWav(channelCount = 1, sampleRateHz = 8000, bitsPerSample = 16, samples16 = intArrayOf(100))
        assertEquals(emptyList<Float>(), WaveformPeaksExtractor.extract(wav, peakCount = 0))
    }

    // --- extractFromInterleavedPcm16 (post-v1 audit A1: import size/DoS
    // hardening) -- AudioImporter's own entry point, operating directly on
    // raw already-decoded 16-bit PCM rather than WAV-file bytes. ---

    @Test
    fun extractFromInterleavedPcm16_simpleMono16Bit_producesExpectedPeakCountAndValues() {
        // Same values/expectations as extract_simpleMono16Bit... above --
        // this entry point must behave identically for the same underlying
        // samples, just fed raw PCM instead of a wrapping WAV file.
        val samples = intArrayOf(0, 16384, 32767, 8192)
        val pcm = buildRawPcm16(samples)

        val peaks = WaveformPeaksExtractor.extractFromInterleavedPcm16(pcm, channelCount = 1, peakCount = 4)

        assertEquals(4, peaks.size)
        assertEquals(0.0f, peaks[0], TOLERANCE)
        assertEquals(0.5f, peaks[1], TOLERANCE)
        assertTrue("expected peak[2] close to 1.0, got ${peaks[2]}", peaks[2] > 0.99f)
        assertEquals(0.25f, peaks[3], TOLERANCE)
    }

    @Test
    fun extractFromInterleavedPcm16_stereoWithLoudSampleOnOneChannelOnly_stillCapturesIt() {
        val interleaved = intArrayOf(32767, 0, 0, 32767)
        val pcm = buildRawPcm16(interleaved)

        val peaks = WaveformPeaksExtractor.extractFromInterleavedPcm16(pcm, channelCount = 2, peakCount = 2)

        assertEquals(2, peaks.size)
        assertTrue("expected peak[0] to reflect the loud LEFT channel, got ${peaks[0]}", peaks[0] > 0.99f)
        assertTrue("expected peak[1] to reflect the loud RIGHT channel, got ${peaks[1]}", peaks[1] > 0.99f)
    }

    @Test
    fun extractFromInterleavedPcm16_matchesExtractForTheSameUnderlyingAudio() {
        // The exact regression this entry point exists to avoid introducing:
        // AudioImporter used to call extract(wavBytes) on a canonical
        // 44-byte-header WAV; it now calls extractFromInterleavedPcm16 on
        // the raw PCM directly. For the SAME underlying samples, both must
        // produce IDENTICAL peaks -- this builds one canonical WAV, derives
        // its raw PCM (everything past the fixed 44-byte header, matching
        // AudioImporter.writeWavFile's exact layout), and compares both
        // entry points' output directly rather than just individually
        // asserting expected values.
        val samples = intArrayOf(-32768, -1000, 0, 1000, 16384, 32767, -20000, 500)
        val channelCount = 2
        val wav = buildWav(channelCount = channelCount, sampleRateHz = 44100, bitsPerSample = 16, samples16 = samples)
        val rawPcm = wav.copyOfRange(44, wav.size)

        val viaWav = WaveformPeaksExtractor.extract(wav, peakCount = 6)
        val viaRawPcm = WaveformPeaksExtractor.extractFromInterleavedPcm16(rawPcm, channelCount, peakCount = 6)

        assertEquals(viaWav.size, viaRawPcm.size)
        viaWav.indices.forEach { i ->
            assertEquals("peak[$i] should match between extract() and extractFromInterleavedPcm16()", viaWav[i], viaRawPcm[i], TOLERANCE)
        }
    }

    @Test
    fun extractFromInterleavedPcm16_zeroChannelCount_returnsEmptyList() {
        val pcm = buildRawPcm16(intArrayOf(100, 200))
        assertEquals(emptyList<Float>(), WaveformPeaksExtractor.extractFromInterleavedPcm16(pcm, channelCount = 0))
    }

    @Test
    fun extractFromInterleavedPcm16_zeroPeakCount_returnsEmptyList() {
        val pcm = buildRawPcm16(intArrayOf(100))
        assertEquals(emptyList<Float>(), WaveformPeaksExtractor.extractFromInterleavedPcm16(pcm, channelCount = 1, peakCount = 0))
    }

    @Test
    fun extractFromInterleavedPcm16_emptyPcm_returnsAllZeroPeaksWithoutCrashing() {
        val peaks = WaveformPeaksExtractor.extractFromInterleavedPcm16(ByteArray(0), channelCount = 1, peakCount = 5)
        assertEquals(List(5) { 0f }, peaks)
    }

    @Test
    fun extractFromInterleavedPcm16_audioShorterThanPeakCount_returnsFullPeakCountWithoutCrashing() {
        val pcm = buildRawPcm16(intArrayOf(32767)) // a single frame
        val peaks = WaveformPeaksExtractor.extractFromInterleavedPcm16(pcm, channelCount = 1, peakCount = 50)
        assertEquals(50, peaks.size)
        assertTrue("expected every bucket to fall back to the single available frame", peaks.all { it > 0.99f })
    }

    /** Raw interleaved 16-bit PCM bytes for [samples16] -- no WAV wrapper.
     *  Exactly the shape [WaveformPeaksExtractor.extractFromInterleavedPcm16]
     *  consumes, and exactly what [buildWav]'s own data chunk contains
     *  (extracted out to a shared helper so both stay byte-for-byte
     *  consistent). */
    private fun buildRawPcm16(samples16: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (s in samples16) {
            out.write(s and 0xFF)
            out.write((s shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    /** Hand-builds a canonical (optionally with one extra chunk inserted
     *  between "fmt " and "data") 16-bit PCM RIFF/WAVE file from raw
     *  interleaved sample values, mirroring WavWriter.cpp's own byte layout
     *  exactly. */
    private fun buildWav(
        channelCount: Int,
        sampleRateHz: Int,
        bitsPerSample: Int,
        samples16: IntArray,
        extraChunkId: String? = null,
        extraChunkBytes: ByteArray = ByteArray(0)
    ): ByteArray {
        val bytesPerSample = bitsPerSample / 8
        val blockAlign = channelCount * bytesPerSample
        val byteRate = sampleRateHz * blockAlign
        val data = buildRawPcm16(samples16)

        val extraChunkTotalSize = if (extraChunkId != null) {
            8 + extraChunkBytes.size + (extraChunkBytes.size % 2)
        } else {
            0
        }
        val riffSize = 4 + (8 + 16) + extraChunkTotalSize + (8 + data.size)

        val out = ByteArrayOutputStream()
        fun writeTag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeU32(v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            out.write(b)
        }
        fun writeU16(v: Int) {
            val b = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort()).array()
            out.write(b)
        }

        writeTag("RIFF")
        writeU32(riffSize)
        writeTag("WAVE")

        writeTag("fmt ")
        writeU32(16)
        writeU16(1) // PCM
        writeU16(channelCount)
        writeU32(sampleRateHz)
        writeU32(byteRate)
        writeU16(blockAlign)
        writeU16(bitsPerSample)

        if (extraChunkId != null) {
            writeTag(extraChunkId)
            writeU32(extraChunkBytes.size)
            out.write(extraChunkBytes)
            if (extraChunkBytes.size % 2 != 0) out.write(0) // word-alignment padding byte
        }

        writeTag("data")
        writeU32(data.size)
        out.write(data)

        return out.toByteArray()
    }

    companion object {
        private const val TOLERANCE = 0.001f
    }
}
