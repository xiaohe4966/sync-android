# Default ProGuard rules for SquadSync. Keep Ktor & kotlinx.serialization metadata.
-keepattributes *Annotation*
-keepclassmembers class * { kotlinx.serialization.KSerializer serializer(...); }
-keep class io.ktor.** { *; }
-keep class kotlinx.serialization.** { *; }