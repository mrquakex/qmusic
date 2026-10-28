import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xskip-metadata-version-check")
        }
    }
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xskip-metadata-version-check")
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(project(":core:yt-engine"))
                implementation(project(":core:spotify"))
                implementation(project(":core:player"))
                implementation(project(":core:database"))
                implementation(project(":core:updater"))

                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.components.resources)

                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)

                implementation(libs.coil.compose)
                implementation(libs.coil.network.ktor3)

                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.kotlinx.coroutines.test)
            }
        }
        val androidMain by getting {
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.activity.compose)
                implementation(libs.androidx.webkit)
                implementation(libs.media3.exoplayer)
                implementation(libs.media3.ui)
                implementation("androidx.appcompat:appcompat:1.7.0")
                implementation("androidx.mediarouter:mediarouter:1.7.0")
                implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
                implementation(libs.koin.android)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
                implementation(libs.okhttp)
                implementation("org.openjfx:javafx-base:21.0.5:win")
                implementation("org.openjfx:javafx-graphics:21.0.5:win")
                implementation("org.openjfx:javafx-media:21.0.5:win")
            }
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.qmusic.MainKt"
        nativeDistributions {
            includeAllModules = true
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
            )
            packageName = "QMusic"
            packageVersion = "2.0.3"
        }
    }
}

android {
    namespace = "com.qmusic.ui"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
