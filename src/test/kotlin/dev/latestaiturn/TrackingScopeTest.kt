package dev.latestaiturn

import dev.latestaiturn.core.TrackingScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackingScopeTest {
    private val base = "/work/p"

    @Test
    fun `folder list is parsed leniently and blanks are dropped`() {
        assertEquals(listOf("src", "doc"), TrackingScope.parse("/src/, ./doc"))
        assertEquals(emptyList<String>(), TrackingScope.parse("  "))
    }

    @Test
    fun `a file is in scope only under one of the tracked folders`() {
        assertTrue(TrackingScope.contains("/work/p/src/A.java", base, listOf("src")))
        assertTrue(TrackingScope.contains("/work/p/src/deep/B.java", base, listOf("src")))
        assertFalse(TrackingScope.contains("/work/p/dep/x.js", base, listOf("src")))
        assertFalse(TrackingScope.contains("/work/p/srcs/A.java", base, listOf("src")))
        assertFalse(TrackingScope.contains("/work/p/src/A.java", base, emptyList<String>().let { listOf("doc") }))
    }

    @Test
    fun `an empty folder list means the whole project`() {
        assertTrue(TrackingScope.contains("/work/p/anything/at/all.txt", base, emptyList()))
        assertFalse(TrackingScope.contains("/elsewhere/x.txt", base, emptyList()))
    }
}
