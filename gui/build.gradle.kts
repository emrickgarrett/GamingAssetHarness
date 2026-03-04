plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}

val appVersion: String by project

dependencies {
    implementation(project(":core"))
    implementation(project(":agent"))

    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.logback.classic)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "dev.gameharness.gui.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb
            )
            packageName = "GameDeveloperHarness"
            packageVersion = appVersion
            description = "AI-powered game asset generator using chat-based interaction"
            vendor = "Game Developer Harness"
            copyright = "2026 Game Developer Harness"

            windows {
                menuGroup = "Game Developer Harness"
                shortcut = true
            }

            linux {
                shortcut = true
            }
        }
    }
}

// ── Version resource generation ─────────────────────────────────────────────
// Generates version.properties on the classpath so BuildInfo can read the
// version at runtime. The version comes from gradle.properties (appVersion)
// and can be overridden via -PappVersion=X.Y.Z on the command line.

val generateVersionProperties by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/resources/version")
    outputs.dir(outputDir)
    inputs.property("version", appVersion)

    doLast {
        val propsFile = outputDir.get().asFile.resolve("version.properties")
        propsFile.parentFile.mkdirs()
        propsFile.writeText("app.version=$appVersion\n")
    }
}

sourceSets.main {
    resources.srcDir(generateVersionProperties.map {
        layout.buildDirectory.dir("generated/resources/version")
    })
}

tasks.named("processResources") {
    dependsOn(generateVersionProperties)
}
