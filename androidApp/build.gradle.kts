import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

// Push notifications for new versions need the Firebase config; without the file the app builds and runs without them.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.qmusic.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qmusic.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 + resource shrinking: the release build is what feels smooth; debug runs Compose unoptimised.
            isMinifyEnabled = true
            isShrinkResources = true
            // Signed with the debug key so it can be installed straight from the build for daily use.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // quickjs-kt pulls a newer kotlin-stdlib (2.4 metadata); same workaround as the core modules.
        freeCompilerArgs.addAll("-Xskip-metadata-version-check")
    }
}

// --- Live install loop -------------------------------------------------------------------------
// `gradlew launchDebug --continuous` rebuilds, reinstalls and reopens the app on the phone every time a
// source file is saved; `launchRelease` does the same for the optimised build.
val adbExecutable = File(android.sdkDirectory, "platform-tools/adb").absolutePath

fun registerLaunchTask(name: String, installTask: String, applicationId: String) {
    tasks.register<Exec>(name) {
        group = "qmusic"
        description = "Installs the build on the connected device and opens it."
        dependsOn(installTask)
        commandLine(adbExecutable, "shell", "am", "start", "-n", "$applicationId/com.qmusic.app.MainActivity")
    }
}
registerLaunchTask("launchDebug", "installDebug", "com.qmusic.app.debug")
registerLaunchTask("launchRelease", "installRelease", "com.qmusic.app")

dependencies {
    implementation(project(":composeApp"))
    implementation(project(":core:player"))
    implementation(project(":core:yt-engine"))
    implementation(project(":core:database"))
    implementation(project(":core:spotify"))
    implementation(project(":core:updater"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.koin.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // Compiles the libraries' bundled baseline profiles on install, so first launch is not interpreted.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
