# NewPipeExtractor & Mozilla Rhino
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keep class org.schabi.newpipe.extractor.** { *; }

# YoutubeDL-Android & Jackson
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.pulsewave.music.**$$serializer { *; }
-keepclassmembers class com.pulsewave.music.** {
    *** Companion;
}
-keepclasseswithmembers class com.pulsewave.music.** {
    kotlinx.serialization.KSerializer serializer(...);
}
