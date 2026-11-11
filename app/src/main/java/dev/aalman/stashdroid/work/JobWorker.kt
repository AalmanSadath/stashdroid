package dev.aalman.stashdroid.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.aalman.stashdroid.R
import dev.aalman.stashdroid.repo.BackupEngine
import dev.aalman.stashdroid.repo.FileStore
import dev.aalman.stashdroid.repo.Repo
import dev.aalman.stashdroid.repo.RestoreEngine
import dev.aalman.stashdroid.repo.SafStore
import dev.aalman.stashdroid.repo.TargetNotEmptyException
import dev.aalman.stashdroid.repo.Verifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs one backup, restore or verify as a foreground job, so a large folder keeps going with the
 * screen off. Only one job runs at a time.
 */
class JobWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val kind get() = inputData.getString(KEY_KIND) ?: ""
    private var lastProgress = 0L

    override suspend fun doWork(): Result {
        setForeground(foregroundInfo(applicationContext.getString(R.string.job_starting)))
        return withContext(Dispatchers.IO) { runJob() }
    }

    private fun runJob(): Result {
        return try {
            when (kind) {
                KIND_BACKUP -> backup()
                KIND_RESTORE -> restore()
                KIND_VERIFY -> verify()
                else -> fail("Unknown job $kind")
            }
        } catch (e: TargetNotEmptyException) {
            fail(applicationContext.getString(R.string.restore_not_empty))
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun sourceStore(name: String) = FileStore(File(Environment.getExternalStorageDirectory(), name))

    private fun safStore(key: String) = SafStore(applicationContext.contentResolver, Uri.parse(inputData.getString(key)))

    private fun backup(): Result {
        val source = inputData.getString(KEY_SOURCE)!!
        val repo = Repo.openOrCreate(safStore(KEY_REPO))
        val engine = BackupEngine(repo, device = "${Build.MANUFACTURER} ${Build.MODEL}", appVersion = appVersion())
        val origin = File(Environment.getExternalStorageDirectory(), source).path
        val result = engine.backup(sourceStore(source), source, origin) { p ->
            report(
                workDataOf(
                    KEY_KIND to kind,
                    KEY_FILES_DONE to p.filesDone, KEY_FILES_TOTAL to p.filesTotal,
                    KEY_BYTES_DONE to p.bytesDone, KEY_BYTES_TOTAL to p.bytesTotal,
                    KEY_NEW_CHUNKS to p.newChunks, KEY_REUSED_CHUNKS to p.reusedChunks,
                ),
                p.bytesDone, p.bytesTotal,
            )
        }
        val p = result.progress
        return Result.success(
            workDataOf(
                KEY_KIND to kind,
                KEY_SNAPSHOT to result.snapshotName,
                KEY_FILES_DONE to p.filesDone, KEY_BYTES_DONE to p.bytesDone,
                KEY_NEW_CHUNKS to p.newChunks, KEY_REUSED_CHUNKS to p.reusedChunks,
                KEY_UNCHANGED to p.unchangedFiles,
                KEY_PROBLEM_COUNT to result.errors.size,
                KEY_PROBLEMS to problems(result.errors.map { "${it.first}: ${it.second}" }),
            ),
        )
    }

    private fun restore(): Result {
        val source = inputData.getString(KEY_SOURCE)!!
        val repo = Repo.open(safStore(KEY_REPO)) ?: return fail(applicationContext.getString(R.string.no_backup_here))
        val snapshot = repo.readSnapshot(inputData.getString(KEY_SNAPSHOT)!!)
        val result = RestoreEngine(repo).restore(snapshot, source, safStore(KEY_TARGET)) { p ->
            report(
                workDataOf(
                    KEY_KIND to kind,
                    KEY_FILES_DONE to p.filesDone, KEY_FILES_TOTAL to p.filesTotal,
                    KEY_BYTES_DONE to p.bytesDone, KEY_BYTES_TOTAL to p.bytesTotal,
                ),
                p.bytesDone, p.bytesTotal,
            )
        }
        return Result.success(
            workDataOf(
                KEY_KIND to kind,
                KEY_FILES_DONE to result.progress.filesDone,
                KEY_BYTES_DONE to result.progress.bytesDone,
                KEY_PROBLEM_COUNT to result.errors.size,
                KEY_PROBLEMS to problems(result.errors.map { "${it.first}: ${it.second}" }),
            ),
        )
    }

    private fun verify(): Result {
        val source = inputData.getString(KEY_SOURCE)!!
        val result = Verifier.compare(sourceStore(source), safStore(KEY_TARGET)) { done, total ->
            report(
                workDataOf(KEY_KIND to kind, KEY_FILES_DONE to done, KEY_FILES_TOTAL to total),
                done.toLong(), total.toLong(),
            )
        }
        val ctx = applicationContext
        val lines = result.missing.map { ctx.getString(R.string.verify_missing, it) } +
            result.extra.map { ctx.getString(R.string.verify_extra, it) } +
            result.different.map { ctx.getString(R.string.verify_different, it) } +
            result.unreadable.map { ctx.getString(R.string.verify_unreadable, it.first, it.second) }
        return Result.success(
            workDataOf(
                KEY_KIND to kind,
                KEY_MATCHING to result.matching,
                KEY_PROBLEM_COUNT to lines.size,
                KEY_PROBLEMS to problems(lines),
            ),
        )
    }

    /** Publishes progress at most twice a second. */
    private fun report(data: Data, done: Long, total: Long) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgress < 500 && done < total) return
        lastProgress = now
        setProgressAsync(data)
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        setForegroundAsync(foregroundInfo(applicationContext.getString(R.string.job_percent, percent), percent))
    }

    private fun fail(message: String) = Result.failure(workDataOf(KEY_KIND to kind, KEY_ERROR to message))

    /** WorkManager output data is capped at 10 KB, so only the first problems are passed on. */
    private fun problems(lines: List<String>) = lines.take(MAX_PROBLEMS).joinToString("\n").take(6000)

    private fun appVersion() = runCatching {
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull() ?: "?"

    private fun foregroundInfo(text: String, percent: Int = -1): ForegroundInfo {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.channel_jobs), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val title = when (kind) {
            KIND_BACKUP -> R.string.job_backup
            KIND_RESTORE -> R.string.job_restore
            else -> R.string.job_verify
        }
        val notification = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(ctx.getString(title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    companion object {
        const val UNIQUE_NAME = "stashdroid-job"
        private const val CHANNEL = "jobs"
        private const val NOTIFICATION_ID = 1
        private const val MAX_PROBLEMS = 20

        const val KIND_BACKUP = "backup"
        const val KIND_RESTORE = "restore"
        const val KIND_VERIFY = "verify"

        const val KEY_KIND = "kind"
        const val KEY_SOURCE = "source"
        const val KEY_REPO = "repo"
        const val KEY_TARGET = "target"
        const val KEY_SNAPSHOT = "snapshot"
        const val KEY_FILES_DONE = "filesDone"
        const val KEY_FILES_TOTAL = "filesTotal"
        const val KEY_BYTES_DONE = "bytesDone"
        const val KEY_BYTES_TOTAL = "bytesTotal"
        const val KEY_NEW_CHUNKS = "newChunks"
        const val KEY_REUSED_CHUNKS = "reusedChunks"
        const val KEY_UNCHANGED = "unchanged"
        const val KEY_MATCHING = "matching"
        const val KEY_PROBLEM_COUNT = "problemCount"
        const val KEY_PROBLEMS = "problems"
        const val KEY_ERROR = "error"

        fun enqueue(context: Context, vararg input: Pair<String, Any?>) {
            val request = OneTimeWorkRequestBuilder<JobWorker>().setInputData(workDataOf(*input)).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
