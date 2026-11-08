# TCG Scan R8 configuration.
# Everything kept here is reachable only through JNI or reflection, which R8 cannot see.

# ONNX Runtime (D5/D9): the native library resolves these classes, their fields and their
# constructors by name from C++. The telemetry provider is removed in the manifest, not here.
-keep class ai.onnxruntime.** { *; }
-keepclassmembers class ai.onnxruntime.** { native <methods>; }
-dontwarn ai.onnxruntime.**

# OpenCV (D4): libopencv_java4.so instantiates Mat and friends through JNI.
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { native <methods>; }
-dontwarn org.opencv.**

# kotlinx.serialization: keep the generated serializers of our own @Serializable models.
-keepclassmembers class org.tcgscan.app.** {
    *** Companion;
}
-keepclasseswithmembers class org.tcgscan.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class org.tcgscan.app.**$$serializer { *; }

# Room: the generated *_Impl classes are looked up reflectively by Room.getGeneratedImplementation.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# OkHttp / Okio: optional platform integrations that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
