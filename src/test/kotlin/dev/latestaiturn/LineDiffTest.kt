package dev.latestaiturn

import dev.latestaiturn.core.Hunk
import dev.latestaiturn.core.LineDiff
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class LineDiffTest {
    /** Replaces each hunk's old lines with its new lines; the result must be the new text. */
    private fun apply(a: List<String>, b: List<String>, hunks: List<Hunk>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        for (h in hunks) {
            out.addAll(a.subList(i, h.aStart))
            out.addAll(b.subList(h.bStart, h.bStart + h.bLen))
            i = h.aStart + h.aLen
        }
        out.addAll(a.subList(i, a.size))
        return out
    }

    @Test
    fun `applying the hunks to the old lines gives the new lines, for small, similar and fully different inputs`() {
        val rnd = Random(7)
        for (alphabet in listOf(2, 5, 1000)) for (size in listOf(0, 1, 3, 30, 300)) repeat(100) {
            val a = List(rnd.nextInt(size + 1)) { "l${rnd.nextInt(alphabet)}" }
            val b = List(rnd.nextInt(size + 1)) { "l${rnd.nextInt(alphabet)}" }
            assertEquals("a=$a b=$b", b, apply(a, b, LineDiff.hunks(a, b)))
        }
        // far more differences than the search limit: one big hunk, still a correct one
        val a = List(2500) { "a${rnd.nextInt(100000)}" }
        val b = List(2300) { "b${rnd.nextInt(100000)}" }
        assertEquals(b, apply(a, b, LineDiff.hunks(a, b)))
    }

    @Test
    fun `identical lines produce no hunks and one changed line produces one small hunk`() {
        val a = List(1000) { "l$it" }
        assertEquals(emptyList<Hunk>(), LineDiff.hunks(a, a))
        assertEquals(listOf(Hunk(500, 1, 500, 1)), LineDiff.hunks(a, a.toMutableList().also { it[500] = "changed" }))
    }
}
