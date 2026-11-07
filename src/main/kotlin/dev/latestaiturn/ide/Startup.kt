package dev.latestaiturn.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class Startup : ProjectActivity {
    override suspend fun execute(project: Project) {
        TurnService.getInstance(project).start()
    }
}
