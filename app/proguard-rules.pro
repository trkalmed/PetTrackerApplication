# R8 rules for the release build.
#
# Most of this app is plain Java that R8 can shrink freely. The exceptions are
# the libraries that resolve classes reflectively or through JNI, where R8 has
# no call graph to follow and will happily strip something that is still used.

# ── TensorFlow Lite ───────────────────────────────────────────────────────────
# The task-vision API loads its native bindings through JNI, so the Java peers
# and their native methods must survive. GPU delegate classes are referenced
# optionally and produce "missing class" warnings when absent — that is expected.
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-keep class org.tensorflow.lite.task.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.tensorflow.lite.gpu.**

# ── ML Kit / Play Services ───────────────────────────────────────────────────
# Subject segmentation is delivered as a downloadable module and looked up by
# name at runtime.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-dontwarn com.google.mlkit.**

# ── Room ─────────────────────────────────────────────────────────────────────
# The generated implementation is instantiated by name from the @Database class.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# The Pet entity is constructed reflectively by the generated DAO.
-keepclassmembers class com.example.pettrackerapplication.Pet {
    <init>(...);
    public *** get*();
    public void set*(***);
}

# ── Glide ────────────────────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# ── Firebase Auth ────────────────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# ── Custom views inflated from XML ───────────────────────────────────────────
# GraphicOverlay is referenced by class name in activity_live_object_detection.
-keep class com.example.pettrackerapplication.GraphicOverlay {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Keep line numbers so release crash reports stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
