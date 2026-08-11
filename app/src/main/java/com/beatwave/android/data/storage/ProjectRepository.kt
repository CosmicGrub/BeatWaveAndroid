package com.beatwave.android.data.storage

import android.content.Context
import com.beatwave.android.data.model.Project
import java.io.File
import java.io.IOException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Local JSON persistence for [Project]s, one file per project named
 * "<id>.json" inside [projectsDir]. Deliberately takes a plain [File]
 * rather than an Android [Context] so it's unit-testable on the plain JVM
 * without Robolectric; use [forContext] to point it at real app storage.
 */
class ProjectRepository(private val projectsDir: File) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /** Writes [project] to "<id>.json" in [projectsDir], creating the
     *  directory first if it doesn't exist yet. Overwrites any existing
     *  file for the same project id. Writes via a temp file + atomic rename
     *  so a process kill mid-save can never leave a truncated/partial
     *  project file behind for [load]/[list] to trip over. */
    fun save(project: Project) {
        if (!projectsDir.exists()) {
            projectsDir.mkdirs()
        }
        val target = projectFile(project.id)
        val tempFile = File(projectsDir, "${project.id}.json.tmp")
        tempFile.writeText(json.encodeToString(project))
        if (!tempFile.renameTo(target)) {
            // renameTo can fail across some filesystems/edge cases; fall
            // back to copy + delete so save() still succeeds.
            target.writeText(tempFile.readText())
            tempFile.delete()
        }
    }

    /** Returns the project with the given [id], or null if no such file
     *  exists or it fails to parse (e.g. a partially-written file left
     *  behind by a process death mid-save). */
    fun load(id: String): Project? = decodeProjectFile(projectFile(id))

    /** Returns every project currently stored in [projectsDir]. Any file
     *  that fails to parse is skipped rather than aborting the whole
     *  listing. */
    fun list(): List<Project> {
        val files = projectsDir.listFiles { candidate -> candidate.isFile && candidate.extension == "json" }
            ?: return emptyList()
        return files.mapNotNull { file -> decodeProjectFile(file) }
    }

    private fun decodeProjectFile(file: File): Project? {
        if (!file.exists()) return null
        return try {
            json.decodeFromString<Project>(file.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: IOException) {
            null
        }
    }

    /** Deletes the project with the given [id], if present. No-op otherwise. */
    fun delete(id: String) {
        val file = projectFile(id)
        if (file.exists()) {
            file.delete()
        }
    }

    private fun projectFile(id: String): File = File(projectsDir, "$id.json")

    companion object {
        /** Factory for real Android use: stores projects under the app's
         *  private files directory (files/projects/). */
        fun forContext(context: Context): ProjectRepository =
            ProjectRepository(File(context.filesDir, "projects"))
    }
}
