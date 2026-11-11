package dev.aalman.stashdroid.repo

import java.io.InputStream
import java.io.OutputStream

/** One child of a folder in a [Store]. */
data class StoreEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    /** Last modified time in epoch milliseconds, or 0 when the store does not know it. */
    val modified: Long,
)

/**
 * A tree of folders and files addressed by relative paths with `/` between segments. The empty
 * path is the tree's root. Backed by plain files ([FileStore]) or by a folder picked through the
 * Storage Access Framework ([SafStore]), so the same backup and restore code runs against a local
 * folder in tests and against any document provider on the phone.
 */
interface Store {
    /** Children of [dir], or an empty list when [dir] does not exist. */
    fun list(dir: String): List<StoreEntry>

    /** The entry at [path], or null when nothing is there. */
    fun stat(path: String): StoreEntry?

    fun openRead(path: String): InputStream

    /** Opens [path] for writing, creating missing parent folders and replacing any existing file. */
    fun openWrite(path: String): OutputStream

    /** Creates [dir] and any missing parents. */
    fun mkdirs(dir: String)

    fun delete(path: String)
}

fun Store.writeBytes(path: String, bytes: ByteArray) = openWrite(path).use { it.write(bytes) }

fun Store.readBytes(path: String): ByteArray = openRead(path).use { it.readBytes() }

internal fun joinPath(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

internal fun parentOf(path: String) = path.substringBeforeLast('/', "")

internal fun nameOf(path: String) = path.substringAfterLast('/')
