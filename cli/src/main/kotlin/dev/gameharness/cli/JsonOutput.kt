package dev.gameharness.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

private val json = Json {
    prettyPrint = false
    encodeDefaults = true
    ignoreUnknownKeys = true
}

@Serializable
data class CliResponse(
    val success: Boolean,
    val command: String,
    val data: JsonElement? = null,
    val error: CliError? = null
)

@Serializable
data class CliError(
    val code: String,
    val message: String
)

fun printSuccess(command: String, data: JsonElement) {
    val response = CliResponse(success = true, command = command, data = data)
    println(json.encodeToString(CliResponse.serializer(), response))
}

fun printError(command: String, code: String, message: String) {
    val response = CliResponse(
        success = false,
        command = command,
        error = CliError(code, message)
    )
    println(json.encodeToString(CliResponse.serializer(), response))
}

fun assetToJson(
    id: String,
    fileName: String,
    filePath: String,
    type: String,
    format: String,
    description: String,
    sizeBytes: Long,
    status: String,
    folder: String
): JsonObject = buildJsonObject {
    put("assetId", id)
    put("fileName", fileName)
    put("filePath", filePath)
    put("type", type)
    put("format", format)
    put("description", description)
    put("sizeBytes", sizeBytes)
    put("status", status)
    put("folder", folder)
}

fun revisionAssetToJson(
    asset: dev.gameharness.core.model.GeneratedAsset,
    originalFileName: String
): JsonObject = buildJsonObject {
    put("assetId", asset.id)
    put("fileName", asset.fileName)
    put("filePath", asset.filePath)
    put("type", asset.type.name)
    put("format", asset.format)
    put("description", asset.description)
    put("sizeBytes", asset.sizeBytes)
    put("status", asset.status.name)
    put("folder", asset.folder)
    put("originalFileName", originalFileName)
}
