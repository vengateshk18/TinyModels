package com.ai.tinymodels.core.common
import android.content.Context
import android.util.Log
/**
 * Tiny app-wide logger.
 *
 * Rules:
 *  - NO output in release builds: every method is a no-op unless `isDebug()`
 *    returns true.
 *  - In debug builds, delegates to `android.util.Log` exactly like before.
 *  - Tag is always required explicitly. Use the class `TAG` (as before) or
 *    create a short app-wide constant.
 *
 * Usage:
 *   TinyLog.i(TAG, "…")
 *   TinyLog.w(TAG, "…", throwable)
 *   TinyLog.e(TAG, "…")
 */
object TinyLog {
    private const val TAG_GLOBAL = "TinyModels"
    /**
     * Single source-of-truth for "debug build". Prefer compile-time checking
     * via Reflection of `BuildConfig.DEBUG`, but also accept runtime-aware
     * fallbacks so unit tests and release/prerelease tiers resolve correctly.
     */
    private val isDebugCached: Boolean by lazy {
        try {
            val clazz = Class.forName("com.ai.tinymodels.BuildConfig")
            val field = clazz.getDeclaredField("DEBUG")
            field.getBoolean(null)
        } catch (throwable: Throwable) {
            try {
                android.content.Context::class
                false
            } catch (_: Throwable) {
                false
            }
        }
    }
    /** True when running a debug build. */
    fun isDebug(context: Context? = null): Boolean {
        val runtime = context?.let { (it.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0 }
        return runtime ?: isDebugCached
    }
    fun d(tag: String, message: String) {
        if (!isDebugCached) return
        Log.d(tag, message)
    }
    fun i(tag: String, message: String) {
        if (!isDebugCached) return
        Log.i(tag, message)
    }
    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (!isDebugCached) return
        if (throwable == null) Log.w(tag, message) else Log.w(tag, message, throwable)
    }
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (!isDebugCached) return
        if (throwable == null) Log.e(tag, message) else Log.e(tag, message, throwable)
    }
    /** One-call convenience for "Error"/"warning" with a throwable-only body. */
    fun wtf(tag: String, throwable: Throwable) {
        if (!isDebugCached) return
        Log.wtf(tag, throwable)
    }
    /** Render an object as pretty JSON-ish? We don't ship a JSON parser. Just string-format. */
    fun format(format: String, vararg args: Any?): String = String.format(format, *args)
    /** no-op object tag for callers that want a component-level prefix. */
    fun tag(name: String): String = TAG_GLOBAL + "/" + name
}
