package dev.gameharness.cli

import com.github.ajalt.clikt.core.subcommands
import dev.gameharness.cli.commands.ConfigShow
import dev.gameharness.cli.commands.asset.AssetCleanup
import dev.gameharness.cli.commands.asset.AssetCmd
import dev.gameharness.cli.commands.asset.AssetList
import dev.gameharness.cli.commands.asset.AssetRevise
import dev.gameharness.cli.commands.asset.AssetTrim
import dev.gameharness.cli.commands.generate.*
import dev.gameharness.cli.commands.sculpt.SculptBake
import dev.gameharness.cli.commands.sculpt.SculptCmd
import dev.gameharness.cli.commands.sculpt.SculptInit
import dev.gameharness.cli.commands.sculpt.SculptNext
import dev.gameharness.cli.commands.sculpt.SculptRender
import dev.gameharness.cli.commands.sculpt.SculptRun
import dev.gameharness.cli.commands.workspace.WorkspaceCmd
import dev.gameharness.cli.commands.workspace.WorkspaceCreate
import dev.gameharness.cli.commands.workspace.WorkspaceList
import dev.gameharness.cli.commands.workspace.WorkspaceOpen

fun main(args: Array<String>) {
    GameHarnessCli()
        .subcommands(
            Generate().subcommands(
                GenerateSprite(),
                GenerateModel(),
                GenerateRig(),
                GenerateAnimation(),
                GenerateMusic(),
                GenerateSfx()
            ),
            WorkspaceCmd().subcommands(
                WorkspaceList(),
                WorkspaceCreate(),
                WorkspaceOpen()
            ),
            AssetCmd().subcommands(
                AssetList(),
                AssetRevise(),
                AssetTrim(),
                AssetCleanup()
            ),
            SculptCmd().subcommands(
                SculptInit(),
                SculptNext(),
                SculptRun(),
                SculptBake(),
                SculptRender()
            ),
            ConfigShow()
        )
        .main(args)
}
