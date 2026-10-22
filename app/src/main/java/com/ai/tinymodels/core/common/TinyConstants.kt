package com.ai.tinymodels.core.common

/**
 * Shared, non-user-facing constants for the whole app.
 *
 * User-visible copy lives in `res/values/strings*.xml` (see `R.string.*`).
 * Everything here is machine-facing: URLs, HTTP headers, file formats,
 * notification identifiers, WorkManager data keys and persisted preference
 * names. Keeping them in one place stops the same literal from drifting
 * across the data, network and worker layers.
 */
object TinyConstants {

    // ------------------------------------------------------------------
    // Download notifications
    // ------------------------------------------------------------------
    /** Notification channel id for model downloads. */
    const val NOTIFICATION_CHANNEL_DOWNLOADS = "model_downloads"

    /** Base notification id used for the foreground download progress. */
    const val NOTIFICATION_ID_DOWNLOADS = 1001

    /** Intent extra carrying the WorkManager work id for the Cancel action. */
    const val NOTIFICATION_EXTRA_WORK_ID = "com.ai.tinymodels.extra.DOWNLOAD_WORK_ID"

    /** Broadcast action sent to `CancelDownloadReceiver` when Cancel is tapped. */
    const val NOTIFICATION_ACTION_CANCEL_DOWNLOAD = "com.ai.tinymodels.action.CANCEL_DOWNLOAD"

    // ------------------------------------------------------------------
    // Model download worker — WorkManager data keys
    // ------------------------------------------------------------------
    const val WORK_KEY_MODEL_ID = "model_id"
    const val WORK_KEY_MODEL_NAME = "model_name"
    const val WORK_KEY_FILES = "files"
    const val WORK_KEY_AUTHOR = "author"
    const val WORK_KEY_LIBRARY = "library"
    const val WORK_KEY_PIPELINE = "pipeline"
    const val WORK_KEY_TOTAL_BYTES = "total_bytes"
    const val WORK_KEY_DOWNLOADED_BYTES = "downloaded_bytes"
    const val WORK_KEY_PROGRESS = "progress"
    const val WORK_KEY_BYTES_PER_SEC = "bytes_per_sec"
    const val WORK_KEY_ERROR = "error"

    // ------------------------------------------------------------------
    // Model files
    // ------------------------------------------------------------------
    /** Container extension this runtime can load. */
    const val MODEL_FILE_EXTENSION = ".litertlm"

    /** Same extension without the dot (used for suffix checks). */
    const val MODEL_FILE_EXTENSION_BARE = "litertlm"

    /** MIME type used when copying model files through a content resolver. */
    const val MIME_TYPE_OCTET_STREAM = "application/octet-stream"

    /** Directory (under app files dir) that holds downloaded models. */
    const val MODEL_DIRECTORY_NAME = "models"

    /** Remote path prefix for a model file inside a Hugging Face repo. */
    const val MODEL_DOWNLOAD_PATH_PREFIX = "models/"

    // ------------------------------------------------------------------
    // Hugging Face
    // ------------------------------------------------------------------
    /** REST endpoint listing models. */
    const val HUGGING_FACE_API_BASE_URL = "https://huggingface.co/api/models"

    /** Public hub root (model pages and file downloads). */
    const val HUGGING_FACE_HUB_BASE_URL = "https://huggingface.co"

    /** Response header carrying the total size of a repo file. */
    const val HTTP_HEADER_LINKED_SIZE = "x-linked-size"

    // ------------------------------------------------------------------
    // Web search
    // ------------------------------------------------------------------
    const val DUCKDUCKGO_BASE_URL = "https://duckduckgo.com"
    const val DUCKDUCKGO_LITE_URL = "https://lite.duckduckgo.com/lite/"
    const val DUCKDUCKGO_HTML_URL = "https://html.duckduckgo.com/html/"
    const val BING_SEARCH_URL = "https://www.bing.com/search"
    const val WIKIPEDIA_BASE_URL = "https://en.wikipedia.org/wiki/"

    const val HTTP_HEADER_USER_AGENT = "User-Agent"
    const val HTTP_HEADER_ACCEPT = "Accept"
    const val HTTP_HEADER_ACCEPT_LANGUAGE = "Accept-Language"
    const val HTTP_HEADER_REFERER = "Referer"

    const val HTTP_ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
    const val HTTP_ACCEPT_LANGUAGE_EN = "en-US,en;q=0.9"

    /** Browser identity used for search requests (mobile + desktop variants). */
    const val USER_AGENT_MOBILE =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    const val USER_AGENT_DESKTOP =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // ------------------------------------------------------------------
    // Persisted settings (DataStore preference names)
    // ------------------------------------------------------------------
    const val PREFS_THEME_MODE = "theme_mode"
    const val PREFS_DYNAMIC_COLOR = "dynamic_color"
    const val PREFS_FONT_CHOICE = "font_choice"
    const val PREFS_FONT_SCALE = "font_scale"
    const val PREFS_DEFAULT_BACKEND = "default_backend"
    const val PREFS_WEB_SEARCH_ENABLED = "web_search_enabled"
    const val PREFS_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
    const val PREFS_AUTO_LOAD_LAST_MODEL = "auto_load_last_model"
    const val PREFS_KEEP_SCREEN_ON = "keep_screen_on"
    const val PREFS_SHOW_TOKEN_COUNTS = "show_token_counts"
    const val PREFS_LAST_USED_MODEL_ID = "last_used_model_id"
    const val PREFS_HUGGING_FACE_TOKEN = "hugging_face_token"

    // ------------------------------------------------------------------
    // Support links
    // ------------------------------------------------------------------
    /** Repository page linked from Settings → Support. */
    const val SUPPORT_GITHUB_URL = "https://github.com/vengateshk18/TinyModels"

    /** Address the "Report a bug" button writes to. */
    const val SUPPORT_EMAIL_ADDRESS = "mailto:vengatesh00014@gamil.com"

    /** Subject line used for the bug-report email. */
    const val SUPPORT_EMAIL_SUBJECT = "TinyModels bug report"

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------
    /** Clipboard label used when copying a benchmark summary. */
    const val CLIP_LABEL_BENCHMARK = "benchmark"

    /** Mime filter used by the photo picker. */
    const val MIME_TYPE_IMAGE_ANY = "image/*"

    /** Mime filter used by the model import picker (any file). */
    const val MIME_TYPE_ANY = "*/*"
}
