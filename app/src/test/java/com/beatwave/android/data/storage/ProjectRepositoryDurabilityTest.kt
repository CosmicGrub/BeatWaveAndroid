package com.beatwave.android.data.storage

import com.beatwave.android.data.library.ImportedSampleIndex
import com.beatwave.android.data.model.Project
import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import com.beatwave.android.data.model.Track
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Audit regressions: overlapping saves of one project used to share a single
 * temp file (interleaved writes / FileNotFoundException crash), and an
 * unreadable project or imported-sample index used to be silently replaced.
 */
class ProjectRepositoryDurabilityTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "bw-durability-${System.nanoTime()}").also { it.mkdirs() }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun project(name: String) = Project(
        id = "p1", name = name, bpm = 90,
        tracks = (1..8).map { Track(slot = it) },
        createdAtEpochMs = 0L, modifiedAtEpochMs = 0L
    )

    @Test
    fun concurrentSavesOfOneProject_neverCrash_andLeaveAReadableFileAndNoTempFiles() {
        val repo = ProjectRepository(dir)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        repeat(8) { t ->
            pool.execute {
                try {
                    start.await()
                    repeat(40) { i -> repo.save(project("writer-$t-$i")) }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS))
        assertNull("a concurrent save threw: ${failure.get()}", failure.get())
        assertNotNull("final file must still parse", repo.load("p1"))
        assertEquals("no leftover temp files", emptyList<String>(), dir.list { _, n -> n.endsWith(".tmp") }!!.toList())
    }

    @Test
    fun unreadableProject_isQuarantinedNotOverwritten() {
        val repo = ProjectRepository(dir)
        File(dir, "p1.json").writeText("{ this is not json")
        assertNull(repo.load("p1"))
        assertTrue(repo.quarantineIfUnreadable("p1"))
        assertFalse("original must be moved aside", File(dir, "p1.json").exists())
        val kept = dir.listFiles { f -> f.name.startsWith("p1.json.corrupt-") }!!
        assertEquals(1, kept.size)
        assertEquals("{ this is not json", kept[0].readText())
    }

    @Test
    fun readableOrMissingProject_isNotQuarantined() {
        val repo = ProjectRepository(dir)
        assertFalse(repo.quarantineIfUnreadable("missing"))
        repo.save(project("ok"))
        assertFalse(repo.quarantineIfUnreadable("p1"))
        assertNotNull(repo.load("p1"))
    }

    @Test
    fun importedIndex_corruptFileIsKeptAside_notSilentlyReplaced() {
        File(dir, "index.json").writeText("garbage")
        val index = ImportedSampleIndex(dir)
        index.add(
            Sample("s1", "one", SampleCategory.VOCAL, SampleSource.ImportedFile("/x.wav"), 1000)
        )
        assertEquals(listOf("s1"), index.load().map { it.id })
        val kept = dir.listFiles { f -> f.name.startsWith("index.json.corrupt-") }!!
        assertEquals(1, kept.size)
        assertEquals("garbage", kept[0].readText())
    }
}
