# kotlinx.serialization: keep generated serializers for the app's @Serializable models.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class dev.wledambilight.** {
    *** Companion;
}
-keepclasseswithmembers class dev.wledambilight.** {
    kotlinx.serialization.KSerializer serializer(...);
}
