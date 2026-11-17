package dev.gameharness.cli.commands.generate

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import dev.gameharness.api.common.ApiException
import dev.gameharness.cli.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** Free questions to Meshy: `meshy balance` (credits left), `meshy library [-s word]` (animation actions). */
class MeshyCmd : CliktCommand(name = "meshy") {
    override fun run() = Unit
}

class MeshyBalanceCmd : CliktCommand(name = "balance") {
    private val ctx by requireObject<CliContext>()

    override fun run() {
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("meshy.balance", "MISSING_KEY", e.message ?: "Missing API key")
        }
        try {
            val b = runBlocking { client.balance() }
            printSuccess("meshy.balance", buildJsonObject {
                put("balance", b.balance)
                b.monthlyCredits?.let { put("monthly", it) }
                b.permanentCredits?.let { put("permanent", it) }
            })
        } catch (e: ApiException) {
            printError("meshy.balance", "FAILED", e.message ?: "Failed")
        } finally {
            client.close()
        }
    }
}

class MeshyLibraryCmd : CliktCommand(name = "library") {
    private val ctx by requireObject<CliContext>()
    private val search by option("-s", "--search", help = "Find actions whose name holds this").default("")

    override fun run() {
        val client = try {
            ClientFactory(ctx.config, ProgressReporter()).createModelClient()
        } catch (e: MissingKeyException) {
            return printError("meshy.library", "MISSING_KEY", e.message ?: "Missing API key")
        }
        try {
            val list = runBlocking { client.library(search) }
            printSuccess("meshy.library", buildJsonObject {
                putJsonArray("actions") {
                    for (a in list) addJsonObject {
                        put("id", a.actionId)
                        put("name", a.name)
                        a.category?.let { put("category", it) }
                        a.subCategory?.let { put("sub", it) }
                    }
                }
            })
        } catch (e: ApiException) {
            printError("meshy.library", "FAILED", e.message ?: "Failed")
        } finally {
            client.close()
        }
    }
}
