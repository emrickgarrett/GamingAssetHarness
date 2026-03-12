package dev.gameharness.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import dev.gameharness.cli.CliContext
import dev.gameharness.cli.printSuccess
import kotlinx.serialization.json.*

class ConfigShow : CliktCommand(name = "config") {

    private val ctx by requireObject<CliContext>()

    override fun run() {
        val config = ctx.config
        val capabilities = buildJsonArray {
            addJsonObject {
                put("type", "SPRITE")
                put("service", "NanoBanana (Gemini)")
                put("available", !config.geminiApiKey.isNullOrBlank())
                if (!config.geminiApiKey.isNullOrBlank()) {
                    put("model", config.nanoBananaModel)
                }
            }
            addJsonObject {
                put("type", "MODEL_3D")
                put("service", "Meshy")
                put("available", !config.meshyApiKey.isNullOrBlank())
            }
            addJsonObject {
                put("type", "MUSIC")
                put("service", "Suno")
                put("available", !config.sunoApiKey.isNullOrBlank())
            }
            addJsonObject {
                put("type", "SOUND_EFFECT")
                put("service", "ElevenLabs")
                put("available", !config.elevenLabsApiKey.isNullOrBlank())
            }
        }

        printSuccess("config", buildJsonObject {
            put("capabilities", capabilities)
        })
    }
}
