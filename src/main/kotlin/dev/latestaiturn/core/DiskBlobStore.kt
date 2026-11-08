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
    // pending, seqs and pendingChars change together, so every change to them holds [lock]. Without it a prune running
    // on another thread could see a blob that [put] has stored but not yet stamped, and drop it.
    private val lock = Any()
    private val pending = ConcurrentHashMap<String, String>()
    private val seqs = HashMap<String, Long>()
    private var pendingChars = 0L
    private val counter = java.util.concurrent.atomic.AtomicLong()
    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>) = size > 48
        },
    )

    private companion object { const val GC_GRACE_MILLIS = 10 * 60_000L }

    init { Files.createDirectories(dir) }

    private fun file(hash: String): Path = dir.resolve("$hash.txt")

    override fun put(text: String): String {
        val hash = sha256(text)
        synchronized(lock) {
            if (!pending.containsKey(hash)) { pending[hash] = text; pendingChars += text.length }
            seqs[hash] = counter.incrementAndGet()
        }
        return hash
    }

    /** How much text is waiting in memory for the next [flush]; lets the caller save early when it grows large. */
    fun pendingChars(): Long = synchronized(lock) { pendingChars }

    /** A point in time: blobs put after this are never dropped by a [flush] that was prepared before it. */
    fun mark(): Long = counter.get()

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

    /**
     * Writes pending blobs to disk. With [keep], pending blobs that no file refers to (typing leaves one per
     * keystroke) are discarded instead, but only those put at or before [upTo], so a blob stored after the
     * snapshot that produced [keep] is never lost. Safe to call from any thread.
     */
    fun flush(keep: Set<String>? = null, upTo: Long = 0) {
        if (keep != null) synchronized(lock) {
            for (hash in pending.keys.toList()) {
                if (hash !in keep && (seqs[hash] ?: 0) <= upTo) { pendingChars -= pending.remove(hash)?.length ?: 0; seqs.remove(hash) }
            }
        }
        for ((hash, text) in pending.entries.toList()) {
            val f = file(hash)
            try {
                if (!Files.exists(f)) {
                    val tmp = dir.resolve("$hash.tmp")
                    Files.write(tmp, text.toByteArray(StandardCharsets.UTF_8))
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING)
                }
                synchronized(lock) {
                    if (pending.remove(hash, text)) { pendingChars -= text.length; seqs.remove(hash) } // on disk now; the stamp is no longer needed
                }
            } catch (e: Exception) {
                // keep it pending; the next flush retries
            }
        }
    }

    /** Deletes blobs no file refers to any more. Recently written blobs are kept: they may belong to a newer snapshot. */
    fun gc(referenced: Set<String>) {
        val cutoff = System.currentTimeMillis() - GC_GRACE_MILLIS
        try {
            Files.newDirectoryStream(dir, "*.txt").use { stream ->
                for (p in stream) {
                    val hash = p.fileName.toString().removeSuffix(".txt")
                    if (hash !in referenced && !pending.containsKey(hash) && Files.getLastModifiedTime(p).toMillis() < cutoff) Files.deleteIfExists(p)
                }
            }
        } catch (e: Exception) {
            // best effort
        }
    }
}
