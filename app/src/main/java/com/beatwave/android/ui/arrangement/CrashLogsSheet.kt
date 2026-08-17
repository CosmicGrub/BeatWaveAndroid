package com.beatwave.android.ui.arrangement

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * Post-v1 audit A2 (crash resilience & diagnostics): a minimal "list +
 * share" surface for [CrashLogger]'s reports, mirroring [ProjectPickerSheet]'s
 * established sheet/card/testTag shape rather than introducing a new UI
 * pattern for what's conceptually the same "browse a list, act on an item"
 * kind of surface. Deliberately no delete/clear action -- the backlog item
 * scopes this to "list + share via the existing FileProvider/share-sheet
 * infrastructure", and [CrashLogger.pruneOldLogs] already bounds on-disk
 * growth on its own, so there's nothing a user needs to manually clean up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashLogsSheet(
    logs: List<CrashLogSummary>,
    onDismiss: () -> Unit,
    onShare: (absolutePath: String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                // Post-v1 audit A4: announces the sheet's identity the
                // instant it opens.
                .semantics { paneTitle = "Crash Logs" }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Crash Logs", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("crash_logs_close_button")) {
                    Text("Close")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (logs.isEmpty()) {
                Text(
                    "No crash logs -- nothing's crashed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp).testTag("crash_logs_empty")
                )
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(logs, key = { it.absolutePath }) { log ->
                        CrashLogRow(log = log, onShareTap = { onShare(log.absolutePath) })
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CrashLogRow(log: CrashLogSummary, onShareTap: () -> Unit) {
    // Same "no whole-row clickable" reasoning as ProjectPickerSheet's
    // ProjectRow -- Share is its own explicit sibling TextButton, not a
    // Card-wide click target.
    Card(Modifier.fillMaxWidth().testTag("crash_log_row_${log.fileName}")) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Post-v1 audit A4: without merging, TalkBack reads the
            // timestamp and size as two disconnected stops per entry.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(formatCrashTimestamp(log.timestampEpochMs), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${log.sizeBytes} bytes",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Post-v1 audit A4: with multiple logs listed, a bare "Share"
            // announcement doesn't say which report is about to be shared.
            // Size appended (found during this audit's adversarial-review
            // pass): the displayed timestamp format has no seconds, so two
            // crashes within the same minute would otherwise still collide
            // -- size is already visible in this same row and, unlike the
            // exact file path, is meaningful when read aloud.
            TextButton(
                onClick = onShareTap,
                modifier = Modifier
                    .semantics {
                        contentDescription =
                            "Share crash log from ${formatCrashTimestamp(log.timestampEpochMs)}, ${log.sizeBytes} bytes"
                    }
                    .testTag("share_crash_log_${log.fileName}")
            ) {
                Text("Share")
            }
        }
    }
}

private fun formatCrashTimestamp(epochMs: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochMs))
