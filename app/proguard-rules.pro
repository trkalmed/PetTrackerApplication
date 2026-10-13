# R8 rules for the release build.

# TensorFlow Lite: native bindings are loaded through JNI.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.tensorflow.lite.gpu.**

# ML Kit: subject segmentation is looked up by name at runtime.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# Room: the generated database is instantiated by name.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# The Pet entity is constructed reflectively by the generated DAO.
-keepclassmembers class com.example.pettrackerapplication.Pet {
    <init>(...);
    public *** get*();
    public void set*(***);
}

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# Firebase Auth
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# GraphicOverlay is inflated from XML by class name.
-keep class com.example.pettrackerapplication.GraphicOverlay {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Keep line numbers so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
