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

    // 3D model viewer: LWJGL (OpenGL bindings, a hidden GLFW window for the
    // context) + jglTF (GLB and glTF loading) + Assimp (FBX and OBJ loading)
    implementation(libs.lwjgl)
    implementation(libs.lwjgl.opengl)
    implementation(libs.lwjgl.glfw)
    implementation(libs.lwjgl.assimp)
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
    runtimeOnly("org.lwjgl:lwjgl-assimp:$lwjglVersion:$lwjglNatives")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
    // the benchmark reads a workspace.json itself, so it never goes through the workspace manager
    testImplementation(libs.kotlinx.serialization.json)
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
 * Times the asset browser against a real workspace with no window on screen:
 * first frame of each folder, the frames of a scroll to its end, the way back.
 * The workspace file is only read.
 *
 *   ./gradlew :gui:browserBench --args="<workspace.json> <out-dir> [width] [height] [folder ...]"
 */
tasks.register<JavaExec>("browserBench") {
    group = "verification"
    description = "Time folder navigation in the asset browser (args: <workspace.json> <out-dir> [w] [h])"
    mainClass.set("dev.gameharness.gui.perf.BrowserBench")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("-Xmx2g")
}

/**
 * Drives the asset panel of a real workspace with no window on screen and saves
 * pictures of it: open a folder, open a model, orbit it. The actions are listed
 * in UiShots.kt. The workspace file is only read.
 *
 *   ./gradlew :gui:uiShots --args="<workspace.json> <out-dir> 1100 900 folder:rigs settle shot:rigs"
 */
tasks.register<JavaExec>("uiShots") {
    group = "verification"
    description = "Screenshot the asset browser and model viewer headlessly (args: <workspace.json> <out-dir> <w> <h> <action> ...)"
    mainClass.set("dev.gameharness.gui.perf.UiShots")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("-Xmx2g")
}

/**
 * Times the 3D viewer on model files with no window: opening a model, and the
 * pictures per second while it is turned and while its animation plays.
 *
 *   ./gradlew :gui:viewerBench --args="1400 1200 <model> [<model> ...]"
 */
tasks.register<JavaExec>("viewerBench") {
    group = "verification"
    description = "Time the 3D viewer (args: <w> <h> <model> ...)"
    mainClass.set("dev.gameharness.gui.perf.ViewerBench")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("-Xmx2g")
}

/**
 * Runs the whole application in a real window, works it with made-up mouse
 * events (the real pointer is not moved) and saves pictures of the window. The
 * actions are listed in WindowCheck.kt.
 *
 * It is the real app, so it must be given a throwaway home directory: settings,
 * the workspace registry and logs are read from and written to
 * <checkHome>/.gameharness, never the user's own. The API key is a placeholder;
 * nothing is sent unless a chat message is typed.
 *
 *   ./gradlew :gui:windowCheck -PcheckHome=<dir> --args="<out-dir> 1300 900 wait:2000 shot:start"
 */
tasks.register<JavaExec>("windowCheck") {
    group = "verification"
    description = "Drive the real app window with synthetic input (needs -PcheckHome=<throwaway dir>)"
    mainClass.set("dev.gameharness.gui.perf.WindowCheck")
    classpath = sourceSets["test"].runtimeClasspath
    val checkHome = providers.gradleProperty("checkHome")
    doFirst {
        require(checkHome.isPresent) { "windowCheck runs the real app: pass -PcheckHome=<throwaway dir> so it leaves your own settings alone" }
        systemProperty("user.home", checkHome.get())
        systemProperty("gameharness.thumbnailCache", File(checkHome.get(), "thumbnails").path)
    }
    environment("OPENROUTER_API_KEY", "window-check-placeholder")
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
