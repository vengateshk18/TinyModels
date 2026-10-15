# --- LiteRT-LM (JNI) ---
# The native runtime calls back into Java via these classes; stripping or
# renaming them breaks the JNI bindings (UnsatisfiedLinkError / SIGSEGV).
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }

# --- Hilt / Dagger (generated code references originals by name) ---
-keep class dagger.hilt.** { *; }
-keep class javax.annotation.** { *; }
-keepclasseswithmembernames class * { @javax.inject.* <fields>; }
-keepclasseswithmembernames class * { @javax.inject.* <init>(...); }

# --- Coroutines ---
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Keep enum values/fields accessed reflectively ---
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# --- MediaPipe LLM Inference (.task runtime) ---
# AutoValue is a compile-time annotation processor upstream; the annotation
# classes are referenced by MediaPipe's generated builders but are not on the
# runtime classpath. R8 only needs to stop warning about them.
-dontwarn com.google.auto.value.**
# JNI: native code resolves these Java classes/methods by name.
-keep class com.google.mediapipe.** { *; }
-keep interface com.google.mediapipe.** { *; }
# The vision/audio entry points reference MediaPipe framework image classes
# (tasks-core) that this app never calls — the text-LLM path does not need
# them on the classpath.
-dontwarn com.google.mediapipe.framework.**
