package dev.gameharness.api.common

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Creates a Ktor [HttpClient] with the CIO engine pre-configured for API usage.
 *
 * Includes JSON content negotiation (lenient, ignoring unknown keys) and generous
 * timeouts suitable for long-running generation APIs (120 s request/socket, 15 s connect).
 */
fun createDefaultHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    }
}
