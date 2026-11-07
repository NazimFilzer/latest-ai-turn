package dev.latestaiturn.core

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Baseline storage outside the project (in the IDE system directory). Writes are deferred: [put] only records the
 * text in memory and [flush] writes pending blobs, so typing never waits on the disk.
 */
class DiskBlobStore(private val dir: Path) : BlobStore {
    private val pending = ConcurrentHashMap<String, String>()
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 48
        },
    )

    init { Files.createDirectories(dir) }

    private fun file(hash: String): Path = dir.resolve("$hash.txt")

    override fun put(text: String): String {
        val hash = sha256(text)
        if (!pending.containsKey(hash)) pending[hash] = text
        return hash
    }

    override fun get(hash: String): String? {
        pending[hash]?.let { return it }
        cache[hash]?.let { return it }
        val f = file(hash)
        return try {
            if (Files.exists(f)) String(Files.readAllBytes(f), StandardCharsets.UTF_8).also { cache[hash] = it } else null
        } catch (e: Exception) {
            null
        }
    }

    /** Writes every pending blob to disk. Safe to call from any thread. */
    fun flush() {
        for ((hash, text) in pending.entries.toList()) {
            val f = file(hash)
            try {
                if (!Files.exists(f)) {
                    val tmp = dir.resolve("$hash.tmp")
                    Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
                }
                pending.remove(hash, text)
            } catch (e: Exception) {
                // keep it pending; the next flush retries
            }
        }
    }

    /** Deletes blobs no file refers to any more. */
    fun gc(referenced: Set<String>) {
        try {
            Files.newDirectoryStream(dir, "*.txt").use { stream ->
                for (p in stream) {
                    val hash = p.fileName.toString().removeSuffix(".txt")
                    if (hash !in referenced && !pending.containsKey(hash)) Files.deleteIfExists(p)
                }
            }
        } catch (e: Exception) {
            // best effort
        }
    }
}
