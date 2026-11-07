package dev.latestaiturn.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.util.Consumer
import java.awt.event.MouseEvent

class StatusWidgetFactory : StatusBarWidgetFactory {
    override fun getId() = ID
    override fun getDisplayName() = "Latest AI Turn"
    override fun isAvailable(project: Project) = true
    override fun createWidget(project: Project): StatusBarWidget = Widget(project)
    override fun canBeEnabledOn(statusBar: StatusBar) = true

    private class Widget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {
        override fun ID() = ID
        override fun getPresentation(): StatusBarWidget.WidgetPresentation = this
        override fun install(statusBar: StatusBar) {}
        override fun dispose() {}
        override fun getAlignment() = 0.5f

        override fun getText(): String {
            val svc = TurnService.getInstance(project)
            val changed = svc.tracker.changedPaths().size
            return when {
                svc.scanning -> "AI turn: scanning…"
                svc.tracker.activeTurn() != null -> "AI turn: running · $changed file${if (changed == 1) "" else "s"}"
                changed > 0 -> "AI turn: $changed file${if (changed == 1) "" else "s"} changed"
                else -> "AI turn: idle"
            }
        }

        override fun getTooltipText() = "Latest AI Turn — click to open the diff of what the latest turn changed"
        override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { TurnDiff.show(project, null) }
    }

    companion object { const val ID = "LatestAiTurn.Status" }
}
