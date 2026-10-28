# Native GGUF (llama.cpp) bridge — build instructions

TinyModels' `GgufRuntime` calls into `libgguf_bridge.so` via `LlamaCppBridge`.
That library is **not** bundled; build it from llama.cpp with the Android NDK.

## Prerequisites
- Android NDK (r26+), CMake 3.22+
- Clone llama.cpp (or the `Siddhesh2377/llama.cpp-android` fork) into this folder's
  sibling, e.g. `app/src/main/cpp/llama.cpp`.

## Build
```bash
cd app/src/main/cpp
mkdir -p build && cd build
cmake .. \
  -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-26 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build . -j$(nproc)
cp libgguf_bridge.so ../../jniLibs/arm64-v8a/
```

## Wiring into Gradle (when NDK is available)
Add to app/build.gradle.kts:
```kotlin
android {
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }
    ndk { abiFilters += listOf("arm64-v8a") }
}
```

## JNI contract (must match LlamaCppBridge.kt)
- `Java_com_ai_tinymodels_core_inference_runtime_LlamaCppBridge_nativeLoadModel(JNIEnv*, jclass, jstring path, jint nCtx) -> jlong`
- `nativeContextSize(JNIEnv*, jclass, jlong handle) -> jint`
- `nativeGenerate(JNIEnv*, jclass, jlong handle, jstring prompt, jint maxTokens, jobject onToken) -> jstring`
- `nativeClose(JNIEnv*, jclass, jlong handle) -> void`

Until this library is present, `GgufRuntime` reads GGUF metadata (max tokens etc.)
in pure Kotlin and fails fast with a clear error, so the app never crashes.