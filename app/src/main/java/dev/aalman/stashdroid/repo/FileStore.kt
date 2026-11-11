package dev.aalman.stashdroid.repo

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/**
 * A [Store] on the plain file system. Used for shared storage, which all-files access lets the
 * app read directly, and for tests. Symbolic links are left out of listings so a link back up
 * the tree cannot make a walk loop.
 */
class FileStore(private val root: File) : Store {

    private fun file(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun list(dir: String): List<StoreEntry> {
        val children = file(dir).listFiles() ?: return emptyList()
        return children
            .filterNot { Files.isSymbolicLink(it.toPath()) }
            .map { it.toEntry() }
            .sortedBy { it.name }
    }

    override fun stat(path: String): StoreEntry? {
        val f = file(path)
        return if (f.exists()) f.toEntry() else null
    }

    override fun openRead(path: String): InputStream {
        val f = file(path)
        if (!f.isFile) throw FileNotFoundException(path)
        return f.inputStream()
    }

    override fun openWrite(path: String): OutputStream {
        val f = file(path)
        f.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("Cannot create ${it.path}") }
        return f.outputStream()
    }

    override fun mkdirs(dir: String) {
        val f = file(dir)
        if (!f.isDirectory && !f.mkdirs()) throw IOException("Cannot create $dir")
    }

    override fun delete(path: String) {
        file(path).deleteRecursively()
    }

    private fun File.toEntry() = StoreEntry(
        name = name,
        isDirectory = isDirectory,
        size = if (isFile) length() else 0,
        modified = lastModified(),
    )
}
