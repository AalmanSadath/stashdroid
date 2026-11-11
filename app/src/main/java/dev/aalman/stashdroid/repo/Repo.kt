package dev.aalman.stashdroid.repo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Serializable
data class RepoConfig(
    val format: Int = Repo.FORMAT,
    val repoId: String,
    /** Base64 HMAC-SHA256 key that names chunks. */
    val idKey: String,
    val chunker: ChunkerParams = ChunkerParams(),
)

/**
 * A backup repository inside a [Store]:
 *
 * ```
 * stashdroid/
 *   config.json                     format, repository id, chunk id key, chunker settings
 *   chunks/<2 hex>/<64 hex>         one blob per chunk, named by its keyed hash
 *   snapshots/<yyyyMMdd'T'HHmmssSSS'Z'>.json.gz
 * ```
 *
 * A chunk blob is one header byte followed by the payload. The header's bits flag compression
 * and encryption; format 1 writes only plain chunks (header 0). Chunk ids are a keyed hash
 * (HMAC-SHA256) of the plain payload, as in Seedvault, so the names reveal nothing about content
 * once encryption is added and the key is kept encrypted.
 *
 * Snapshots are written after all their chunks, so a run that stops halfway leaves unused chunks
 * but never a snapshot that points at missing data.
 */
class Repo private constructor(private val store: Store, val config: RepoConfig) {

    val chunker = FastCdc(config.chunker)

    private val mac: Mac = Mac.getInstance("HmacSHA256").apply {
        init(SecretKeySpec(Base64.getDecoder().decode(config.idKey), "HmacSHA256"))
    }

    fun chunkId(buf: ByteArray, off: Int, len: Int): String {
        mac.update(buf, off, len)
        return mac.doFinal().toHex()
    }

    private fun chunkPath(id: String) = "$CHUNKS/${id.substring(0, 2)}/$id"

    /** Ids of stored chunks mapped to their blob sizes, read once at the start of a backup. */
    fun listChunks(): MutableMap<String, Long> {
        val out = HashMap<String, Long>()
        for (dir in store.list(CHUNKS)) {
            if (!dir.isDirectory) continue
            for (f in store.list("$CHUNKS/${dir.name}")) {
                if (!f.isDirectory) out[f.name] = f.size
            }
        }
        return out
    }

    fun writeChunk(id: String, buf: ByteArray, off: Int, len: Int, replace: Boolean) {
        val path = chunkPath(id)
        if (replace) store.delete(path)
        store.openWrite(path).use {
            it.write(HEADER_PLAIN)
            it.write(buf, off, len)
        }
    }

    /** Reads a chunk's payload and checks it still hashes to its id. */
    fun readChunk(id: String): ByteArray {
        val blob = store.readBytes(chunkPath(id))
        if (blob.isEmpty()) throw IOException("Chunk $id is empty")
        val header = blob[0].toInt()
        if (header != HEADER_PLAIN) throw IOException("Chunk $id has unsupported header $header")
        if (chunkId(blob, 1, blob.size - 1) != id) throw IOException("Chunk $id is damaged")
        return blob.copyOfRange(1, blob.size)
    }

    fun listSnapshots(): List<String> = store.list(SNAPSHOTS)
        .filter { !it.isDirectory && it.name.endsWith(SNAPSHOT_SUFFIX) }
        .map { it.name }
        .sortedDescending()

    fun readSnapshot(name: String): Snapshot =
        GZIPInputStream(store.openRead("$SNAPSHOTS/$name")).use {
            json.decodeFromString(Snapshot.serializer(), it.readBytes().decodeToString())
        }

    /** Writes [snapshot] and returns its file name. */
    fun writeSnapshot(snapshot: Snapshot): String {
        val stamp = SimpleDateFormat("yyyyMMdd'T'HHmmssSSS'Z'", Locale.ROOT)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(snapshot.createdAt))
        val name = "$stamp$SNAPSHOT_SUFFIX"
        GZIPOutputStream(store.openWrite("$SNAPSHOTS/$name")).use {
            it.write(json.encodeToString(Snapshot.serializer(), snapshot).encodeToByteArray())
        }
        return name
    }

    companion object {
        const val FORMAT = 1
        const val ROOT = "stashdroid"
        private const val CONFIG = "$ROOT/config.json"
        private const val CHUNKS = "$ROOT/chunks"
        private const val SNAPSHOTS = "$ROOT/snapshots"
        private const val SNAPSHOT_SUFFIX = ".json.gz"
        private const val HEADER_PLAIN = 0

        internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Opens the repository in [store], or null when the folder holds none. */
        fun open(store: Store): Repo? {
            if (store.stat(CONFIG) == null) return null
            val config = json.decodeFromString(RepoConfig.serializer(), store.readBytes(CONFIG).decodeToString())
            if (config.format > FORMAT) throw IOException("Backup format ${config.format} needs a newer stashdroid")
            return Repo(store, config)
        }

        fun openOrCreate(store: Store): Repo = open(store) ?: run {
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val config = RepoConfig(
                repoId = UUID.randomUUID().toString(),
                idKey = Base64.getEncoder().encodeToString(key),
            )
            store.writeBytes(CONFIG, json.encodeToString(RepoConfig.serializer(), config).encodeToByteArray())
            Repo(store, config)
        }
    }
}

internal fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xff
        chars[i * 2] = HEX[v ushr 4]
        chars[i * 2 + 1] = HEX[v and 0xf]
    }
    return String(chars)
}

private val HEX = "0123456789abcdef".toCharArray()
