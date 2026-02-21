# Ktor
-keep class io.ktor.** { *; }
-keep class io.ktor.server.** { *; }
-keepclassmembers class io.ktor.** { *; }
-dontwarn io.ktor.**

# Netty
-keep class io.netty.** { *; }
-dontwarn io.netty.**
-keepclassmembers class io.netty.** { *; }

# kotlinx.coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# SLF4J
-dontwarn org.slf4j.**
-keep class org.slf4j.** { *; }

# app classes
-keep class dev.agzes.swebapp.server.** { *; }
-keep class dev.agzes.swebapp.service.** { *; }
-keep class dev.agzes.swebapp.receiver.** { *; }
-keep class dev.agzes.swebapp.widget.** { *; }