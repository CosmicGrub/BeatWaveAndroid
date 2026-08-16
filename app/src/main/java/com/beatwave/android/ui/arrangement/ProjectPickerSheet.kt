package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Multiple-projects upgrade: a project picker mirroring
 * [LoopLibraryBottomSheet]'s established sheet/card/testTag shape exactly,
 * rather than introducing a new UI pattern for what's conceptually the same
 * kind of "browse a list, act on an item" surface.
 *
 * All actions ([onOpen]/[onCreate]/[onRename]/[onDelete]) are thin
 * pass-throughs to [ArrangementViewModel]'s own project-management
 * functions -- this Composable owns only its own transient dialog-visibility
 * state (which dialog, if any, is currently showing), never project data
 * itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPickerSheet(
    projects: List<ProjectSummary>,
    activeProjectId: String?,
    onDismiss: () -> Unit,
    onOpen: (id: String) -> Unit,
    onCreate: (name: String) -> Unit,
    onRename: (id: String, newName: String) -> Unit,
    onDelete: (id: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var renamingProject by remember { mutableStateOf<ProjectSummary?>(null) }
    var deletingProject by remember { mutableStateOf<ProjectSummary?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Projects", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("project_picker_close_button")) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showNewProjectDialog = true },
                modifier = Modifier.testTag("new_project_button")
            ) {
                Text("New Project")
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(projects, key = { it.id }) { project ->
                    ProjectRow(
                        project = project,
                        isActive = project.id == activeProjectId,
                        onOpen = { onOpen(project.id) },
                        onRenameTap = { renamingProject = project },
                        onDeleteTap = { deletingProject = project }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showNewProjectDialog) {
        ProjectNameDialog(
            title = "New Project",
            confirmLabel = "Create",
            initialValue = "",
            onDismiss = { showNewProjectDialog = false },
            onConfirm = { name ->
                showNewProjectDialog = false
                onCreate(name)
            }
        )
    }

    renamingProject?.let { project ->
        ProjectNameDialog(
            title = "Rename Project",
            confirmLabel = "Rename",
            initialValue = project.name,
            onDismiss = { renamingProject = null },
            onConfirm = { newName ->
                renamingProject = null
                onRename(project.id, newName)
            }
        )
    }

    deletingProject?.let { project ->
        AlertDialog(
            onDismissRequest = { deletingProject = null },
            title = { Text("Delete \"${project.name}\"?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingProject = null
                        onDelete(project.id)
                    },
                    modifier = Modifier.testTag("confirm_delete_project_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProject = null }, modifier = Modifier.testTag("cancel_delete_project_button")) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ProjectRow(
    project: ProjectSummary,
    isActive: Boolean,
    onOpen: () -> Unit,
    onRenameTap: () -> Unit,
    onDeleteTap: () -> Unit
) {
    // Deliberately NO whole-row clickable here (unlike a first draft of this
    // Composable) -- a Card.clickable{} spanning the entire row while also
    // containing nested TextButtons is exactly the "outer clickable
    // overlaps a nested one" shape that caused a real, confirmed bug in
    // TrackHeader during Phase 6 (a tap landing on the outer element instead
    // of the intended nested one). "Open" is its own explicit sibling
    // TextButton instead, matching LoopLibraryCard's already-proven-safe
    // "no whole-row click target, only specific buttons are clickable"
    // pattern exactly.
    Card(Modifier.fillMaxWidth().testTag("project_row_${project.id}")) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(project.name, style = MaterialTheme.typography.bodyLarge)
                    if (isActive) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "(current)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    formatModifiedDate(project.modifiedAtEpochMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpen, modifier = Modifier.testTag("open_project_${project.id}")) {
                Text("Open")
            }
            TextButton(onClick = onRenameTap, modifier = Modifier.testTag("rename_project_${project.id}")) {
                Text("Rename")
            }
            TextButton(onClick = onDeleteTap, modifier = Modifier.testTag("delete_project_${project.id}")) {
                Text("Delete")
            }
        }
    }
}

/** Shared text-input dialog for both "New Project" and "Rename Project" --
 *  same shape, different title/confirm label/starting value. */
@Composable
private fun ProjectNameDialog(
    title: String,
    confirmLabel: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("project_name_input")
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, modifier = Modifier.testTag("project_name_confirm_button")) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("project_name_cancel_button")) {
                Text("Cancel")
            }
        }
    )
}

private fun formatModifiedDate(epochMs: Long): String =
    "Modified " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
