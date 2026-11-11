package dev.aalman.stashdroid.repo

import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * Chunker settings, stored in the repository config. Changing them later only changes where new
 * chunks are cut, which affects deduplication, never whether old backups restore.
 */
@Serializable
data class ChunkerParams(
    val algorithm: String = ALGORITHM,
    val minSize: Int = 256 * 1024,
    val avgSize: Int = 1024 * 1024,
    val maxSize: Int = 4 * 1024 * 1024,
) {
    init {
        require(minSize in 64..avgSize && avgSize <= maxSize) { "Chunk sizes must satisfy 64 <= min <= avg <= max" }
        require(avgSize.countOneBits() == 1) { "Average chunk size must be a power of two" }
    }

    companion object {
        const val ALGORITHM = "fastcdc-gear64-v1"
    }
}

/**
 * Content-defined chunking (FastCDC with normalised chunking). Cut points depend on the bytes
 * around them, not on their offset, so inserting data into a file moves only the chunks near the
 * insertion and the rest deduplicate against the earlier backup.
 *
 * The rolling hash shifts left by one bit per byte, so its top bits depend on the last 64 bytes.
 * The masks test top bits for that reason. Before the average size a stricter mask (two more bits)
 * makes a cut less likely; after it a looser mask makes one more likely, which keeps chunk sizes
 * close to the average.
 */
class FastCdc(private val params: ChunkerParams = ChunkerParams()) {

    init {
        require(params.algorithm == ChunkerParams.ALGORITHM) { "Unknown chunker ${params.algorithm}" }
    }

    private val bits = params.avgSize.countTrailingZeroBits()
    private val maskStrict = topBits(bits + 2)
    private val maskLoose = topBits(bits - 2)

    /** Splits [input] into chunks, calling [onChunk] with a buffer, its start and its length. */
    fun split(input: InputStream, onChunk: (ByteArray, Int, Int) -> Unit) {
        val buf = ByteArray(params.maxSize)
        var filled = 0
        var eof = false
        while (true) {
            while (!eof && filled < buf.size) {
                val n = input.read(buf, filled, buf.size - filled)
                if (n < 0) eof = true else filled += n
            }
            if (filled == 0) return
            val len = cut(buf, filled)
            onChunk(buf, 0, len)
            System.arraycopy(buf, len, buf, 0, filled - len)
            filled -= len
        }
    }

    /** Length of the next chunk in the first [n] bytes of [buf]. */
    internal fun cut(buf: ByteArray, n: Int): Int {
        if (n <= params.minSize) return n
        val limit = minOf(n, params.maxSize)
        val normal = minOf(params.avgSize, limit)
        var hash = 0L
        var i = params.minSize
        while (i < normal) {
            hash = (hash shl 1) + GEAR[buf[i].toInt() and 0xff]
            if (hash and maskStrict == 0L) return i + 1
            i++
        }
        while (i < limit) {
            hash = (hash shl 1) + GEAR[buf[i].toInt() and 0xff]
            if (hash and maskLoose == 0L) return i + 1
            i++
        }
        return limit
    }

    companion object {
        private fun topBits(count: Int): Long = ((1L shl count) - 1) shl (64 - count)

        /**
         * The gear table: 256 pseudo-random 64-bit values from SplitMix64 with a fixed seed. Part
         * of the "fastcdc-gear64-v1" algorithm, so it must never change.
         */
        private val GEAR = LongArray(256).also { table ->
            var state = 0x5354415348445244L // "STASHDRD"
            for (i in table.indices) {
                state += -0x61c8864680b583ebL
                var z = state
                z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
                z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
                table[i] = z xor (z ushr 31)
            }
        }
    }
}
