package dev.latestaiturn

import dev.latestaiturn.ide.StoredData
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StoredDataTest {
    @Test
    fun `deleteTree removes nested project folders, blobs and state`() {
        val root = Files.createTempDirectory("latest-ai-turn")
        val blobs = Files.createDirectories(root.resolve("abc123").resolve("blobs"))
        Files.writeString(blobs.resolve("deadbeef.txt"), "baseline")
        Files.writeString(root.resolve("abc123").resolve("state.json"), "{}")

        StoredData.deleteTree(root)

        assertFalse(Files.exists(root))
    }

    @Test
    fun `deleteTree on a missing folder does nothing`() {
        val root = Files.createTempDirectory("latest-ai-turn").resolve("never-created")

        StoredData.deleteTree(root)

        assertTrue(!Files.exists(root))
    }

    private fun project(root: java.nio.file.Path, name: String, path: String?, ageDays: Long): java.nio.file.Path {
        val folder = Files.createDirectories(root.resolve(name))
        val state = folder.resolve("state.json")
        Files.writeString(state, "{}")
        if (path != null) Files.writeString(folder.resolve(StoredData.MARKER), path)
        val t = java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - ageDays * 86_400_000)
        Files.setLastModifiedTime(state, t)
        return folder
    }

    @Test
    fun `pruneAbandoned removes only old folders whose project is gone`() {
        val root = Files.createTempDirectory("latest-ai-turn")
        val alive = Files.createTempDirectory("project")
        val gone = root.resolve("no-such-project").toString()
        val goneOld = project(root, "goneOld", gone, 60)
        val goneRecent = project(root, "goneRecent", gone, 1)
        val aliveOld = project(root, "aliveOld", alive.toString(), 60)
        val noMarker = project(root, "noMarker", null, 60)

        StoredData.pruneAbandoned(root)

        assertFalse(Files.exists(goneOld))
        assertTrue(Files.exists(goneRecent))
        assertTrue(Files.exists(aliveOld))
        assertTrue(Files.exists(noMarker))
    }
}
