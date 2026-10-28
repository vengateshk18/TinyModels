// Native bridge stub for libgguf_bridge.so.
// Replace the bodies with real llama.cpp calls once the library is linked.
// Signatures must match LlamaCppBridge.kt.

#include <jni.h>
#include <string>

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ai_tinymodels_core_inference_runtime_LlamaCppBridge_nativeLoadModel(
    JNIEnv* env, jclass, jstring path, jint nCtx) {
    // TODO: llama_model_load_from_file + llama_init_from_model
    return 0L; // 0 => failure (Kotlin side treats 0 as load failure)
}

JNIEXPORT jint JNICALL
Java_com_ai_tinymodels_core_inference_runtime_LlamaCppBridge_nativeContextSize(
    JNIEnv* env, jclass, jlong handle) {
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_ai_tinymodels_core_inference_runtime_LlamaCppBridge_nativeGenerate(
    JNIEnv* env, jclass, jlong handle, jstring prompt, jint maxTokens, jobject onToken) {
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_ai_tinymodels_core_inference_runtime_LlamaCppBridge_nativeClose(
    JNIEnv* env, jclass, jlong handle) {
    // TODO: llama_free
}

}
