pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CarosConnect"
include(":app")

// Wspólny moduł z repo CAR OS (Car-lanchuer/core): protokół auto ↔ telefon, szyfrowanie, stacje przy trasie.
// W GitHub Actions pobierany do caros-core-src (commit z gradle.properties: coreRef), lokalnie – repo CAR OS obok.
val coreDir = listOf("caros-core-src/core", "../CarLauncher/core", "../Car-lanchuer/core")
    .map { file(it) }.firstOrNull { it.resolve("build.gradle.kts").exists() }
    ?: error("Brak modułu core – pobierz repo Car-lanchuer obok albo uruchom krok „core” z build.yml")
include(":core")
project(":core").projectDir = coreDir
