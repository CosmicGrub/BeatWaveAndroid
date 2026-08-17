package com.beatwave.android.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [AudioImporter.ImportError] -- no Robolectric, no
 * MediaExtractor/MediaCodec/Context needed. Post-v1 audits/upgrades backlog
 * item A3 (unit test coverage expansion): the full [AudioImporter.import]
 * decode pipeline is inherently Android-framework-bound (MediaExtractor,
 * MediaCodec, ContentResolver) and stays covered by on-device/instrumented
 * verification, but [AudioImporter.ImportError] itself is a plain sealed
 * `Exception` hierarchy with no such dependency -- its message/cause
 * contract (what [ArrangementViewModel] surfaces to the user in a Snackbar,
 * per the class doc's "user-presentable via Throwable.message") is worth
 * pinning directly.
 */
class AudioImporterErrorTest {

    @Test
    fun noAudioTrack_hasUserPresentableMessageAndNoCause() {
        val error = AudioImporter.ImportError.NoAudioTrack()

        assertEquals("The selected file doesn't contain an audio track.", error.message)
        assertNull(error.cause)
    }

    @Test
    fun decodeFailed_withoutCause_hasUserPresentableMessageAndNoCause() {
        val error = AudioImporter.ImportError.DecodeFailed()

        assertEquals(
            "Couldn't decode the selected audio file. It may be corrupt or in an unsupported format.",
            error.message
        )
        assertNull(error.cause)
    }

    @Test
    fun decodeFailed_withCause_preservesTheOriginalCause() {
        val originalCause = IllegalStateException("codec configure failed")

        val error = AudioImporter.ImportError.DecodeFailed(originalCause)

        assertSame(originalCause, error.cause)
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun ioFailure_withoutCause_hasUserPresentableMessageAndNoCause() {
        val error = AudioImporter.ImportError.IoFailure()

        assertEquals("Couldn't read the selected file.", error.message)
        assertNull(error.cause)
    }

    @Test
    fun ioFailure_withCause_preservesTheOriginalCause() {
        val originalCause = java.io.IOException("disk full")

        val error = AudioImporter.ImportError.IoFailure(originalCause)

        assertSame(originalCause, error.cause)
        assertTrue(error.message!!.isNotBlank())
    }

    @Test
    fun tooLarge_hasUserPresentableMessageAndNoCause() {
        // Post-v1 audit A1 (import size/DoS hardening).
        val error = AudioImporter.ImportError.TooLarge()

        assertEquals("The selected file is too large to import. Try a shorter clip.", error.message)
        assertNull(error.cause)
    }

    @Test
    fun everyImportError_hasANonBlankUserPresentableMessage() {
        // ArrangementViewModel's error-surfacing code reads `.message`
        // straight off whichever variant `import()` returns -- every
        // variant sharing the sealed base type is a compile-time guarantee,
        // but a blank/missing message would still silently break the
        // user-facing Snackbar, so assert it for every variant here.
        val errors: List<AudioImporter.ImportError> = listOf(
            AudioImporter.ImportError.NoAudioTrack(),
            AudioImporter.ImportError.DecodeFailed(),
            AudioImporter.ImportError.IoFailure(),
            AudioImporter.ImportError.TooLarge()
        )

        errors.forEach { error ->
            assertTrue(error.message!!.isNotBlank())
        }
    }
}
