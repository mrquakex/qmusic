import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    kotlin("multiplatform") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    id("org.jetbrains.compose") version "1.12.2"
}

val app = rootDir.resolve("../composeApp/src/commonMain/kotlin")

/**
 * The phone app's messages section, the same files (not copies kept by hand): the chat screens and their logic, the
 * colours and icons, the page motion and the few app pieces they use. Only what the web needs is taken.
 */
val sharedSources = tasks.register<Sync>("sharedSources") {
    from(app) {
        include(
            "com/qmusic/ui/chat/**",
            "com/qmusic/ui/theme/**",
            "com/qmusic/ui/motion/**",
            "com/qmusic/ui/PlatformBackHandler.kt",
            "com/qmusic/ui/PlatformShare.kt",
            "com/qmusic/ui/DominantColor.kt",
            "com/qmusic/ui/components/QeyfBar.kt",
        )
    }
    into(layout.buildDirectory.dir("generated/shared/kotlin"))
}

/** The app's version (the same number as the phone's, from the root gradle.properties). */
val appVersion = tasks.register("appVersion") {
    val props = rootDir.resolve("../gradle.properties")
    val out = layout.buildDirectory.dir("generated/appVersion/kotlin")
    inputs.file(props)
    outputs.dir(out)
    doLast {
        val name = props.readLines().first { it.startsWith("qmusic.versionName=") }.substringAfter("=").trim()
        val file = out.get().file("com/qmusic/ui/AppVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package com.qmusic.ui\n\nobject AppVersion {\n    const val NAME: String = \"$name\"\n}\n")
    }
}

kotlin {
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        outputModuleName.set("nuvelo")
        browser {
            commonWebpackConfig { outputFileName = "nuvelo.js" }
        }
        binaries.executable()
    }

    compilerOptions {
        freeCompilerArgs.addAll("-Xexpect-actual-classes", "-nowarn")
    }

    sourceSets {
        val wasmJsMain by getting {
            dependencies {
                implementation(npm("firebase", "11.10.0"))
                implementation(npm("@noble/curves", "1.9.2"))
                implementation(npm("@noble/ciphers", "1.3.0"))
                implementation(npm("@noble/hashes", "1.8.0"))
                implementation(npm("qrcode-generator", "1.4.4"))
                // The browser's half of the page (Firebase, the cryptography, files): web/bridge.
                implementation(npm("nuvelo-bridge", projectDir.resolve("bridge")))
            }
        }
        commonMain {
            kotlin.srcDir(sharedSources)
            kotlin.srcDir(appVersion)
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.7.1-0.6.x-compat")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
                implementation("io.coil-kt.coil3:coil-compose:3.3.0")
                implementation("io.coil-kt.coil3:coil-network-ktor3:3.3.0")
                implementation("io.ktor:ktor-client-js:3.2.3")
                // Claude's answers: Markdown with highlighted code (as on the phone).
                implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.30.0")
                implementation("com.mikepenz:multiplatform-markdown-renderer-code:0.30.0")
            }
        }
    }
}

compose.resources {
    packageOfResClass = "com.nuvelo.web.res"
    publicResClass = false
}
