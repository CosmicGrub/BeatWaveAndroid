package com.beatwave.android.data.library

import com.beatwave.android.data.model.Sample
import com.beatwave.android.data.model.SampleCategory
import com.beatwave.android.data.model.SampleSource
import java.io.InputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Parses the bundled loop pack's `loops/manifest.json` into a list of
 * [Sample]s. Deliberately takes the manifest content as a plain [String] (or
 * [InputStream]) rather than reading assets itself, so this is testable with
 * plain JUnit4 on the JVM -- no Robolectric, no `android.content.res.AssetManager`.
 *
 * See [AssetLoopLibrary] for the thin Android-facing adapter that actually
 * reads the manifest off disk via the app's `AssetManager`.
 */
object LoopManifestParser {

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /**
     * One entry in the manifest JSON array. Field names/shape match the
     * bundled `app/src/main/assets/loops/manifest.json` exactly, e.g.:
     * `{ "id": "kick_basic_01", "name": "Basic Kick", "category": "DRUMS",
     *    "assetPath": "loops/kick_basic_01.wav", "durationMs": 2000, "bpm": 90 }`
     */
    @Serializable
    private data class ManifestEntry(
        val id: String,
        val name: String,
        val category: SampleCategory,
        val assetPath: String,
        val durationMs: Long,
        val bpm: Int = 0
    )

    /** Parses [manifestJson] (the raw JSON array text) into [Sample]s. */
    fun parse(manifestJson: String): List<Sample> {
        val entries = json.decodeFromString<List<ManifestEntry>>(manifestJson)
        return entries.map { entry ->
            Sample(
                id = entry.id,
                name = entry.name,
                category = entry.category,
                source = SampleSource.BundledAsset(assetPath = entry.assetPath),
                durationMs = entry.durationMs
            )
        }
    }

    /** Convenience overload: reads all of [manifestStream] as UTF-8 text and parses it. */
    fun parse(manifestStream: InputStream): List<Sample> =
        parse(manifestStream.readBytes().toString(Charsets.UTF_8))
}
