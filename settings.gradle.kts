pluginManagement {
    repositories { google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }; maven("https://repo1.maven.org/maven2/"); gradlePluginPortal() }
    // Versions live here so the Android plugins resolve only when :app is included (no SDK = core only).
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.21"
        id("org.jetbrains.kotlin.jvm") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google { content { includeGroupByRegex("com\\.android.*|com\\.google.*|androidx.*") } }; maven("https://repo1.maven.org/maven2/") }
}
rootProject.name = "UrTV-Player"
include(":core")
// The Android app needs the Android SDK; the pure-Kotlin core builds and tests anywhere.
val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
if (sdk != null || file("local.properties").exists()) include(":app")
