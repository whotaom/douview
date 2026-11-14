# Project-specific ProGuard / R8 keep rules

# Keep WebView JavascriptInterface methods intact
-keepclassmembers class com.example.MainActivity$* {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep TV channel models & release data models
-keep class com.example.tv.channel.** { *; }
-keep class com.example.util.AppReleaseInfo { *; }

# Keep Media3 / ExoPlayer classes referenced by reflection
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.ui.** { *; }
-keep class androidx.media3.datasource.** { *; }

# Moshi & OkHttp compatibility
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
