import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ---------------------------------------------------------------------------
// Release signing
//
// CI provides the keystore through RELEASE_* environment variables (see
// .github/workflows/release.yml). Local builds may instead put the same keys
// in the untracked release.properties file. When none are present the release
// build is unsigned, so debug work keeps working without a keystore.
// ---------------------------------------------------------------------------
val releaseProperties = Properties().apply {
    val file = rootProject.file("release.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun releaseValue(key: String): String? =
    (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(key).orNull?.takeIf { it.isNotBlank() }
        ?: releaseProperties.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseStorePath = releaseValue("RELEASE_KEYSTORE_PATH")
val releaseStoreFile = releaseStorePath?.let { rootProject.file(it) }
val releaseStorePassword = releaseValue("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseValue("RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseValue("RELEASE_KEY_PASSWORD")

// A partial configuration must fail loudly rather than silently producing an
// unsigned bundle that Play rejects.
val releaseSigningValues = listOf(
    releaseStorePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
)
val releaseSigningConfigured = releaseSigningValues.all { it != null }

// Passwords are the signal that signing was actually requested. A local
// release.properties that only pins the path/alias must not break debug
// builds, but exporting one password without the rest is a real mistake.
val releaseSigningAttempted = releaseStorePassword != null || releaseKeyPassword != null
if (releaseSigningAttempted && !releaseSigningConfigured) {
    throw GradleException(
        "Incomplete release signing configuration. Set all of " +
            "RELEASE_KEYSTORE_PATH, RELEASE_KEYSTORE_PASSWORD, RELEASE_KEY_ALIAS " +
            "and RELEASE_KEY_PASSWORD (environment variables or release.properties)."
    )
}
if (releaseSigningConfigured && releaseStoreFile?.exists() != true) {
    throw GradleException(
        "Release keystore not found at ${releaseStoreFile?.absolutePath ?: "(null)"}"
    )
}

val versionNameOverride = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() }
val versionCodeOverride = (project.findProperty("versionCode") as String?)?.toIntOrNull()

android {
    namespace = "com.ai.tinymodels"
    compileSdk {
        version = release(37)
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.ai.tinymodels"
        minSdk = 29
        targetSdk = 37
        versionCode = versionCodeOverride ?: 4
        versionName = versionNameOverride ?: "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the languages we actually translate (English default +
        // Spanish, French, Hindi, Japanese, Tamil). Keeps the APK small and
        // stops Android from offering a language the app has no strings for.
        resourceConfigurations += listOf("en", "es", "fr", "hi", "ja", "ta")

        // Real phones are arm64; shipping x86/x86_64/armeabi-v7a doubles the
        // native-lib size (LiteRT-LM's .so is ~22MB per ABI). Emulators can
        // still run the app via arm64 translation.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // R8 shrinking: strips unused code/resources and compresses the
            // dex — the APK was ~61MB with optimization disabled.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // The app ships every translation in one APK and lets the user switch
    // language in-app (Settings → Appearance → Language). Play must therefore
    // NOT split the bundle per language, or the other translations would be
    // missing at runtime.
    bundle {
        language {
            enableSplit = false
        }
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    tasks.withType<Test> {
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Dnet.bytebuddy.experimental=true")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.google.litertlm.android)

    // MediaPipe LLM Inference — runs .task model bundles (Gemma-family, etc.)
    implementation(libs.google.mediapipe.tasks.genai)

    // GGUF (llama.cpp) runtime — prebuilt AAR from ToolNeuron's gguf_lib.
    // Provides GGMLEngine (chat) + RAGEngine (embeddings) + native .so.
    // tn_security is required by gguf_lib's native lib (TnSecurity class).
    implementation(files("libs/gguf_lib-release.aar"))
    implementation(files("libs/tn_security-release.aar"))

    ksp(libs.androidx.room.compiler)

    // Hilt (DI)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // DataStore (settings)
    implementation(libs.androidx.datastore.preferences)

    // Markdown rendering for chat bubbles
    implementation(libs.markdown.renderer)

    // Google Fonts (downloadable fonts for custom typography)
    implementation(libs.androidx.compose.ui.text.google.fonts)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.core.testing)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}