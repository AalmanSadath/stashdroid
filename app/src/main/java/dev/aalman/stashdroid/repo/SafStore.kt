package dev.aalman.stashdroid.repo

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * A [Store] on a folder picked through the Storage Access Framework, so a backup can go to any
 * document provider: a local folder, an SD card, or a cloud app that offers its storage in the
 * file picker.
 *
 * Document providers address files by opaque ids, not paths. Each folder's children are queried
 * once and cached by path; the cache is kept in step with every create and delete made here.
 * Not thread-safe: one backup or restore uses one instance.
 */
class SafStore(private val resolver: ContentResolver, private val tree: Uri) : Store {

    private data class Doc(val id: String, val entry: StoreEntry)

    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    /** Listed folders: path to (child name to doc). */
    private val children = HashMap<String, MutableMap<String, Doc>>()

    private fun docUri(id: String) = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun childrenOf(dir: String): MutableMap<String, Doc>? {
        children[dir]?.let { return it }
        val dirId = if (dir.isEmpty()) rootId else (find(dir)?.takeIf { it.entry.isDirectory }?.id ?: return null)
        val out = HashMap<String, Doc>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        resolver.query(uri, COLUMNS, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                out[name] = Doc(
                    c.getString(0),
                    StoreEntry(
                        name = name,
                        isDirectory = isDir,
                        size = if (c.isNull(3)) 0 else c.getLong(3),
                        modified = if (c.isNull(4)) 0 else c.getLong(4),
                    ),
                )
            }
        } ?: throw IOException("Cannot list $dir")
        children[dir] = out
        return out
    }

    private fun find(path: String): Doc? {
        if (path.isEmpty()) return Doc(rootId, StoreEntry("", true, 0, 0))
        return childrenOf(parentOf(path))?.get(nameOf(path))
    }

    override fun list(dir: String): List<StoreEntry> =
        childrenOf(dir)?.values?.map { it.entry }?.sortedBy { it.name } ?: emptyList()

    override fun stat(path: String): StoreEntry? = find(path)?.entry

    override fun openRead(path: String): InputStream {
        val doc = find(path)?.takeIf { !it.entry.isDirectory } ?: throw FileNotFoundException(path)
        return resolver.openInputStream(docUri(doc.id)) ?: throw IOException("Cannot open $path")
    }

    override fun openWrite(path: String): OutputStream {
        val parent = parentOf(path)
        val name = nameOf(path)
        mkdirs(parent)
        val siblings = childrenOf(parent)!!
        val existing = siblings[name]
        val id = if (existing != null && !existing.entry.isDirectory) {
            existing.id
        } else {
            create(parent, name, MIME_BINARY)
        }
        // "wt" truncates an existing file. Some providers only accept "w", which then truncates too.
        val uri = docUri(id)
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w")
            ?: throw IOException("Cannot write $path")
        return object : java.io.FilterOutputStream(out) {
            var written = 0L
            override fun write(b: Int) { out.write(b); written++ }
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); written += len }
            override fun close() {
                super.close()
                siblings[name] = Doc(id, StoreEntry(name, false, written, System.currentTimeMillis()))
            }
        }
    }

    override fun mkdirs(dir: String) {
        if (dir.isEmpty()) return
        val existing = find(dir)
        if (existing != null) {
            if (!existing.entry.isDirectory) throw IOException("$dir is a file")
            return
        }
        mkdirs(parentOf(dir))
        val id = create(parentOf(dir), nameOf(dir), Document.MIME_TYPE_DIR)
        children[dir] = HashMap()
        childrenOf(parentOf(dir))!![nameOf(dir)] = Doc(id, StoreEntry(nameOf(dir), true, 0, 0))
    }

    override fun delete(path: String) {
        val doc = find(path) ?: return
        DocumentsContract.deleteDocument(resolver, docUri(doc.id))
        childrenOf(parentOf(path))?.remove(nameOf(path))
        val prefix = "$path/"
        children.keys.removeAll { it == path || it.startsWith(prefix) }
    }

    private fun create(parent: String, name: String, mime: String): String {
        val parentId = find(parent)?.id ?: throw IOException("Missing folder $parent")
        val uri = DocumentsContract.createDocument(resolver, docUri(parentId), mime, name)
            ?: throw IOException("Cannot create $name in $parent")
        val id = DocumentsContract.getDocumentId(uri)
        // A provider may rename a new file (for example "name (1)") instead of failing. The repository
        // relies on exact names, so treat that as an error.
        val actual = resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        if (actual != null && actual != name) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            throw IOException("The storage renamed $name to $actual")
        }
        return id
    }

    companion object {
        private const val MIME_BINARY = "application/octet-stream"
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )
    }
}
