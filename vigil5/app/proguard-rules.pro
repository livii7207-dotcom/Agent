# VIGIL-5 ProGuard rules. Release builds ship with minification disabled by
# default; these rules exist so enabling R8 later doesn't strip Ktor / kotlinx
# serialization / Compose internals.

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.vigil5.app.**$$serializer { *; }
-keepclassmembers class com.vigil5.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.vigil5.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor
-keep class io.ktor.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.**

# OkHttp (transitive via ktor-client-okhttp)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
