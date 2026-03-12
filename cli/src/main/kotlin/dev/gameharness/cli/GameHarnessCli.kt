package dev.gameharness.cli

import com.github.ajalt.clikt.core.CliktCommand

class GameHarnessCli : CliktCommand(name = "gameharness") {

    override fun run() {
        currentContext.findOrSetObject { CliContext() }
    }
}
