package dev.latestaiturn.ide

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import dev.latestaiturn.core.ChangeKind
import dev.latestaiturn.core.LineChange
import java.awt.Color
import java.awt.Graphics
import java.awt.Rectangle

/** Draws the Latest Turn's changes into an editor: a violet gutter bar plus an optional line background. */
object TurnPaint {
    private val KEY = Key.create<List<RangeHighlighter>>("latestaiturn.highlighters")

    val GUTTER_COLOR = JBColor(Color(0x7C4DFF), Color(0xB388FF))
    private val BACKGROUND = JBColor(Color(124, 77, 255, 34), Color(179, 136, 255, 40))

    fun clear(editor: Editor) {
        val old = editor.getUserData(KEY) ?: return
        old.forEach { editor.markupModel.removeHighlighter(it) }
        editor.putUserData(KEY, null)
    }

    fun paint(editor: Editor, changes: List<LineChange>, showBackground: Boolean) {
        clear(editor)
        if (changes.isEmpty()) return
        val doc = editor.document
        val lineCount = doc.lineCount
        if (lineCount == 0) return
        val bg = TextAttributes().apply { backgroundColor = BACKGROUND }
        val added = ArrayList<RangeHighlighter>()
        for (c in changes) {
            val first = c.startLine.coerceIn(0, lineCount - 1)
            val last = if (c.kind == ChangeKind.DELETED) first else (c.startLine + c.lineCount - 1).coerceIn(first, lineCount - 1)
            if (c.kind != ChangeKind.DELETED && c.startLine >= lineCount) continue
            val h = editor.markupModel.addRangeHighlighter(
                doc.getLineStartOffset(first),
                doc.getLineEndOffset(last),
                HighlighterLayer.ADDITIONAL_SYNTAX,
                if (showBackground && c.kind != ChangeKind.DELETED) bg else null,
                HighlighterTargetArea.LINES_IN_RANGE,
            )
            h.lineMarkerRenderer = Bar(c.kind)
            added.add(h)
        }
        editor.putUserData(KEY, added)
    }

    private class Bar(private val kind: ChangeKind) : LineMarkerRendererEx {
        override fun getPosition() = LineMarkerRendererEx.Position.RIGHT

        override fun paint(editor: Editor, g: Graphics, r: Rectangle) {
            g.color = GUTTER_COLOR
            val x = r.x - BAR_WIDTH - 1
            if (kind == ChangeKind.DELETED) {
                // a small wedge marking lines the turn removed
                g.fillPolygon(intArrayOf(x, x + BAR_WIDTH + 3, x), intArrayOf(r.y - 3, r.y, r.y + 3), 3)
            } else {
                g.fillRect(x, r.y, BAR_WIDTH, r.height)
            }
        }
    }

    private const val BAR_WIDTH = 3
}
