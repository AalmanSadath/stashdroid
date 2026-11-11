package dev.aalman.stashdroid.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.aalman.stashdroid.repo.Repo
import dev.aalman.stashdroid.repo.SafStore
import dev.aalman.stashdroid.work.JobWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SnapshotInfo(val name: String, val createdAt: Long, val files: Int, val bytes: Long)

data class Picked(val uri: Uri, val label: String)

data class MainState(
    val allFilesAccess: Boolean = false,
    val folders: List<String> = emptyList(),
    val source: String = DEFAULT_SOURCE,
    val repo: Picked? = null,
    val snapshots: List<SnapshotInfo> = emptyList(),
    val snapshotsError: String? = null,
    val selectedSnapshot: String? = null,
    val restoreTarget: Picked? = null,
    val job: WorkInfo? = null,
)

const val DEFAULT_SOURCE = "DCIM"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("main", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(MainState())
    val state: StateFlow<MainState> = _state.asStateFlow()

    init {
        _state.update {
            it.copy(
                source = prefs.getString(PREF_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE,
                repo = prefs.getString(PREF_REPO, null)?.let(::picked),
                restoreTarget = prefs.getString(PREF_TARGET, null)?.let(::picked),
            )
        }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(JobWorker.UNIQUE_NAME).collect { infos ->
                val info = infos.lastOrNull()
                val finishedBackup = info?.state == WorkInfo.State.SUCCEEDED &&
                    info.outputData.getString(JobWorker.KEY_KIND) == JobWorker.KIND_BACKUP &&
                    _state.value.job?.state?.isFinished == false
                _state.update { it.copy(job = info) }
                if (finishedBackup) loadSnapshots()
            }
        }
        refresh()
    }

    /** Re-reads what can change while the app is in the background, such as all-files access. */
    fun refresh() {
        val granted = Environment.isExternalStorageManager()
        val folders = if (granted) {
            Environment.getExternalStorageDirectory().listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.map(File::getName)
                ?.sortedBy { it.lowercase() }
                .orEmpty()
        } else {
            emptyList()
        }
        _state.update { it.copy(allFilesAccess = granted, folders = folders) }
        loadSnapshots()
    }

    fun setSource(name: String) {
        prefs.edit { putString(PREF_SOURCE, name) }
        _state.update { it.copy(source = name) }
    }

    fun setRepo(uri: Uri) {
        persist(uri)
        prefs.edit { putString(PREF_REPO, uri.toString()) }
        _state.update { it.copy(repo = picked(uri.toString()), selectedSnapshot = null) }
        loadSnapshots()
    }

    fun setRestoreTarget(uri: Uri) {
        persist(uri)
        prefs.edit { putString(PREF_TARGET, uri.toString()) }
        _state.update { it.copy(restoreTarget = picked(uri.toString())) }
    }

    fun selectSnapshot(name: String) = _state.update { it.copy(selectedSnapshot = name) }

    fun backUp() {
        val s = _state.value
        val repo = s.repo ?: return
        JobWorker.enqueue(
            getApplication(),
            JobWorker.KEY_KIND to JobWorker.KIND_BACKUP,
            JobWorker.KEY_SOURCE to s.source,
            JobWorker.KEY_REPO to repo.uri.toString(),
        )
    }

    fun restore() {
        val s = _state.value
        val repo = s.repo ?: return
        val target = s.restoreTarget ?: return
        val snapshot = s.selectedSnapshot ?: s.snapshots.firstOrNull()?.name ?: return
        JobWorker.enqueue(
            getApplication(),
            JobWorker.KEY_KIND to JobWorker.KIND_RESTORE,
            JobWorker.KEY_SOURCE to s.source,
            JobWorker.KEY_REPO to repo.uri.toString(),
            JobWorker.KEY_SNAPSHOT to snapshot,
            JobWorker.KEY_TARGET to target.uri.toString(),
        )
    }

    fun verify() {
        val s = _state.value
        val target = s.restoreTarget ?: return
        JobWorker.enqueue(
            getApplication(),
            JobWorker.KEY_KIND to JobWorker.KIND_VERIFY,
            JobWorker.KEY_SOURCE to s.source,
            JobWorker.KEY_TARGET to target.uri.toString(),
        )
    }

    private fun loadSnapshots() {
        val repo = _state.value.repo ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val r = Repo.open(SafStore(getApplication<Application>().contentResolver, repo.uri))
                        ?: return@runCatching emptyList()
                    r.listSnapshots().map { name ->
                        val snap = r.readSnapshot(name)
                        val files = snap.sources.flatMap { it.files }
                        SnapshotInfo(name, snap.createdAt, files.size, files.sumOf { it.size })
                    }
                }
            }
            _state.update {
                it.copy(
                    snapshots = result.getOrDefault(emptyList()),
                    snapshotsError = result.exceptionOrNull()?.let { e -> e.message ?: e.javaClass.simpleName },
                )
            }
        }
    }

    private fun persist(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }

    /** A saved folder, labelled with the name its provider shows, or null if access was lost. */
    private fun picked(uriString: String): Picked? {
        val uri = uriString.toUri()
        val resolver = getApplication<Application>().contentResolver
        if (resolver.persistedUriPermissions.none { it.uri == uri }) return null
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val label = runCatching {
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: uriString
        return Picked(uri, label)
    }

    companion object {
        private const val PREF_SOURCE = "source"
        private const val PREF_REPO = "repo"
        private const val PREF_TARGET = "target"
    }
}
