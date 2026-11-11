package dev.aalman.stashdroid.ui

import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import dev.aalman.stashdroid.R
import dev.aalman.stashdroid.work.JobWorker
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(state: MainState, vm: MainViewModel, onRequestNotifications: () -> Unit) {
    val context = LocalContext.current
    val pickRepo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setRepo)
    }
    val pickTarget = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::setRestoreTarget)
    }
    val busy = state.job?.state?.isFinished == false

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) }) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.allFilesAccess) {
                item {
                    Section(stringResource(R.string.access_title)) {
                        Text(stringResource(R.string.access_body))
                        Button(onClick = {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        }) { Text(stringResource(R.string.access_button)) }
                    }
                }
            }

            item {
                Section(stringResource(R.string.source_title)) {
                    SourcePicker(state.folders, state.source, enabled = state.allFilesAccess && !busy, onPick = vm::setSource)
                }
            }

            item {
                Section(stringResource(R.string.repo_title)) {
                    Text(state.repo?.label ?: stringResource(R.string.not_chosen), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickRepo.launch(null) }, enabled = !busy) {
                            Text(stringResource(R.string.choose_folder))
                        }
                        Button(
                            onClick = { onRequestNotifications(); vm.backUp() },
                            enabled = state.allFilesAccess && state.repo != null && !busy,
                        ) { Text(stringResource(R.string.back_up_now)) }
                    }
                }
            }

            state.job?.let { job -> item { JobCard(job) } }

            item {
                Section(stringResource(R.string.snapshots_title)) {
                    when {
                        state.snapshotsError != null -> Text(state.snapshotsError, color = MaterialTheme.colorScheme.error)
                        state.snapshots.isEmpty() -> Text(stringResource(R.string.no_snapshots))
                    }
                    val selected = state.selectedSnapshot ?: state.snapshots.firstOrNull()?.name
                    for (snap in state.snapshots) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = snap.name == selected, enabled = !busy, role = Role.RadioButton) {
                                    vm.selectSnapshot(snap.name)
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = snap.name == selected, onClick = null, enabled = !busy)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(formatDate(snap.createdAt))
                                Text(
                                    pluralStringResource(R.plurals.file_count, snap.files, snap.files) + " · " +
                                        Formatter.formatShortFileSize(context, snap.bytes),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Section(stringResource(R.string.restore_title)) {
                    Text(stringResource(R.string.restore_body))
                    Text(state.restoreTarget?.label ?: stringResource(R.string.not_chosen), style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { pickTarget.launch(null) }, enabled = !busy) {
                            Text(stringResource(R.string.choose_folder))
                        }
                        Button(
                            onClick = { onRequestNotifications(); vm.restore() },
                            enabled = state.repo != null && state.restoreTarget != null && state.snapshots.isNotEmpty() && !busy,
                        ) { Text(stringResource(R.string.restore_button)) }
                    }
                    OutlinedButton(
                        onClick = vm::verify,
                        enabled = state.allFilesAccess && state.restoreTarget != null && !busy,
                    ) { Text(stringResource(R.string.verify_button)) }
                    Text(stringResource(R.string.verify_body, state.source), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourcePicker(folders: List<String>, source: String, enabled: Boolean, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (enabled) open = it }) {
        OutlinedTextField(
            value = source,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(stringResource(R.string.source_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (folder in folders) {
                DropdownMenuItem(text = { Text(folder) }, onClick = { onPick(folder); open = false })
            }
        }
    }
}

@Composable
private fun JobCard(job: WorkInfo) {
    val context = LocalContext.current
    val running = !job.state.isFinished
    val data = if (running) job.progress else job.outputData
    val kind = data.getString(JobWorker.KEY_KIND)
    val title = when (kind) {
        JobWorker.KIND_BACKUP -> R.string.job_backup
        JobWorker.KIND_RESTORE -> R.string.job_restore
        JobWorker.KIND_VERIFY -> R.string.job_verify
        else -> R.string.job_starting
    }
    Section(stringResource(title)) {
        val filesDone = data.getInt(JobWorker.KEY_FILES_DONE, 0)
        val filesTotal = data.getInt(JobWorker.KEY_FILES_TOTAL, 0)
        val bytesDone = data.getLong(JobWorker.KEY_BYTES_DONE, 0)
        val bytesTotal = data.getLong(JobWorker.KEY_BYTES_TOTAL, 0)
        when (job.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED, WorkInfo.State.RUNNING -> {
                val fraction = when {
                    bytesTotal > 0 -> bytesDone.toFloat() / bytesTotal
                    filesTotal > 0 -> filesDone.toFloat() / filesTotal
                    else -> null
                }
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text(stringResource(R.string.progress_files, filesDone, filesTotal))
                if (bytesTotal > 0) {
                    Text(
                        stringResource(
                            R.string.progress_bytes,
                            Formatter.formatShortFileSize(context, bytesDone),
                            Formatter.formatShortFileSize(context, bytesTotal),
                        ),
                    )
                }
                if (kind == JobWorker.KIND_BACKUP) {
                    Text(
                        stringResource(
                            R.string.progress_chunks,
                            data.getInt(JobWorker.KEY_NEW_CHUNKS, 0),
                            data.getInt(JobWorker.KEY_REUSED_CHUNKS, 0),
                        ),
                    )
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                val problems = data.getInt(JobWorker.KEY_PROBLEM_COUNT, 0)
                when (kind) {
                    JobWorker.KIND_BACKUP -> {
                        Text(stringResource(R.string.backup_done, filesDone, Formatter.formatShortFileSize(context, bytesDone)))
                        Text(
                            stringResource(
                                R.string.backup_done_chunks,
                                data.getInt(JobWorker.KEY_NEW_CHUNKS, 0),
                                data.getInt(JobWorker.KEY_REUSED_CHUNKS, 0),
                                data.getInt(JobWorker.KEY_UNCHANGED, 0),
                            ),
                        )
                    }
                    JobWorker.KIND_RESTORE ->
                        Text(stringResource(R.string.restore_done, filesDone, Formatter.formatShortFileSize(context, bytesDone)))
                    JobWorker.KIND_VERIFY -> {
                        val matching = data.getInt(JobWorker.KEY_MATCHING, 0)
                        Text(
                            if (problems == 0) stringResource(R.string.verify_all_match, matching)
                            else stringResource(R.string.verify_some_differ, matching, problems),
                            color = if (problems == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (problems > 0) {
                    if (kind != JobWorker.KIND_VERIFY) {
                        Text(pluralStringResource(R.plurals.problem_count, problems, problems), color = MaterialTheme.colorScheme.error)
                    }
                    Text(data.getString(JobWorker.KEY_PROBLEMS).orEmpty(), style = MaterialTheme.typography.bodySmall)
                }
            }
            WorkInfo.State.FAILED -> Text(
                stringResource(R.string.job_failed, data.getString(JobWorker.KEY_ERROR).orEmpty()),
                color = MaterialTheme.colorScheme.error,
            )
            WorkInfo.State.CANCELLED -> Text(stringResource(R.string.job_cancelled))
        }
    }
}

@Composable
private fun formatDate(millis: Long): String {
    val context = LocalContext.current
    return DateFormat.getMediumDateFormat(context).format(Date(millis)) + " " +
        DateFormat.getTimeFormat(context).format(Date(millis))
}
