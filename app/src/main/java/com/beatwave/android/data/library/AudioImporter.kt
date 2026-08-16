package com.beatwave.android.data.library

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.beatwave.android.audio.WaveformPeaksExtractor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a user-picked audio [Uri] (Phase 4 SAF import, design item 3) to a
 * 16-bit PCM RIFF/WAVE file under `filesDir/imported_samples/<uuid>.wav`,
 * using [MediaExtractor] to demux and [MediaCodec] to decode the first audio
 * track it finds. The produced WAV file matches exactly what the native
 * `WavDecoder` (see app/src/main/cpp/WavDecoder.cpp) already parses: a
 * canonical RIFF/WAVE container, 16-bit PCM (format 1), so the resulting
 * file loads through the engine's filesystem-path decode path identically to
 * a bundled asset.
 *
 * Runs entirely off the main thread -- [import] dispatches its own work onto
 * [Dispatchers.IO], so callers may invoke it from any coroutine context.
 */
class AudioImporter(private val context: Context) {

    /** Successful import: the WAV file written to app-private storage, the
     *  original file's display name (best-effort), its real decoded
     *  duration in milliseconds (derived from decoded frame count, never
     *  estimated), and its [com.beatwave.android.data.model.Sample.waveformPeaks]
     *  (waveform-visualization upgrade). */
    data class ImportResult(
        val file: File,
        val displayName: String,
        val durationMs: Long,
        val waveformPeaks: List<Float> = emptyList()
    )

    /** Every failure mode [import] can surface, all user-presentable via
     *  [Throwable.message] (e.g. in a Snackbar). */
    sealed class ImportError(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class NoAudioTrack :
            ImportError("The selected file doesn't contain an audio track.")
        class DecodeFailed(cause: Throwable? = null) :
            ImportError("Couldn't decode the selected audio file. It may be corrupt or in an unsupported format.", cause)
        class IoFailure(cause: Throwable? = null) :
            ImportError("Couldn't read the selected file.", cause)
    }

    private data class DecodedAudio(
        val pcm: ByteArray,
        val sampleRateHz: Int,
        val channelCount: Int
    )

    /** Decodes [uri] and writes the result into app-private storage. Never
     *  throws -- decode/IO failures come back as [Result.failure] wrapping
     *  an [ImportError] so the caller (see ArrangementViewModel) can surface
     *  a user-visible message instead of crashing. */
    suspend fun import(uri: Uri): Result<ImportResult> = withContext(Dispatchers.IO) {
        try {
            val displayName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "Imported Sample"
            val decoded = decodeToPcm(uri)
            val file = writeWavFile(decoded)
            val frameSizeBytes = BYTES_PER_SAMPLE * decoded.channelCount
            val numFrames = if (frameSizeBytes > 0) decoded.pcm.size / frameSizeBytes else 0
            val durationMs = if (decoded.sampleRateHz > 0) {
                numFrames.toLong() * 1000L / decoded.sampleRateHz.toLong()
            } else {
                0L
            }
            // Waveform-visualization upgrade: read the file just written
            // back rather than re-deriving peaks from `decoded.pcm` directly
            // -- reuses WaveformPeaksExtractor's ONE canonical-WAV-bytes
            // entry point (the same one AssetLoopLibrary/recording use)
            // instead of a second, raw-PCM-shaped code path. The extra read
            // is a small, one-time cost against a file this same function
            // just wrote, not a hot path.
            val waveformPeaks = try {
                WaveformPeaksExtractor.extract(file.readBytes())
            } catch (e: IOException) {
                emptyList()
            }
            Result.success(
                ImportResult(file = file, displayName = displayName, durationMs = durationMs, waveformPeaks = waveformPeaks)
            )
        } catch (e: ImportError) {
            Result.failure(e)
        } catch (e: Exception) {
            // Any other unexpected failure (OOM aside) still needs to reach
            // the caller as a graceful error, never a crash.
            Result.failure(ImportError.DecodeFailed(e))
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** Standard MediaExtractor+MediaCodec decode-to-PCM loop: feed input
     *  buffers from the extractor, drain output buffers into [pcm], repeat
     *  until end-of-stream. */
    private fun decodeToPcm(uri: Uri): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: IOException) {
            extractor.release()
            throw ImportError.IoFailure(e)
        } catch (e: SecurityException) {
            extractor.release()
            throw ImportError.IoFailure(e)
        }

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val candidate = extractor.getTrackFormat(i)
            val mime = candidate.getString(MediaFormat.KEY_MIME)
            if (mime != null && mime.startsWith("audio/")) {
                trackIndex = i
                format = candidate
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw ImportError.NoAudioTrack()
        }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRateHz = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 0
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 0
        if (sampleRateHz <= 0 || channelCount <= 0) {
            extractor.release()
            throw ImportError.DecodeFailed()
        }

        val codec = try {
            MediaCodec.createDecoderByType(mime)
        } catch (e: Exception) {
            extractor.release()
            throw ImportError.DecodeFailed(e)
        }
        try {
            codec.configure(format, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            extractor.release()
            throw ImportError.DecodeFailed(e)
        }

        val pcmOut = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        val deadlineMs = System.currentTimeMillis() + MAX_DECODE_WALL_CLOCK_MS

        try {
            while (!sawOutputEos) {
                if (System.currentTimeMillis() > deadlineMs) {
                    throw ImportError.DecodeFailed()
                }

                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: throw ImportError.DecodeFailed()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTimeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                var outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                            ?: throw ImportError.DecodeFailed()
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        outputBuffer.get(chunk)
                        pcmOut.write(chunk)
                    }
                    val wasEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (wasEos) {
                        sawOutputEos = true
                        break
                    }
                    outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                }
            }
        } catch (e: ImportError) {
            throw e
        } catch (e: Exception) {
            throw ImportError.DecodeFailed(e)
        } finally {
            try {
                codec.stop()
            } catch (e: Exception) {
                // Already in a failure path; nothing more to do.
            }
            codec.release()
            extractor.release()
        }

        val pcmBytes = pcmOut.toByteArray()
        if (pcmBytes.isEmpty()) {
            throw ImportError.DecodeFailed()
        }
        return DecodedAudio(pcm = pcmBytes, sampleRateHz = sampleRateHz, channelCount = channelCount)
    }

    /** Writes a canonical 16-bit PCM RIFF/WAVE file for [decoded] into
     *  `filesDir/imported_samples/<uuid>.wav`, generating a fresh UUID per
     *  import so filenames never collide. */
    private fun writeWavFile(decoded: DecodedAudio): File {
        val dir = File(context.filesDir, IMPORTED_SAMPLES_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val file = File(dir, "${UUID.randomUUID()}.wav")

        val bitsPerSample = BYTES_PER_SAMPLE * 8
        val blockAlign = decoded.channelCount * BYTES_PER_SAMPLE
        val byteRate = decoded.sampleRateHz * blockAlign
        val dataSize = decoded.pcm.size
        val riffChunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riffChunkSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16) // PCM fmt chunk size
        header.putShort(1) // audioFormat = 1 (PCM)
        header.putShort(decoded.channelCount.toShort())
        header.putInt(decoded.sampleRateHz)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        try {
            FileOutputStream(file).use { out ->
                out.write(header.array())
                out.write(decoded.pcm)
            }
        } catch (e: IOException) {
            file.delete()
            throw ImportError.IoFailure(e)
        }
        return file
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_DECODE_WALL_CLOCK_MS = 60_000L
        private const val BYTES_PER_SAMPLE = 2 // 16-bit PCM

        /** Sibling of [ImportedSampleIndex]'s storage location -- both live
         *  under this same directory, matching the persisted index's
         *  `forContext` factory. */
        const val IMPORTED_SAMPLES_DIR_NAME = "imported_samples"
    }
}
