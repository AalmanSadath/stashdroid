package dev.aalman.stashdroid.repo

import java.security.MessageDigest

data class VerifyResult(
    val matching: Int,
    /** In the original folder but not in the restored one. */
    val missing: List<String>,
    /** In the restored folder but not in the original one. */
    val extra: List<String>,
    /** In both, with different content. */
    val different: List<String>,
    /** Could not be read on one side, with the reason. */
    val unreadable: List<Pair<String, String>>,
) {
    val allMatch get() = missing.isEmpty() && extra.isEmpty() && different.isEmpty() && unreadable.isEmpty()
}

/** Compares two folder trees file by file: same paths, same sizes, same SHA-256. */
object Verifier {

    fun compare(original: Store, restored: Store, onFile: (done: Int, total: Int) -> Unit = { _, _ -> }): VerifyResult {
        val a = files(original)
        val b = files(restored)
        val common = a.keys.intersect(b.keys).sorted()
        val different = ArrayList<String>()
        val unreadable = ArrayList<Pair<String, String>>()
        var matching = 0
        common.forEachIndexed { i, path ->
            try {
                if (a.getValue(path) != b.getValue(path) || hash(original, path) != hash(restored, path)) {
                    different += path
                } else {
                    matching++
                }
            } catch (e: Exception) {
                unreadable += path to (e.message ?: e.javaClass.simpleName)
            }
            onFile(i + 1, common.size)
        }
        return VerifyResult(
            matching = matching,
            missing = (a.keys - b.keys).sorted(),
            extra = (b.keys - a.keys).sorted(),
            different = different,
            unreadable = unreadable,
        )
    }

    /** Every file in [store] mapped to its size. */
    private fun files(store: Store): Map<String, Long> {
        val out = HashMap<String, Long>()
        fun walk(dir: String) {
            for (e in store.list(dir)) {
                val path = joinPath(dir, e.name)
                if (e.isDirectory) walk(path) else out[path] = e.size
            }
        }
        walk("")
        return out
    }

    private fun hash(store: Store, path: String): String {
        val sha = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(1 shl 16)
        store.openRead(path).use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha.update(buf, 0, n)
            }
        }
        return sha.digest().toHex()
    }
}
