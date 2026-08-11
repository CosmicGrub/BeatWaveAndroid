package com.beatwave.android.data.library

import android.content.Context
import com.beatwave.android.data.model.Sample
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted JSON list of imported [Sample] metadata (Phase 4 design item 5):
 * conceptually a sibling of [AssetLoopLibrary] but for user-imported samples
 * rather than the bundled loop pack, reusing Phase 1's Sample/SampleCategory/
 * SampleSource classes read-only via kotlinx.serialization -- the same way
 * [LoopManifestParser] already does for the bundled manifest.
 *
 * Deliberately takes a plain [File] (the storage directory) rather than an
 * Android [Context] so the core class stays Context-free, mirroring
 * [com.beatwave.android.data.storage.ProjectRepository]'s pattern; use
 * [forContext] to point it at real app storage.
 *
 * Stored at `filesDir/imported_samples/index.json` -- the same
 * `imported_samples` directory [AudioImporter] writes its decoded WAV files
 * into (see [AudioImporter.IMPORTED_SAMPLES_DIR_NAME]).
 */
class ImportedSampleIndex(private val storageDir: File) {

    private val indexFile: File get() = File(storageDir, INDEX_FILE_NAME)

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Returns every imported [Sample] currently on record, or an empty list
     *  if the index doesn't exist yet or fails to parse (e.g. a partially
     *  written file left behind by a process death mid-save) -- mirrors
     *  ProjectRepository's forgiving-on-corruption load style. */
    @Synchronized
    fun load(): List<Sample> {
        if (!indexFile.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Sample>>(indexFile.readText())
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        } catch (e: IOException) {
            emptyList()
        }
    }

    /** Appends [sample] to the persisted index (replacing any existing
     *  entry with the same id), writing via a temp file + atomic rename so
     *  a process kill mid-save can never leave a truncated index behind --
     *  mirrors ProjectRepository.save's approach. */
    @Synchronized
    fun add(sample: Sample) {
        val updated = load().filterNot { it.id == sample.id } + sample
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val tempFile = File(storageDir, "$INDEX_FILE_NAME.tmp")
        tempFile.writeText(json.encodeToString(updated))
        if (!tempFile.renameTo(indexFile)) {
            indexFile.writeText(tempFile.readText())
            tempFile.delete()
        }
    }

    companion object {
        private const val INDEX_FILE_NAME = "index.json"

        /** Factory for real Android use: stores the index under the app's
         *  private files directory (files/imported_samples/index.json). */
        fun forContext(context: Context): ImportedSampleIndex =
            ImportedSampleIndex(File(context.filesDir, AudioImporter.IMPORTED_SAMPLES_DIR_NAME))
    }
}
