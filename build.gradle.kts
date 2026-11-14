plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
}

/**
 * No test may reach the developer's own settings, workspace registry or API
 * keys.
 *
 * Several CLI tests build the real command tree, which reads
 * `~/.gameharness` and the key environment variables. On a machine where keys
 * are configured, "generate model fails when API key not configured" did not
 * fail: it generated a model with the real Meshy key, minutes long and paid
 * for, and every run left a temporary workspace in the real registry. Each
 * test JVM therefore gets an empty home directory of its own and blank keys.
 */
subprojects {
    tasks.withType<Test>().configureEach {
        val testHome = layout.buildDirectory.dir("test-home")
        doFirst {
            val home = testHome.get().asFile
            home.deleteRecursively()
            home.mkdirs()
        }
        systemProperty("user.home", testHome.get().asFile.absolutePath)
        listOf("OPENROUTER_API_KEY", "GEMINI_API_KEY", "MESHY_API_KEY", "SUNO_API_KEY", "ELEVENLABS_API_KEY")
            .forEach { environment(it, "") }
    }
}
