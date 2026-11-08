package dev.latestaiturn

import dev.latestaiturn.core.DiskBlobStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DiskBlobStoreTest {
    private fun store() = DiskBlobStore(Files.createTempDirectory("blobs"))

    @Test
    fun `a blob stored after the mark survives a prune prepared before it`() {
        val store = store()
        val old = store.put("typed before the snapshot")
        val mark = store.mark()
        val newer = store.put("typed after the snapshot")

        store.flush(keep = emptySet(), upTo = mark)

        assertNull(store.get(old))                                  // unreferenced and older than the mark: dropped
        assertEquals("typed after the snapshot", store.get(newer))  // newer than the mark: must be kept
    }

    @Test
    fun `putting a blob again after the mark protects it from a prune prepared before`() {
        val store = store()
        val hash = store.put("same text")
        val mark = store.mark()
        store.put("same text") // e.g. an undo back to this text

        store.flush(keep = emptySet(), upTo = mark)

        assertEquals("same text", store.get(hash))
    }

    @Test
    fun `referenced blobs are written to disk and read back`() {
        val dir = Files.createTempDirectory("blobs")
        val first = DiskBlobStore(dir)
        val hash = first.put("keep me")
        first.flush(keep = setOf(hash), upTo = first.mark())

        assertEquals("keep me", DiskBlobStore(dir).get(hash)) // a new store, as after an IDE restart
    }

    @Test
    fun `pending size grows with new text and drops when it is saved or pruned`() {
        val store = store()
        assertEquals(0L, store.pendingChars())

        store.put("12345"); store.put("12345"); val b = store.put("abc")
        assertEquals(8L, store.pendingChars())

        store.flush(keep = setOf(b), upTo = store.mark()) // "12345" is dropped, "abc" is written
        assertEquals(0L, store.pendingChars())
        assertEquals("abc", store.get(b))
    }

    @Test
    fun `gc removes old unreferenced blobs and stale temp files but keeps referenced and recent ones`() {
        val dir = Files.createTempDirectory("blobs")
        val store = DiskBlobStore(dir)
        val old = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 11 * 60_000)
        fun file(name: String, aged: Boolean) = Files.writeString(dir.resolve(name), "x").also { if (aged) Files.setLastModifiedTime(it, old) }
        val kept = file("kept.txt", aged = true)
        val orphan = file("orphan.txt", aged = true)
        val recentOrphan = file("recent.txt", aged = false)
        val staleTmp = file("crash.tmp", aged = true)
        val freshTmp = file("inflight.tmp", aged = false)

        store.gc(referenced = setOf("kept"))

        assertTrue(Files.exists(kept))
        assertFalse(Files.exists(orphan))
        assertTrue(Files.exists(recentOrphan))
        assertFalse(Files.exists(staleTmp))
        assertTrue(Files.exists(freshTmp))
    }
}
