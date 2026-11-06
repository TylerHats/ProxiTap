# Keep application classes and members so reflection/JNI/Compose navigation never breaks,
# while allowing R8 to shrink unused 3rd-party library code and embed proguard.map in the AAB.
-keep class com.tylerhats.proxitap.** { *; }

# WebRTC JNI & Native callbacks
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# Ktor Server & Client CIO / WebSockets
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.slf4j.**
-dontwarn java.lang.management.**

# MLKit Barcode Scanning & CameraX
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-keep class com.google.android.odml.** { *; }
-keep class androidx.camera.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.odml.**
-dontwarn androidx.camera.**

# Preserve source file names and line numbers for accurate Play Console crash/ANR deobfuscation
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

