# kotlinx.serialization keeps generated serializers
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class io.github.abrahampaulj.pocketfuse.** { kotlinx.serialization.KSerializer serializer(...); }

# libwebrtc and MediaPipe are called from native code
-keep class org.webrtc.** { *; }
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn org.webrtc.**

# MediaPipe ships AutoValue's annotation processor; its compile-time javax.lang.model references never run
-dontwarn javax.lang.model.**
-dontwarn autovalue.shaded.**
-dontwarn com.google.auto.value.**
