# Add project specific ProGuard rules here.

# Keep Room entities
-keep class com.vivoios.emojichanger.model.** { *; }
-keep class com.vivoios.emojichanger.db.** { *; }

# Keep engine classes for reflection
-keep class com.vivoios.emojichanger.engine.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule { *; }
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }

# Lottie
-dontwarn com.airbnb.lottie.**
-keep class com.airbnb.lottie.** { *; }

# Keep service + receiver names for manifest registration
-keep class com.vivoios.emojichanger.service.** { *; }
-keep class com.vivoios.emojichanger.receiver.** { *; }
