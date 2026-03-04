package dev.gameharness.gui

import java.util.Properties

/**
 * Provides build-time metadata at runtime.
 *
 * The version is read from `version.properties`, a classpath resource generated
 * by the `generateVersionProperties` Gradle task. The value originates from
 * the `appVersion` property in `gradle.properties` (overridable via
 * `-PappVersion=X.Y.Z` on the command line).
 *
 * Falls back to `"dev"` if the resource is unavailable (e.g. running directly
 * from an IDE without a Gradle build).
 */
object BuildInfo {
    val version: String by lazy {
        val props = Properties()
        val stream = BuildInfo::class.java.getResourceAsStream("/version.properties")
        if (stream != null) {
            stream.use { props.load(it) }
            props.getProperty("app.version", "dev")
        } else {
            "dev"
        }
    }
}
