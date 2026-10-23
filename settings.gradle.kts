pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "QMusic"

include(":androidApp")
include(":composeApp")
include(":core:yt-engine")
include(":core:spotify")
include(":core:player")
include(":core:database")
include(":core:updater")

project(":core:yt-engine").projectDir = file("core/yt-engine")
project(":core:spotify").projectDir = file("core/spotify")
project(":core:player").projectDir = file("core/player")
project(":core:database").projectDir = file("core/database")
project(":core:updater").projectDir = file("core/updater")

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
