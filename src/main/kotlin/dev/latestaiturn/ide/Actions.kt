package dev.latestaiturn.ide

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem

abstract class TurnAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabledAndVisible = e.project != null }
    final override fun actionPerformed(e: AnActionEvent) { e.project?.let { perform(it, e) } }
    abstract fun perform(project: Project, e: AnActionEvent)
}

class StartTurnAction : TurnAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        TurnService.getInstance(project).manualStart()
        TurnService.getInstance(project).notify("Turn started. Changes from now on are highlighted.")
    }
}

class EndTurnAction : TurnAction() {
    override fun update(e: AnActionEvent) {
        val p = e.project
        e.presentation.isEnabledAndVisible = p != null
        e.presentation.isEnabled = p != null && TurnService.getInstance(p).tracker.activeTurn() != null
    }
    override fun perform(project: Project, e: AnActionEvent) = TurnService.getInstance(project).manualEnd()
}

class RebaselineAction : TurnAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        val ok = Messages.showYesNoDialog(
            project,
            "Every file's current content becomes its baseline, so nothing is highlighted until files change again.",
            "Re-baseline", "Re-baseline", "Cancel", Messages.getQuestionIcon(),
        )
        if (ok == Messages.YES) TurnService.getInstance(project).rebaseline()
    }
}

class ToggleHighlightsAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = e.project?.let { TurnService.getInstance(it).highlightsEnabled } ?: true
    override fun setSelected(e: AnActionEvent, state: Boolean) { e.project?.let { TurnService.getInstance(it).highlightsEnabled = state } }
}

class ToggleBackgroundAction : ToggleAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun isSelected(e: AnActionEvent) = e.project?.let { TurnService.getInstance(it).backgroundEnabled } ?: true
    override fun setSelected(e: AnActionEvent, state: Boolean) { e.project?.let { TurnService.getInstance(it).backgroundEnabled = state } }
}

class ShowTurnDiffAction : TurnAction() {
    override fun perform(project: Project, e: AnActionEvent) = TurnDiff.show(project, null)
}

class ShowFileTurnDiffAction : TurnAction() {
    override fun update(e: AnActionEvent) {
        val p = e.project
        val f = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = p != null && f != null && TurnService.getInstance(p).tracker.changedPaths().contains(f.path)
    }
    override fun perform(project: Project, e: AnActionEvent) = TurnDiff.show(project, e.getData(CommonDataKeys.VIRTUAL_FILE)?.path)
}

/** Opens IntelliJ's standard diff window over every file the latest turn changed: baseline on the left, live file on the right. */
object TurnDiff {
    fun show(project: Project, focusPath: String?) {
        val svc = TurnService.getInstance(project)
        val tracker = svc.tracker
        val paths = tracker.changedPaths().sorted()
        if (paths.isEmpty()) { svc.notify("The latest turn has no changes to show."); return }
        val factory = DiffContentFactory.getInstance()
        val base = project.basePath?.trimEnd('/') ?: ""
        val requests = paths.map { path ->
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
            val before = tracker.baselineText(path)
            val left = if (before == null) factory.createEmpty() else factory.create(project, before, fileType)
            val vf = LocalFileSystem.getInstance().findFileByPath(path)
            val right = if (vf != null && tracker.currentText(path) != null) factory.create(project, vf) else factory.createEmpty()
            SimpleDiffRequest(path.removePrefix("$base/"), left, right, "Before the turn", "Now")
        }
        val chain = SimpleDiffRequestChain(requests)
        focusPath?.let { paths.indexOf(it).takeIf { i -> i >= 0 }?.let { i -> chain.index = i } }
        DiffManager.getInstance().showDiff(project, chain, DiffDialogHints.DEFAULT)
    }
}
