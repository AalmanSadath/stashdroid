package dev.aalman.stashdroid.repo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

class FastCdcTest {

    private val params = ChunkerParams(minSize = 2 * 1024, avgSize = 8 * 1024, maxSize = 32 * 1024)
    private val cdc = FastCdc(params)

    private fun chunks(data: ByteArray, input: InputStream = ByteArrayInputStream(data)): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        cdc.split(input) { buf, off, len -> out += buf.copyOfRange(off, off + len) }
        return out
    }

    @Test
    fun chunksJoinBackToTheInput() {
        val data = Random(1).nextBytes(1_000_000)
        val joined = chunks(data).fold(ByteArray(0)) { acc, c -> acc + c }
        assertArrayEquals(data, joined)
    }

    @Test
    fun chunkSizesStayWithinBounds() {
        val list = chunks(Random(2).nextBytes(2_000_000))
        list.dropLast(1).forEach { assertTrue(it.size in params.minSize..params.maxSize) }
        assertTrue(list.last().size <= params.maxSize)
        val avg = list.sumOf { it.size } / list.size
        assertTrue("average $avg", avg in params.avgSize / 2..params.avgSize * 2)
    }

    @Test
    fun cutsDoNotDependOnHowTheStreamDeliversBytes() {
        val data = Random(3).nextBytes(500_000)
        // Hands out at most 7 bytes per read.
        val trickle = object : InputStream() {
            var pos = 0
            override fun read() = if (pos < data.size) data[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= data.size) return -1
                val n = minOf(len, 7, data.size - pos)
                System.arraycopy(data, pos, b, off, n)
                pos += n
                return n
            }
        }
        assertEquals(chunks(data).map { it.size }, chunks(data, trickle).map { it.size })
    }

    @Test
    fun insertingBytesChangesOnlyNearbyChunks() {
        val data = Random(4).nextBytes(1_000_000)
        val edited = data.copyOfRange(0, 500_000) + Random(5).nextBytes(100) + data.copyOfRange(500_000, data.size)
        val before = chunks(data).map { it.contentHashCode() }.toSet()
        val after = chunks(edited).map { it.contentHashCode() }
        val changed = after.count { it !in before }
        assertTrue("changed $changed of ${after.size}", changed <= 3)
    }

    @Test
    fun smallAndEmptyInputs() {
        assertEquals(0, chunks(ByteArray(0)).size)
        assertEquals(listOf(1), chunks(ByteArray(1)).map { it.size })
        assertEquals(listOf(params.minSize), chunks(ByteArray(params.minSize)).map { it.size })
    }
}
