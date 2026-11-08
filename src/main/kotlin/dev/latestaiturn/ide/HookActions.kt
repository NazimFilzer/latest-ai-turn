package dev.latestaiturn.ide

import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import dev.latestaiturn.hooks.HookInstaller
import dev.latestaiturn.hooks.HookScript
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import javax.swing.JComponent

/** One agent's hook config file and how to add or remove our hooks in it. */
private class HookTarget(
    val name: String,
    val app: String,
    val startEvent: String,
    val configFile: Path,
    val install: (String?, String) -> String,
    val uninstall: (String?) -> String,
    val isInstalled: (String?) -> Boolean,
) {
    fun existing(): String? = if (Files.exists(configFile)) Files.readString(configFile) else null
    val agentPresent: Boolean get() = Files.isDirectory(configFile.parent)
}

/** True when any agent's config already contains our hooks. */
fun anyHooksInstalled(): Boolean = targets().any { t -> runCatching { t.isInstalled(t.existing()) }.getOrDefault(false) }

private fun targets(): List<HookTarget> {
    val home = PluginPaths.home()
    return listOf(
        HookTarget("Claude Code (desktop app, CLI and T3 Code)", "claude", "UserPromptSubmit", home.resolve(".claude/settings.json"),
            { c, s -> HookInstaller.installClaudeStyle(c, s, "claude") }, HookInstaller::uninstallClaudeStyle, HookInstaller::isInstalledClaudeStyle),
        HookTarget("Codex (desktop app, CLI and T3 Code)", "codex", "UserPromptSubmit", home.resolve(".codex/hooks.json"),
            { c, s -> HookInstaller.installClaudeStyle(c, s, "codex") }, HookInstaller::uninstallClaudeStyle, HookInstaller::isInstalledClaudeStyle),
        HookTarget("Antigravity (best effort)", "antigravity", "PreInvocation", home.resolve(".gemini/config/hooks.json"),
            { c, s -> HookInstaller.installAntigravity(c, s) }, HookInstaller::uninstallAntigravity, HookInstaller::isInstalledAntigravity),
    )
}

private fun backupAndWrite(configFile: Path, content: String) {
    // A settings file that is a symlink (dotfiles repo) must stay one: write through to the real file.
    val target = if (Files.isSymbolicLink(configFile)) runCatching { configFile.toRealPath() }.getOrDefault(configFile) else configFile
    Files.createDirectories(target.parent)
    if (Files.exists(target)) {
        var backup = target.resolveSibling(target.fileName.toString() + ".latest-ai-turn.bak")
        if (Files.exists(backup)) backup = target.resolveSibling(target.fileName.toString() + ".latest-ai-turn." + System.currentTimeMillis() + ".bak")
        Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES)
    }
    val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
    Files.writeString(tmp, content)
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
}

class InstallHooksAction : TurnAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        val all = targets()
        val script = PluginPaths.script().toString()
        val dialog = object : DialogWrapper(project, true) {
            val boxes = all.map { JBCheckBox(it.name, it.agentPresent) }
            val preview = JBTextArea(14, 80).apply { isEditable = false; lineWrap = false }

            init {
                title = "Install Agent Hooks"
                setOKButtonText("Install")
                boxes.forEach { it.addActionListener { refreshPreview() } }
                refreshPreview()
                init()
            }

            private fun refreshPreview() {
                preview.text = all.indices.filter { boxes[it].isSelected }.joinToString("\n\n") { i ->
                    val t = all[i]
                    val state = if (t.existing() == null) "will be created" else "exists; a backup copy is made, nothing is removed"
                    val block = if (t.app == "antigravity") " in hook block \"latest-ai-turn\"" else ""
                    "${t.configFile}  ($state)\n" +
                        "  adds ${t.startEvent}$block -> ${HookInstaller.command(script, t.app, "start")}\n" +
                        "  adds Stop$block -> ${HookInstaller.command(script, t.app, "end")}"
                }.ifEmpty { "Nothing selected." }
            }

            override fun createCenterPanel(): JComponent {
                val fb = FormBuilder.createFormBuilder()
                    .addComponent(JBLabel("<html>The hooks tell this plugin when an agent turn starts and ends.<br>" +
                        "Each hook only copies the event into <code>~/.latest-ai-turn/spool</code>; it does not change the agent's behavior.<br>" +
                        "Codex asks you to approve a new hook once (Settings &gt; Hooks).</html>"))
                boxes.forEach { fb.addComponent(it) }
                fb.addComponentFillVertically(JBScrollPane(preview), 0)
                return fb.panel
            }
        }
        if (!dialog.showAndGet()) return
        try {
            Files.createDirectories(PluginPaths.dataDir())
            Files.writeString(PluginPaths.script(), HookScript.TEXT)
            runCatching { Files.setPosixFilePermissions(PluginPaths.script(), PosixFilePermissions.fromString("rwxr-xr-x")) }
            val done = ArrayList<String>()
            for (i in all.indices) {
                if (!dialog.boxes[i].isSelected) continue
                val t = all[i]
                backupAndWrite(t.configFile, t.install(t.existing(), script))
                done.add(t.name.substringBefore(" ("))
            }
            TurnService.getInstance(project).notify("Hooks installed for: ${done.joinToString()}. Restart running agent sessions so they load them.")
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Install Agent Hooks")
        }
    }
}

class UninstallHooksAction : TurnAction() {
    override fun perform(project: Project, e: AnActionEvent) {
        val installed = targets().filter { it.isInstalled(it.existing()) }
        if (installed.isEmpty()) { TurnService.getInstance(project).notify("No Latest AI Turn hooks are installed."); return }
        val ok = Messages.showYesNoDialog(project,
            "Remove only the Latest AI Turn hooks from:\n" + installed.joinToString("\n") { "  ${it.configFile}" },
            "Uninstall Agent Hooks", "Remove", "Cancel", Messages.getQuestionIcon())
        if (ok != Messages.YES) return
        try {
            installed.forEach { backupAndWrite(it.configFile, it.uninstall(it.existing())) }
            TurnService.getInstance(project).notify("Hooks removed.")
        } catch (ex: Exception) {
            Messages.showErrorDialog(project, ex.message ?: ex.toString(), "Uninstall Agent Hooks")
        }
    }
}
