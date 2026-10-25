# Keep kotlinx-serialization generated serializers
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.kotunnel.client.**$$serializer { *; }
-keepclassmembers class com.kotunnel.client.** {
    *** Companion;
}
-keepclasseswithmembers class com.kotunnel.client.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Tink (via security-crypto) references errorprone annotations that aren't on runtime classpath.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.google.crypto.tink.**
