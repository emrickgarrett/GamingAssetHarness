package dev.gameharness.cli

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.commands.ConfigShow
import dev.gameharness.cli.commands.asset.AssetCmd
import dev.gameharness.cli.commands.asset.AssetList
import dev.gameharness.cli.commands.generate.*
import dev.gameharness.cli.commands.workspace.WorkspaceCmd
import dev.gameharness.cli.commands.workspace.WorkspaceCreate
import dev.gameharness.cli.commands.workspace.WorkspaceList

fun main(args: Array<String>) {
    GameHarnessCli()
        .subcommands(
            Generate().subcommands(
                GenerateSprite(),
                GenerateModel(),
                GenerateMusic(),
                GenerateSfx()
            ),
            WorkspaceCmd().subcommands(
                WorkspaceList(),
                WorkspaceCreate()
            ),
            AssetCmd().subcommands(
                AssetList()
            ),
            ConfigShow()
        )
        .main(args)
}
