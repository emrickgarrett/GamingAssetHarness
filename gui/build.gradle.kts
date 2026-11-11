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

    // 3D model viewer: LWJGL (OpenGL bindings) + jglTF (GLB loading)
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.opengl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl3.awt) {
        // Exclude transitive LWJGL deps with unresolved ${lwjgl.natives} classifier
        exclude(group = "org.lwjgl")
    }
    implementation(libs.jgltf.model)

    // LWJGL native libraries (platform-specific)
    val lwjglVersion = libs.versions.lwjgl.get()
    val lwjglNatives = when {
        System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "natives-windows"
        System.getProperty("os.name").contains("Mac", ignoreCase = true) -> {
            if (System.getProperty("os.arch") == "aarch64") "natives-macos-arm64" else "natives-macos"
        }
        else -> "natives-linux"
    }
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl:$lwjglVersion:$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw:$lwjglVersion:$lwjglNatives")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

/**
 * Headless GLB → PNG render, used by `harness sculpt render` to produce the
 * screenshots the img2threejs review gates need. Lives here because it reuses
 * the viewer's own renderer, so review shots match the asset window exactly.
 *
 *   ./gradlew :gui:renderGlb --args="model.glb out.png 720 720 35 20"
 */
tasks.register<JavaExec>("renderGlb") {
    group = "sculpt"
    description = "Render a GLB to a PNG offscreen (args: <glb> <png> [w] [h] [azimuth] [elevation])"
    mainClass.set("dev.gameharness.gui.viewer3d.OffscreenRenderMain")
    classpath = sourceSets["main"].runtimeClasspath
    // macOS parks GLFW on the main thread; harmless elsewhere
    if (System.getProperty("os.name").contains("Mac", ignoreCase = true)) {
        jvmArgs("-XstartOnFirstThread")
    }
}

/**
 * Publishes this module's runtime classpath so `harness sculpt render` can spawn
 * the offscreen renderer in a plain JVM.
 *
 * WHY NOT just make :cli depend on :gui — the CLI is deliberately lean (core +
 * api-clients + clikt); depending on :gui would drag all of Compose Desktop onto
 * every CLI invocation. WHY NOT shell out to the Gradle wrapper — `sculpt render`
 * is itself usually running inside `:cli:run`, and a nested build can block on
 * the same project locks. A classpath file costs nothing and avoids both.
 */
val renderClasspath = tasks.register("renderClasspath") {
    group = "sculpt"
    description = "Write the renderer's runtime classpath for the CLI to spawn"
    val cp = sourceSets["main"].runtimeClasspath
    val outFile = layout.buildDirectory.file("renderer-classpath.txt")
    inputs.files(cp)
    outputs.file(outFile)
    doLast {
        outFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(cp.joinToString(File.pathSeparator) { it.absolutePath })
        }
    }
}

// keep the published classpath in step with the code it points at
tasks.named("classes") { finalizedBy(renderClasspath) }

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
