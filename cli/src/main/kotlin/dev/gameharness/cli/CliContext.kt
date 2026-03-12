package dev.gameharness.cli

import dev.gameharness.core.config.AppConfig
import dev.gameharness.core.config.SettingsManager
import dev.gameharness.core.workspace.WorkspaceManager
import java.nio.file.Paths

class CliContext {
    val settingsManager = SettingsManager()
    val workspaceManager = WorkspaceManager(
        Paths.get(System.getProperty("user.home"), ".gameharness")
    )
    val config: AppConfig by lazy { AppConfig.forCli(settingsManager) }
}
