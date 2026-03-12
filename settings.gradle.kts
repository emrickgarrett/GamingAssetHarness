rootProject.name = "GameDeveloperHarness"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":core")
include(":api-clients")
include(":agent")
include(":gui")
include(":cli")
