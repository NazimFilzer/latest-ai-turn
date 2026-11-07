package dev.latestaiturn.core

/** A changed region: lines [aStart, aStart+aLen) of the old text became [bStart, bStart+bLen) of the new text. */
data class Hunk(val aStart: Int, val aLen: Int, val bStart: Int, val bLen: Int)

/** Splits text into lines; a trailing newline does not produce an extra empty line. */
fun linesOf(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

object LineDiff {
    /** Beyond this many edits we stop searching for a minimal diff and report one big hunk. */
    private const val MAX_EDITS = 1500

    fun hunks(a: List<String>, b: List<String>): List<Hunk> {
        var pre = 0
        while (pre < a.size && pre < b.size && a[pre] == b[pre]) pre++
        var suf = 0
        while (suf < a.size - pre && suf < b.size - pre && a[a.size - 1 - suf] == b[b.size - 1 - suf]) suf++
        val aa = a.subList(pre, a.size - suf)
        val bb = b.subList(pre, b.size - suf)
        if (aa.isEmpty() && bb.isEmpty()) return emptyList()
        if (aa.isEmpty() || bb.isEmpty()) return listOf(Hunk(pre, aa.size, pre, bb.size))
        val matches = myersMatches(aa, bb) ?: return listOf(Hunk(pre, aa.size, pre, bb.size))

        val out = ArrayList<Hunk>()
        var i = 0
        var j = 0
        for ((mi, mj) in matches) {
            if (mi > i || mj > j) out.add(Hunk(pre + i, mi - i, pre + j, mj - j))
            i = mi + 1
            j = mj + 1
        }
        if (i < aa.size || j < bb.size) out.add(Hunk(pre + i, aa.size - i, pre + j, bb.size - j))
        return out
    }

    /** Pairs (indexInA, indexInB) of lines kept by a minimal edit script, or null if too different. */
    private fun myersMatches(a: List<String>, b: List<String>): List<Pair<Int, Int>>? {
        val n = a.size
        val m = b.size
        val cap = minOf(n + m, MAX_EDITS)
        val off = cap + 1
        val v = IntArray(2 * cap + 3)
        val trace = ArrayList<IntArray>()
        var found = -1
        search@ for (d in 0..cap) {
            trace.add(v.copyOf())
            for (k in -d..d step 2) {
                var x = if (k == -d || (k != d && v[off + k - 1] < v[off + k + 1])) v[off + k + 1] else v[off + k - 1] + 1
                var y = x - k
                while (x < n && y < m && a[x] == b[y]) { x++; y++ }
                v[off + k] = x
                if (x >= n && y >= m) { found = d; break@search }
            }
        }
        if (found < 0) return null

        val matches = ArrayList<Pair<Int, Int>>()
        var x = n
        var y = m
        for (d in found downTo 1) {
            val vv = trace[d]
            val k = x - y
            val prevK = if (k == -d || (k != d && vv[off + k - 1] < vv[off + k + 1])) k + 1 else k - 1
            val prevX = vv[off + prevK]
            val prevY = prevX - prevK
            while (x > prevX && y > prevY) { x--; y--; matches.add(x to y) }
            x = prevX
            y = prevY
        }
        while (x > 0 && y > 0) { x--; y--; matches.add(x to y) }
        matches.reverse()
        return matches
    }
}
