package dev.aalman.stashdroid.repo

import java.security.MessageDigest

data class BackupProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val newChunks: Int,
    val reusedChunks: Int,
    val unchangedFiles: Int,
)

data class BackupResult(
    val snapshotName: String,
    val progress: BackupProgress,
    /** Files that could not be read, with the reason. They are left out of the snapshot. */
    val errors: List<Pair<String, String>>,
)

/**
 * Backs up one folder into a [Repo].
 *
 * A file whose size and modified time match the latest snapshot, and whose chunks are all still
 * stored, keeps that snapshot's entry without being read again. Every other file is read once,
 * split into chunks, and each chunk the repository lacks is written. A stored chunk whose blob
 * size is wrong (a write that stopped halfway) is written again.
 */
class BackupEngine(
    private val repo: Repo,
    private val device: String,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun backup(
        source: Store,
        name: String,
        origin: String,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupResult {
        val startedAt = clock()
        val dirs = ArrayList<String>()
        val files = ArrayList<Pair<String, StoreEntry>>()
        walk(source, "", dirs, files)

        val previous = latestTree(name)?.files?.associateBy { it.path } ?: emptyMap()
        val stored = repo.listChunks()

        val bytesTotal = files.sumOf { it.second.size }
        var p = BackupProgress(0, files.size, 0, bytesTotal, 0, 0, 0)
        onProgress(p)

        val entries = ArrayList<FileEntry>(files.size)
        val errors = ArrayList<Pair<String, String>>()
        val sha = MessageDigest.getInstance("SHA-256")

        for ((path, stat) in files) {
            val old = previous[path]
            if (old != null && old.size == stat.size && old.modified == stat.modified &&
                old.chunks.all { stored[it.id] == it.length + 1L }
            ) {
                entries += old
                p = p.copy(
                    filesDone = p.filesDone + 1,
                    bytesDone = p.bytesDone + stat.size,
                    reusedChunks = p.reusedChunks + old.chunks.size,
                    unchangedFiles = p.unchangedFiles + 1,
                )
                onProgress(p)
                continue
            }

            val refs = ArrayList<ChunkRef>()
            var size = 0L
            var newChunks = 0
            var reused = 0
            sha.reset()
            try {
                source.openRead(path).use { input ->
                    repo.chunker.split(input) { buf, off, len ->
                        sha.update(buf, off, len)
                        val id = repo.chunkId(buf, off, len)
                        val blobSize = stored[id]
                        if (blobSize == len + 1L) {
                            reused++
                        } else {
                            repo.writeChunk(id, buf, off, len, replace = blobSize != null)
                            stored[id] = len + 1L
                            newChunks++
                        }
                        refs += ChunkRef(id = id, length = len)
                        size += len
                        onProgress(p.copy(bytesDone = p.bytesDone + size))
                    }
                }
            } catch (e: Exception) {
                // Chunks already written stay; a later run reuses them.
                errors += path to (e.message ?: e.javaClass.simpleName)
                p = p.copy(
                    filesDone = p.filesDone + 1,
                    bytesDone = p.bytesDone + stat.size,
                    newChunks = p.newChunks + newChunks,
                    reusedChunks = p.reusedChunks + reused,
                )
                onProgress(p)
                continue
            }
            entries += FileEntry(path, size, stat.modified, sha.digest().toHex(), refs)
            p = p.copy(
                filesDone = p.filesDone + 1,
                bytesDone = p.bytesDone + stat.size,
                newChunks = p.newChunks + newChunks,
                reusedChunks = p.reusedChunks + reused,
            )
            onProgress(p)
        }

        val snapshot = Snapshot(
            createdAt = startedAt,
            device = device,
            appVersion = appVersion,
            sources = listOf(SourceTree(name, origin, entries, dirs)),
        )
        return BackupResult(repo.writeSnapshot(snapshot), p, errors)
    }

    private fun latestTree(name: String): SourceTree? {
        val latest = repo.listSnapshots().firstOrNull() ?: return null
        return runCatching { repo.readSnapshot(latest) }.getOrNull()?.sources?.firstOrNull { it.name == name }
    }

    private fun walk(store: Store, dir: String, dirs: MutableList<String>, files: MutableList<Pair<String, StoreEntry>>) {
        for (e in store.list(dir)) {
            val path = joinPath(dir, e.name)
            if (e.isDirectory) {
                dirs += path
                walk(store, path, dirs, files)
            } else {
                files += path to e
            }
        }
    }
}
