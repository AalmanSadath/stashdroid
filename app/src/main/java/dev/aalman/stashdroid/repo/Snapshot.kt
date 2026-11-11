package dev.aalman.stashdroid.repo

import kotlinx.serialization.Serializable

/**
 * One backup run: every file it saw and the chunks that hold its content. Each snapshot file in
 * the repository is one version of the backup.
 */
@Serializable
data class Snapshot(
    val format: Int = Repo.FORMAT,
    /** Epoch milliseconds when the backup started. */
    val createdAt: Long,
    val device: String,
    val appVersion: String,
    val sources: List<SourceTree>,
)

/** One backed-up folder, such as `DCIM`, with paths relative to it. */
@Serializable
data class SourceTree(
    val name: String,
    /** Where the folder was on the phone, for restoring to its original place later. */
    val origin: String,
    val files: List<FileEntry>,
    /** Every folder in the tree, so empty ones come back too. */
    val dirs: List<String> = emptyList(),
)

@Serializable
data class FileEntry(
    val path: String,
    val size: Long,
    /** Epoch milliseconds. */
    val modified: Long,
    /** SHA-256 of the whole file, hex. Checked after restore. */
    val sha256: String,
    val chunks: List<ChunkRef>,
)

/**
 * A run of bytes inside a chunk's payload. A plain chunk is referenced whole (offset 0); the
 * offset lets several small files share one packed chunk later without a format change.
 */
@Serializable
data class ChunkRef(
    val id: String,
    val offset: Int = 0,
    val length: Int,
)
