# Keep rules for QMusic (release builds with R8 enabled).

# kotlinx.serialization models used by the YouTube engine, Spotify importer and updater.
-keepclassmembers class com.qmusic.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.qmusic.**$$serializer { *; }
-keep class com.qmusic.engine.models.** { *; }
-keep class com.qmusic.spotify.model.** { *; }
-keep class com.qmusic.updater.model.** { *; }

# QuickJS native bridge used by the cipher engine.
-keep class com.dokar.quickjs.** { *; }
-keep class io.github.dokar3.quickjs.** { *; }

# Media3 service entry point.
-keep class com.qmusic.player.service.SimpleMediaService { *; }

# Room generated database implementation.
-keep class * extends androidx.room.RoomDatabase { *; }

-dontwarn org.slf4j.**
-dontwarn javax.annotation.**

# PipePipe extractor (NewPipe fork) and what it loads by name.
-keep class dev.maxrave.pipepipe.** { *; }
-dontwarn dev.maxrave.pipepipe.**
-keep class com.grack.nanojson.** { *; }
-keep class com.squareup.wire.** { *; }
-keep class org.schabi.** { *; }
-dontwarn org.jsoup.**
-dontwarn com.google.protobuf.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.brotli.**
-dontwarn javax.script.**
-dontwarn org.mozilla.javascript.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn org.java_websocket.**
-dontwarn org.slf4j.**

# OkHttp 4.x (ktor, media3) and 5.x (PipePipe) share a classpath; the unused SSE helper still references the old Util.
-dontwarn okhttp3.internal.Util

# Named only in the manifest (cast options meta-data), so R8 cannot see it is used.
-keep class com.qmusic.app.CastOptionsProvider { *; }
