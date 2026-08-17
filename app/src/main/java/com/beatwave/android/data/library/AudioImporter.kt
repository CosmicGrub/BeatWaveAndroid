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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
 *
 * POST-V1 AUDIT A1 (import size/DoS hardening): [maxDecodedPcmBytes] bounds
 * how much decoded PCM a single [import] call will accumulate before
 * bailing out with [ImportError.TooLarge] -- checked both as an early,
 * fast-fail ContentResolver size hint (before any MediaExtractor/MediaCodec
 * setup work begins) and as a running byte count inside `decodeToPcm`'s
 * decode loop (the real defense: a highly-compressed adversarial source,
 * e.g. a long silence-only stream, can report a tiny size hint yet still
 * decode to an enormous buffer). [import] also wraps the whole decode in
 * [withTimeout] + [runInterruptible] -- BEST-EFFORT ONLY (confirmed during
 * this audit's own adversarial-review pass, not a guarantee): the
 * loop-internal `MAX_DECODE_WALL_CLOCK_MS` deadline never covered
 * `extractor.setDataSource`/`codec.start()` themselves, and
 * [runInterruptible]'s only preemption mechanism is `Thread.interrupt()`,
 * which MediaExtractor/MediaCodec's native calls are NOT guaranteed to
 * honor -- a genuinely stuck `content://` provider (Phase 7's ACTION_SEND
 * intake -- explicitly a less-trusted source than the user's own SAF pick)
 * can still block this call past the timeout in the worst case. Because of
 * that, the caller-side concurrency guard
 * ([com.beatwave.android.BeatWaveApplication.importLeaseClaimedAtMs], see
 * [com.beatwave.android.ui.arrangement.ArrangementViewModel.importAudioFromUri])
 * is deliberately a SELF-EXPIRING lease, not a plain flag that depends on
 * this function ever returning -- that's what actually bounds the
 * worst-case app-wide impact of a stuck decode, not this timeout wrapper.
 */
class AudioImporter(
    private val context: Context,
    private val maxDecodedPcmBytes: Long = DEFAULT_MAX_DECODED_PCM_BYTES
) {

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
        /** Post-v1 audit A1: [maxDecodedPcmBytes] exceeded, via either the
         *  early ContentResolver size-hint check or the running byte count
         *  inside the decode loop. */
        class TooLarge :
            ImportError("The selected file is too large to import. Try a shorter clip.")
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
            // Post-v1 audit A1: wraps setDataSource/track-selection/
            // codec.configure+start (none of which the loop-internal
            // MAX_DECODE_WALL_CLOCK_MS deadline covers, since that deadline
            // isn't computed until decodeToPcm's decode loop begins) PLUS
            // the decode loop itself. BEST-EFFORT, not a guarantee -- see
            // this class's own doc comment: if the underlying native call
            // genuinely doesn't respond to Thread.interrupt(), this can
            // still block past MAX_DECODE_WALL_CLOCK_MS. What actually
            // bounds the worst-case app-wide impact of that is the CALLER's
            // self-expiring importLeaseClaimedAtMs lease, not this wrapper.
            val decoded = withTimeout(MAX_DECODE_WALL_CLOCK_MS) {
                runInterruptible(Dispatchers.IO) { decodeToPcm(uri) }
            }
            val file = writeWavFile(decoded)
            val frameSizeBytes = BYTES_PER_SAMPLE * decoded.channelCount
            val numFrames = if (frameSizeBytes > 0) decoded.pcm.size / frameSizeBytes else 0
            val durationMs = if (decoded.sampleRateHz > 0) {
                numFrames.toLong() * 1000L / decoded.sampleRateHz.toLong()
            } else {
                0L
            }
            // Post-v1 audit A1: computed directly from `decoded.pcm` (this
            // function already holds it) rather than re-reading the just-
            // written file's bytes a second time -- the original waveform-
            // visualization-upgrade code re-read the file for the sake of
            // reusing WaveformPeaksExtractor's one WAV-bytes entry point,
            // but that redundant full-size read stacked another same-order
            // copy on top of the transient memory maxDecodedPcmBytes is
            // meant to bound (see extractFromInterleavedPcm16's own doc
            // comment). No try/catch needed here (unlike the old
            // file.readBytes() call): this is pure in-memory computation
            // with the same "never throws" contract.
            val waveformPeaks = WaveformPeaksExtractor.extractFromInterleavedPcm16(decoded.pcm, decoded.channelCount)
            Result.success(
                ImportResult(file = file, displayName = displayName, durationMs = durationMs, waveformPeaks = waveformPeaks)
            )
        } catch (e: ImportError) {
            Result.failure(e)
        } catch (e: TimeoutCancellationException) {
            // Post-v1 audit A1: our OWN local withTimeout above, deliberately
            // converted to a domain error here -- this is the one
            // CancellationException subtype this function intentionally
            // absorbs, since it represents a real, user-facing "this import
            // didn't finish in time" outcome, not an external cancellation.
            Result.failure(ImportError.DecodeFailed(e))
        } catch (e: CancellationException) {
            // Found during this audit's adversarial-review pass: any OTHER
            // cancellation (e.g. the owning ViewModel/coroutine scope being
            // torn down while this import is in flight) must propagate
            // normally so structured concurrency isn't silently broken --
            // NOT converted into a Result the caller's .fold() would
            // otherwise act on as if the import simply failed. Caught
            // ahead of the generic `catch (e: Exception)` below (which
            // would otherwise also match this, since CancellationException
            // IS an Exception).
            throw e
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

    /** Post-v1 audit A1: fast-fail check using the source's ContentResolver-
     *  reported size, before any MediaExtractor/MediaCodec setup work
     *  begins. Deliberately just an optimization, NOT the primary defense --
     *  a highly-compressed adversarial file (e.g. a long silence-only
     *  stream) can report a small size here yet still decode to an enormous
     *  PCM buffer, which is exactly what the running byte count inside
     *  [decodeToPcm]'s decode loop actually defends against. Many
     *  content:// providers simply don't report a SIZE column at all --
     *  that's treated as "unknown" and allowed through (not rejected),
     *  since the running check remains the real backstop either way,
     *  mirroring [queryDisplayName]'s own graceful-fallback-on-missing-data
     *  shape. */
    private fun checkSourceSizeHint(uri: Uri) {
        val sizeHint = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
            }
        } catch (e: SecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
        if (sizeHint != null && sizeHint > maxDecodedPcmBytes) {
            throw ImportError.TooLarge()
        }
    }

    /** Standard MediaExtractor+MediaCodec decode-to-PCM loop: feed input
     *  buffers from the extractor, drain output buffers into [pcm], repeat
     *  until end-of-stream. */
    private fun decodeToPcm(uri: Uri): DecodedAudio {
        checkSourceSizeHint(uri)
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
                        // Post-v1 audit A1: checked HERE, inside this inner
                        // drain loop immediately after the write that
                        // actually grows pcmOut -- NOT only in the outer
                        // loop's deadline check above. A single outer pass
                        // can drain an unbounded run of already-ready output
                        // buffers via this inner loop alone (it only returns
                        // to the outer loop once dequeueOutputBuffer stops
                        // returning immediately-ready buffers), which would
                        // let pcmOut blow past both the byte ceiling and the
                        // wall-clock deadline before either outer-loop check
                        // ever got a chance to re-fire.
                        if (pcmOut.size() > maxDecodedPcmBytes) {
                            throw ImportError.TooLarge()
                        }
                        if (System.currentTimeMillis() > deadlineMs) {
                            throw ImportError.DecodeFailed()
                        }
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

        /** Post-v1 audits/upgrades backlog item A1 (import size/DoS
         *  hardening): the default [maxDecodedPcmBytes] ceiling -- 96 MiB,
         *  chosen to comfortably cover the backlog's own "generous multiple
         *  of the ~3-minute recording cap" intent for BOTH common real-world
         *  sample rates: ~8:44 of stereo 16-bit audio at 48kHz, or ~9:31 at
         *  44.1kHz. (Found during this audit's own adversarial-review pass:
         *  an earlier version of this constant, 64 MiB, was verified against
         *  44.1kHz only -- ~6.3 minutes there, but only ~5:50 at 48kHz,
         *  which would have REJECTED an ordinary ~6-minute 48kHz share-intent
         *  import that worked fine before this hardening pass, a real
         *  regression to legitimate use the backlog explicitly ruled out.)
         *
         *  This is NOT the peak memory a single import actually touches,
         *  though: decodeToPcm's own ByteArrayOutputStream/toByteArray()
         *  pair, and the subsequent waveform-peak float-array expansion
         *  (see WaveformPeaksExtractor.extractFromInterleavedPcm16), both
         *  transiently hold additional same-order-of-magnitude copies on
         *  top of this number -- real peak heap usage for one import can
         *  run roughly 3x this constant (~280MB worst case at this value).
         *  This app does not request android:largeHeap, so on the most
         *  memory-constrained real devices that transient peak could still
         *  risk an OutOfMemoryError -- an Error, not an Exception, which
         *  would NOT be caught by import()'s own `catch (e: Exception)` and
         *  would crash the process. Accepted as a known, documented
         *  tradeoff for THIS audit's scope (a per-import byte cap, replacing
         *  the PRE-EXISTING complete absence of any cap) rather than
         *  eliminating the transient-copy multiplier entirely, which would
         *  require a bigger restructure (streaming decoded PCM straight to
         *  disk instead of accumulating it in memory) -- worth a dedicated
         *  follow-up if real-world OOM reports on low-RAM devices ever
         *  surface, but out of scope for a "purely additive, no existing
         *  behavior changes for legitimate files" hardening pass. */
        const val DEFAULT_MAX_DECODED_PCM_BYTES: Long = 96L * 1024 * 1024 // 96 MiB
    }
}
