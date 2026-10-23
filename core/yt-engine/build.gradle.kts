import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-opt-in=kotlin.time.ExperimentalTime")
    }
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-opt-in=kotlin.time.ExperimentalTime")
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.addAll("-Xskip-metadata-version-check", "-opt-in=kotlin.time.ExperimentalTime")
        }
    }

    sourceSets {
        // PipePipe is a JVM library, so its glue is shared by the android and jvm targets.
        androidMain { kotlin.srcDir("src/jvmAndroidMain/kotlin") }
        jvmMain { kotlin.srcDir("src/jvmAndroidMain/kotlin") }
        val commonMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.ktor.client.content.negotiation)
                implementation(libs.ktor.serialization.kotlinx.json)
                implementation(libs.ktor.client.encoding)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.quickjs)
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
                implementation(libs.ktor.client.okhttp)
                implementation(libs.quickjs.android)
                implementation(libs.pipepipe.extractor)
                implementation(libs.okhttp)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.quickjs.jvm)
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.quickjs.jvm)
                implementation(libs.pipepipe.extractor)
                implementation(libs.okhttp)
                // Android ships org.json; the plain JVM target (used by tests) does not.
                implementation(libs.org.json)
            }
        }
    }
}

android {
    namespace = "com.qmusic.engine"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
