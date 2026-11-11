package dev.aalman.stashdroid.repo

import java.io.IOException
import java.security.MessageDigest

data class RestoreProgress(
    val filesDone: Int,
    val filesTotal: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
)

data class RestoreResult(
    val progress: RestoreProgress,
    /** Files that failed to restore or whose SHA-256 did not match the snapshot. */
    val errors: List<Pair<String, String>>,
)

class TargetNotEmptyException : IOException("The restore folder is not empty")

/**
 * Restores one folder of a snapshot into an empty [Store]. Every chunk is checked against its id
 * as it is read, and every file against the SHA-256 the snapshot recorded.
 */
class RestoreEngine(private val repo: Repo) {

    fun restore(
        snapshot: Snapshot,
        name: String,
        target: Store,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreResult {
        val tree = snapshot.sources.firstOrNull { it.name == name }
            ?: throw IOException("The backup has no folder $name")
        if (target.list("").isNotEmpty()) throw TargetNotEmptyException()

        for (dir in tree.dirs) target.mkdirs(dir)

        var p = RestoreProgress(0, tree.files.size, 0, tree.files.sumOf { it.size })
        onProgress(p)
        val errors = ArrayList<Pair<String, String>>()
        val sha = MessageDigest.getInstance("SHA-256")

        for (file in tree.files) {
            sha.reset()
            var written = 0L
            try {
                target.openWrite(file.path).use { out ->
                    for (ref in file.chunks) {
                        val payload = repo.readChunk(ref.id)
                        if (ref.offset + ref.length > payload.size) throw IOException("Chunk ${ref.id} is too short")
                        out.write(payload, ref.offset, ref.length)
                        sha.update(payload, ref.offset, ref.length)
                        written += ref.length
                        onProgress(p.copy(bytesDone = p.bytesDone + written))
                    }
                }
                if (sha.digest().toHex() != file.sha256) errors += file.path to "Content does not match the backup"
            } catch (e: Exception) {
                errors += file.path to (e.message ?: e.javaClass.simpleName)
            }
            p = p.copy(filesDone = p.filesDone + 1, bytesDone = p.bytesDone + file.size)
            onProgress(p)
        }
        return RestoreResult(p, errors)
    }
}
