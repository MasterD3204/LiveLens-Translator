# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /usr/local/android-sdk-tools/proguard/proguard-android.txt

# Keep Hilt components
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepattributes *Annotation*

# Keep Room entities and DAOs
-keep class com.livelens.translator.data.db.** { *; }

# Keep sherpa-onnx JNI
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclassmembers class com.k2fsa.sherpa.onnx.** { *; }

# Keep MediaPipe
-keep class com.google.mediapipe.** { *; }
-keepclassmembers class com.google.mediapipe.** { *; }

# Keep Kotlin coroutines
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep Compose
-keep class androidx.compose.** { *; }
-keepclassmembers class androidx.compose.** { *; }

# Keep accessibility service
-keep public class com.livelens.translator.service.TranslatorAccessibilityService
-keep public class com.livelens.translator.service.AudioCaptureService

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
