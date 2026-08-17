package com.beatwave.android.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.beatwave.android.AudioEngineBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for post-v1 audit/upgrade D1 (2026-08-17
 * engine-upgrades backlog, "SampleBank cache eviction"): SampleBank's decode
 * cache used to grow for the whole process lifetime -- clear() was defined
 * but had zero call sites anywhere, so nothing ever evicted an entry. This
 * drives the offline/test-only native engine (mandate 10) through real
 * decode+cache traffic against the bundled loop pack to verify two things
 * end to end, on real device hardware, not just by reading the diff:
 *
 *  1. The cache genuinely stays bounded under a real eviction budget, and
 *     evicts the LEAST-recently-used entry first, not simply insertion
 *     order or an arbitrary one.
 *  2. Evicting a cache ENTRY never breaks a block that's already resolved
 *     into a committed PlaybackScore -- that block holds its own
 *     shared_ptr<const SampleBuffer> independent of SampleBank's own map
 *     (see SampleBank.h's class doc comment), so it must keep resolving and
 *     rendering correctly even after its asset path is no longer cached.
 */
@RunWith(AndroidJUnit4::class)
class SampleBankEvictionTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun tinyBudget_neverCachesMoreThanOneEntryAtOnce_andEarlyBlockSurvivesEviction() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            // A 1-byte budget: every real decoded sample is far larger than
            // this, so every getOrLoad after the very first one must evict
            // everything else down to the single entry SampleBank's "never
            // evict the last one" guard always preserves.
            AudioEngineBridge.nativeTestSetSampleBankMaxCacheBytes(handle, 1L)

            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)

            // Schedule ALL 8 bundled assets onto the SAME in-progress score
            // before committing (rather than committing after just the
            // first one and continuing to add blocks afterward) --
            // ScoreBuilder::build() moves its ResolvedTrack list out, which
            // would silently empty track 1 out from under any addLoopBlock
            // call made between commits without a fresh addTrack() first.
            // One single commit at the end, with all 8 blocks already
            // resolved, sidesteps that entirely.
            for (i in ALL_ASSET_PATHS.indices) {
                val scheduled = AudioEngineBridge.nativeTestAddLoopBlock(
                    handle, 1, ALL_ASSET_PATHS[i], i * BLOCK_LENGTH_GRID_UNITS, BLOCK_LENGTH_GRID_UNITS,
                    1.0f, 0L, -1L, 0.0f
                )
                assertTrue("expected block for ${ALL_ASSET_PATHS[i]} to be scheduled", scheduled)
                assertEquals(
                    "cache should never hold more than 1 entry under a 1-byte budget " +
                        "(just loaded ${ALL_ASSET_PATHS[i]}, entry #$i)",
                    1, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle)
                )
            }

            // Commit now, with all 8 blocks in the score -- this is the
            // block-0 (kick, loaded first and evicted from the cache seven
            // loads ago) whose survival we verify below.
            AudioEngineBridge.nativeTestCommitProject(handle)

            // The safety property: the FIRST block (index 0, just
            // committed) referenced an asset path whose cache entry was
            // evicted many loads ago. It must still resolve and render
            // correctly -- its ResolvedLoopBlock captured its own
            // shared_ptr<const SampleBuffer> at commit time, independent of
            // whatever SampleBank's cache map currently holds.
            val blockStartFrame = AudioEngineBridge.nativeTestGetBlockStartFrame(handle, 1, 0)
            val loopContentLengthFrames = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, 1, 0)
            assertTrue("expected the long-evicted first block to still resolve a blockStartFrame", blockStartFrame >= 0)
            assertTrue(
                "expected the long-evicted first block to still resolve a positive loop content length",
                loopContentLengthFrames > 0
            )

            // Actually advance the transport and confirm the block's
            // derived position keeps behaving like real, valid playback
            // (not garbage from a dangling reference) for a few loop
            // cycles' worth of frames.
            var totalAdvanced = 0L
            val framesToAdvance = loopContentLengthFrames * 3
            while (totalAdvanced < framesToAdvance) {
                AudioEngineBridge.nativeTestAdvanceOffline(handle, BURST_FRAMES)
                totalAdvanced += BURST_FRAMES
                val loopLocalFrame = AudioEngineBridge.nativeTestGetLoopLocalFrame(handle, 1, 0)
                assertTrue(
                    "expected a valid in-range loopLocalFrame after the block's cache entry was evicted, got $loopLocalFrame",
                    loopLocalFrame in 0 until loopContentLengthFrames
                )
            }
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    @Test
    fun evictsLeastRecentlyUsedEntry_notInsertionOrder_andSurvivorStaysCorrect() {
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)
            AudioEngineBridge.nativeTestAddTrack(handle, 2)

            // Measure X (kick, ~2667ms) and Y (bass, ~5333ms) real cache
            // byte sizes under the huge production-default budget (no
            // eviction happens here), so later assertions can distinguish
            // "X survived" from "Y survived" by exact byte count rather than
            // assuming anything about relative asset sizes. X and Y go on
            // SEPARATE tracks, both starting at grid unit 0 (rather than the
            // same track at staggered start positions), so both blocks stay
            // continuously active from transport frame 0 -- this is purely
            // about cache traffic and the post-eviction render-position
            // check below, not about realistic non-overlapping placement.
            assertTrue(
                "expected block X to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(handle, 1, X_PATH, 0, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f)
            )
            val bytesAfterX = AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)
            val sizeX = bytesAfterX

            assertTrue(
                "expected block Y to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(handle, 2, Y_PATH, 0, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f)
            )
            val bytesAfterY = AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)
            val sizeY = bytesAfterY - bytesAfterX
            assertTrue("expected X and Y to have distinguishably different cache footprints", sizeX != sizeY)

            // X = track 1 block index 0, Y = track 2 block index 0 -- commit
            // now so both resolve into a real PlaybackScore before Y gets
            // evicted below.
            AudioEngineBridge.nativeTestCommitProject(handle)

            // ScoreBuilder::build() moves its ResolvedTrack list out from
            // under the builder -- re-add track 1 so the further
            // addLoopBlock calls below (used purely to drive more
            // SampleBank cache traffic, not to extend the already-committed
            // score) have a track to resolve against.
            AudioEngineBridge.nativeTestAddTrack(handle, 1)

            // Budget fits exactly X + Y (nothing evicted yet).
            AudioEngineBridge.nativeTestSetSampleBankMaxCacheBytes(handle, sizeX + sizeY)
            assertEquals(2, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle))
            assertEquals(sizeX + sizeY, AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle))

            // Re-touch X: another block referencing the SAME asset path is a
            // cache HIT, refreshing X's recency without growing the cache.
            assertTrue(
                "expected the X re-touch block to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(
                    handle, 1, X_PATH, 2 * BLOCK_LENGTH_GRID_UNITS, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f
                )
            )
            assertEquals(
                "re-touching X must be a cache hit, not a duplicate entry",
                2, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle)
            )
            assertEquals(
                "re-touching X must not change total cached bytes",
                sizeX + sizeY, AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)
            )

            // Now load Z (snare, ~2667ms like X) -- pushes total over
            // budget. Y is the least-recently-used entry (X was just
            // refreshed above), so Y -- not X -- must be the one evicted.
            // This relies on Z's real cache footprint (kick-class, ~2667ms)
            // staying below Y's (bass-class, ~5333ms) so evicting Y alone is
            // enough to fit Z -- true for the bundled loop pack today (kick/
            // snare ~235KB on disk vs. bass ~470KB); if that ever stops
            // holding, eviction would correctly cascade into evicting X too,
            // and this test would fail loudly at the entryCount==2
            // assertion below rather than silently pass on a wrong
            // assumption.
            assertTrue(
                "expected block Z to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(
                    handle, 1, Z_PATH, 3 * BLOCK_LENGTH_GRID_UNITS, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f
                )
            )
            assertEquals(
                "expected exactly 2 entries after evicting the LRU one (Y) to admit Z",
                2, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle)
            )
            val totalAfterZ = AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)

            // Re-touch X again. This addLoopBlock call succeeding doesn't by
            // itself prove X is still cached (it would return true equally
            // whether X was a cache hit or had to be re-decoded after being
            // wrongly evicted) -- the real "X survived, Y was evicted" proof
            // is the byte-count comparison in the assertEquals right below.
            assertTrue(
                "expected the second X re-touch block to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(
                    handle, 1, X_PATH, 4 * BLOCK_LENGTH_GRID_UNITS, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f
                )
            )
            assertEquals(
                "X must still be cached after Z's insertion evicted the LRU entry (Y) -- " +
                    "re-touching X must not grow total cached bytes",
                totalAfterZ, AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)
            )
            assertEquals(2, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle))

            // Safety property: Y's block (track 2, index 0, committed before
            // Y was evicted) must still resolve and render correctly even
            // though its own cache entry is long gone.
            val yBlockStart = AudioEngineBridge.nativeTestGetBlockStartFrame(handle, 2, 0)
            val yLoopContentLength = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, 2, 0)
            assertTrue("expected evicted-but-committed Y block to still resolve a blockStartFrame", yBlockStart >= 0)
            assertTrue(
                "expected evicted-but-committed Y block to still resolve a positive loop content length",
                yLoopContentLength > 0
            )

            var totalAdvanced = 0L
            val framesToAdvance = yLoopContentLength * 3
            while (totalAdvanced < framesToAdvance) {
                AudioEngineBridge.nativeTestAdvanceOffline(handle, BURST_FRAMES)
                totalAdvanced += BURST_FRAMES
                val loopLocalFrame = AudioEngineBridge.nativeTestGetLoopLocalFrame(handle, 2, 0)
                assertTrue(
                    "expected a valid in-range loopLocalFrame for the evicted-but-committed Y block, got $loopLocalFrame",
                    loopLocalFrame in 0 until yLoopContentLength
                )
            }
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    @Test
    fun shrinkingBudgetViaSetMaxCacheBytes_evictsExistingEntriesImmediately_andSurvivorStaysCorrect() {
        // The other two tests only ever observe eviction firing from
        // getOrLoad's insert path. This one exercises the OTHER trigger:
        // setMaxCacheBytes shrinking the budget below what's already
        // cached must evict immediately, inside that very call, not wait
        // for a later insert.
        val handle = AudioEngineBridge.nativeTestCreateOfflineEngine(context.assets, SAMPLE_RATE_HZ)
        try {
            AudioEngineBridge.nativeTestBeginProject(handle, BPM)
            AudioEngineBridge.nativeTestAddTrack(handle, 1)
            AudioEngineBridge.nativeTestAddTrack(handle, 2)

            // X (track 1) then Y (track 2), both under the huge default
            // budget. X is touched first (older tick) and never re-touched,
            // so X -- not Y -- is unambiguously the least-recently-used
            // entry regardless of either asset's byte size.
            assertTrue(
                "expected block X to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(handle, 1, X_PATH, 0, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f)
            )
            val sizeX = AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)

            assertTrue(
                "expected block Y to be scheduled",
                AudioEngineBridge.nativeTestAddLoopBlock(handle, 2, Y_PATH, 0, BLOCK_LENGTH_GRID_UNITS, 1.0f, 0L, -1L, 0.0f)
            )
            val sizeY = AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle) - sizeX
            assertEquals(2, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle))

            // Commit now, with X (track 1, index 0) already in the score,
            // so its survival across the shrink-triggered eviction below
            // can be verified the same way the other tests verify survival
            // across insert-triggered eviction.
            AudioEngineBridge.nativeTestCommitProject(handle)

            // Shrink the budget to fit exactly Y alone. X (the LRU entry)
            // must be evicted IMMEDIATELY by this call itself.
            AudioEngineBridge.nativeTestSetSampleBankMaxCacheBytes(handle, sizeY)
            assertEquals(
                "expected X (the LRU entry) to be evicted immediately by the budget shrink itself",
                1, AudioEngineBridge.nativeTestGetSampleBankCacheEntryCount(handle)
            )
            assertEquals(
                "expected only Y's bytes to remain cached after the shrink evicted X",
                sizeY, AudioEngineBridge.nativeTestGetSampleBankCacheBytes(handle)
            )

            // Safety property via this new trigger path: X's committed
            // block must still resolve and render correctly even though a
            // budget shrink -- not a later insert -- is what evicted it.
            val xBlockStart = AudioEngineBridge.nativeTestGetBlockStartFrame(handle, 1, 0)
            val xLoopContentLength = AudioEngineBridge.nativeTestGetLoopContentLengthFrames(handle, 1, 0)
            assertTrue("expected the shrink-evicted X block to still resolve a blockStartFrame", xBlockStart >= 0)
            assertTrue(
                "expected the shrink-evicted X block to still resolve a positive loop content length",
                xLoopContentLength > 0
            )

            var totalAdvanced = 0L
            val framesToAdvance = xLoopContentLength * 3
            while (totalAdvanced < framesToAdvance) {
                AudioEngineBridge.nativeTestAdvanceOffline(handle, BURST_FRAMES)
                totalAdvanced += BURST_FRAMES
                val loopLocalFrame = AudioEngineBridge.nativeTestGetLoopLocalFrame(handle, 1, 0)
                assertTrue(
                    "expected a valid in-range loopLocalFrame for the shrink-evicted X block, got $loopLocalFrame",
                    loopLocalFrame in 0 until xLoopContentLength
                )
            }
        } finally {
            AudioEngineBridge.nativeTestDestroyOfflineEngine(handle)
        }
    }

    companion object {
        private const val SAMPLE_RATE_HZ = 48000
        private const val BPM = 90
        private const val BURST_FRAMES = 960

        // framesPerGridUnit at 90bpm/48kHz = 8000.0 exactly (see
        // MixEngineDriftTest's identical comment) -- 2000 grid units is
        // comfortably longer than any single loop's real content length, so
        // every block below stays "just started" for the handful of frames
        // this test actually advances.
        private const val BLOCK_LENGTH_GRID_UNITS = 2000

        // From the bundled Phase 1 loop pack manifest
        // (app/src/main/assets/loops/manifest.json).
        private const val X_PATH = "loops/kick_basic_01.wav" // durationMs=2667
        private const val Y_PATH = "loops/bass_riff_01.wav" // durationMs=5333 -- deliberately a different duration class than X/Z
        private const val Z_PATH = "loops/snare_basic_01.wav" // durationMs=2667

        private val ALL_ASSET_PATHS = listOf(
            "loops/kick_basic_01.wav",
            "loops/snare_basic_01.wav",
            "loops/bass_riff_01.wav",
            "loops/bass_riff_02.wav",
            "loops/synth_chord_01.wav",
            "loops/synth_arp_01.wav",
            "loops/vocal_oh_01.wav",
            "loops/vocal_ah_01.wav"
        )
    }
}
