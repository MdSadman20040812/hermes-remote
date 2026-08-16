# Hermes Mobile ProGuard rules
# Keep Room entities + DAOs
-keep class com.hermes.mobile.data.local.** { *; }
-keep class com.hermes.mobile.data.remote.dto.** { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}

# Keep serialization annotations
-keepattributes *Annotation*
-dontwarn kotlinx.serialization.**

# Keep Drive API client
-keep class com.google.api.services.drive.** { *; }
-dontwarn com.google.api.services.drive.**

# Kotlinx serialization
-keepattributes RuntimeVisibleAnnotations, RuntimeInvisibleAnnotations
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}
