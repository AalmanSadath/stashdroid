package dev.aalman.stashdroid.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class RoundTripTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sourceTree(): File {
        val root = tmp.newFolder("DCIM")
        val r = Random(42)
        File(root, "Camera").mkdirs()
        File(root, "Camera/IMG_0001.jpg").writeBytes(r.nextBytes(10 * 1024 * 1024))
        File(root, "Camera/IMG_0002.jpg").writeBytes(r.nextBytes(300_000))
        File(root, "Camera/copy.jpg").writeBytes(File(root, "Camera/IMG_0002.jpg").readBytes())
        File(root, "empty.txt").writeBytes(ByteArray(0))
        File(root, "one.bin").writeBytes(byteArrayOf(7))
        File(root, "Ünïcødé ✓/naïve.txt").apply { parentFile!!.mkdirs() }.writeText("hello")
        File(root, "Empty folder").mkdirs()
        File(root, "a/b/c").mkdirs()
        File(root, "a/b/c/deep.bin").writeBytes(r.nextBytes(5_000))
        return root
    }

    private fun engine(repo: Repo, t: Long = 1_000) = BackupEngine(repo, "test", "0", clock = { t })

    @Test
    fun backupRestoresToIdenticalFiles() {
        val source = FileStore(sourceTree())
        val repoStore = FileStore(tmp.newFolder("repo"))
        val repo = Repo.openOrCreate(repoStore)

        val result = engine(repo).backup(source, "DCIM", "/sdcard/DCIM")
        assertTrue(result.errors.isEmpty())
        assertEquals(7, result.progress.filesDone)
        // copy.jpg shares every chunk with IMG_0002.jpg.
        assertTrue(result.progress.reusedChunks > 0)

        val reopened = Repo.open(repoStore)!!
        val snapshot = reopened.readSnapshot(result.snapshotName)
        val target = FileStore(tmp.newFolder("restored"))
        val restored = RestoreEngine(reopened).restore(snapshot, "DCIM", target)
        assertTrue(restored.errors.toString(), restored.errors.isEmpty())

        val verify = Verifier.compare(source, target)
        assertTrue(verify.toString(), verify.allMatch)
        assertEquals(7, verify.matching)
        assertNotNull(target.stat("Empty folder"))
    }

    @Test
    fun secondBackupOfUnchangedTreeWritesNothingNew() {
        val source = FileStore(sourceTree())
        val repo = Repo.openOrCreate(FileStore(tmp.newFolder("repo")))
        engine(repo, 1_000).backup(source, "DCIM", "")
        val second = engine(repo, 2_000).backup(source, "DCIM", "")
        assertEquals(0, second.progress.newChunks)
        assertEquals(7, second.progress.unchangedFiles)
        assertEquals(2, repo.listSnapshots().size)
    }

    @Test
    fun changedFileStoresOnlyChangedChunks() {
        val root = sourceTree()
        val source = FileStore(root)
        val repo = Repo.openOrCreate(FileStore(tmp.newFolder("repo")))
        engine(repo, 1_000).backup(source, "DCIM", "")
        val big = File(root, "Camera/IMG_0001.jpg")
        val bytes = big.readBytes()
        bytes[5_000_000] = (bytes[5_000_000] + 1).toByte()
        big.writeBytes(bytes)
        big.setLastModified(big.lastModified() + 10_000)
        val second = engine(repo, 2_000).backup(source, "DCIM", "")
        assertTrue("new chunks ${second.progress.newChunks}", second.progress.newChunks in 1..2)
    }

    @Test
    fun truncatedChunkIsWrittenAgain() {
        val source = FileStore(sourceTree())
        val repoDir = tmp.newFolder("repo")
        val repo = Repo.openOrCreate(FileStore(repoDir))
        engine(repo, 1_000).backup(source, "DCIM", "")
        val chunk = repoDir.resolve("stashdroid/chunks").walk().first { it.isFile }
        chunk.writeBytes(chunk.readBytes().copyOf(10))

        // Rewritten because its size is wrong, even though no file changed.
        val second = engine(repo, 2_000).backup(source, "DCIM", "")
        assertEquals(1, second.progress.newChunks)
        val target = FileStore(tmp.newFolder("restored"))
        val restored = RestoreEngine(repo).restore(repo.readSnapshot(second.snapshotName), "DCIM", target)
        assertTrue(restored.errors.isEmpty())
        assertTrue(Verifier.compare(source, target).allMatch)
    }

    @Test
    fun damagedChunkIsReportedOnRestore() {
        val source = FileStore(sourceTree())
        val repoDir = tmp.newFolder("repo")
        val repo = Repo.openOrCreate(FileStore(repoDir))
        val result = engine(repo).backup(source, "DCIM", "")
        val chunk = repoDir.resolve("stashdroid/chunks").walk().first { it.isFile }
        val bytes = chunk.readBytes()
        bytes[bytes.size - 1] = (bytes[bytes.size - 1] + 1).toByte()
        chunk.writeBytes(bytes)

        val restored = RestoreEngine(repo).restore(repo.readSnapshot(result.snapshotName), "DCIM", FileStore(tmp.newFolder("restored")))
        assertFalse(restored.errors.isEmpty())
        assertTrue(restored.errors.first().second.contains("damaged"))
    }

    @Test(expected = TargetNotEmptyException::class)
    fun restoreRefusesNonEmptyFolder() {
        val source = FileStore(sourceTree())
        val repo = Repo.openOrCreate(FileStore(tmp.newFolder("repo")))
        val result = engine(repo).backup(source, "DCIM", "")
        val targetDir = tmp.newFolder("restored").apply { resolve("existing.txt").writeText("x") }
        RestoreEngine(repo).restore(repo.readSnapshot(result.snapshotName), "DCIM", FileStore(targetDir))
    }

    @Test
    fun verifierReportsDifferences() {
        val a = tmp.newFolder("a").apply {
            resolve("same.txt").writeText("same")
            resolve("changed.txt").writeText("one")
            resolve("only-a.txt").writeText("a")
        }
        val b = tmp.newFolder("b").apply {
            resolve("same.txt").writeText("same")
            resolve("changed.txt").writeText("two")
            resolve("only-b.txt").writeText("b")
        }
        val r = Verifier.compare(FileStore(a), FileStore(b))
        assertEquals(1, r.matching)
        assertEquals(listOf("changed.txt"), r.different)
        assertEquals(listOf("only-a.txt"), r.missing)
        assertEquals(listOf("only-b.txt"), r.extra)
    }
}
