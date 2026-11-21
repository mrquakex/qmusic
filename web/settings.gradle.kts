// Nuvelo Web: its own build (newer Kotlin / Compose than the phone app), so trying it never touches the Android build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "nuvelo-web"
