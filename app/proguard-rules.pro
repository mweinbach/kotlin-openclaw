# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class ai.openclaw.**$$serializer { *; }
-keepclassmembers class ai.openclaw.** {
    *** Companion;
}
-keepclasseswithmembers class ai.openclaw.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }

# BouncyCastle
-keep class org.bouncycastle.** { *; }

# Netty optional dependencies (not available on Android)
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn reactor.blockhound.**
-dontwarn java.lang.management.**
-dontwarn com.sun.nio.file.**

# Java 9+ APIs not available on Android
-dontwarn java.lang.ProcessHandle
