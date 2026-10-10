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