# TinyModels 📱🤖

**Run large language models entirely on your Android phone — fully offline, fully private.**

TinyModels lets you browse, download, and chat with small open LLMs directly on your
device. No server, no account, no data leaving your phone. Powered by Google's
**LiteRT-LM** on-device runtime. Open source under the MIT License — contributions welcome!

---

## ✨ What it does

| Feature | Details |
|---|---|
| 🔍 **Model catalog** | Browse the Hugging Face catalog — search, filter, and inspect models with per-file details (size, quantization, context window). |
| ⬇️ **Downloads** | Resumable downloads via WorkManager with progress notifications, cancel, and per-file tracking. Capacity checks warn before a model that won't fit. |
| 🔄 **Import / Export** | Import `.litertlm` files from storage (tagged *Imported* in your library) or export downloaded files to share between devices — all through the system file picker, no storage permission needed. |
| 💬 **On-device chat** | Streaming token-by-token replies with markdown bubbles. Persisted conversations, regenerate/edit, per-chat sampler settings. Type your next prompt mid-stream; stop generation anytime. |
| 🖼️ **Multimodal** | Attach photos on vision-capable models (e.g. Gemma 3) — processed on-device. |
| 🌐 **Web search (opt-in)** | Per-chat tool toggle: the model can search the web when it needs current info. Off by default. |
| 🧠 **Context management** | True context-window detection, live usage gauge, auto-compaction at 90% plus manual Compact. |
| ⚡ **Benchmarks** | Measure load time, TTFT, and tokens/sec of the loaded model. |
| 📊 **Home dashboard** | Usage stats, device AI-capability insights, storage overview. |
| 🎨 **Settings** | Theme, fonts, backend, Hugging Face token, Wi-Fi-only downloads, support links. |

## 🔒 Privacy

Chats, photos, and models stay on your device. No accounts, no analytics SDKs. Full
details: [Privacy Policy](https://vengateshk18.github.io/TinyModels/privacy.html) ·
[Security](https://vengateshk18.github.io/TinyModels/security.html).

## 🚀 Getting started

**Requirements:** Android Studio (recent), JDK 17, an arm64 device or emulator with ≥ 4 GB RAM.

```bash
git clone https://github.com/vengateshk18/TinyModels.git
cd TinyModels
./gradlew :app:assembleDebug      # build
./gradlew :app:testDebugUnitTest  # unit tests
```

Install `app/build/outputs/apk/debug/*.apk`, open the app, download a Small-tier model
from Home, and say hello. Large models (3B+) need 8 GB+ RAM devices.

## 🤝 Contributing

**Yes — this app is open for contributions!** Bug fixes, new features, docs, and tests
are all welcome. Please read [CONTRIBUTING.md](CONTRIBUTING.md) first (setup, branch
naming, PR checklist), and note our [Code of Conduct](CODE_OF_CONDUCT.md).

Quick links: [open issues](../../issues) · [bug report](../../issues/new?template=bug_report.md) ·
[feature request](../../issues/new?template=feature_request.md)

## 🛠️ Tech stack

Kotlin · Jetpack Compose (Material 3) · MVI (immutable `UiState` per screen) · Hilt ·
Room · DataStore · WorkManager · Coroutines/Flow · LiteRT-LM (CPU/GPU/Auto)

## 📄 License

MIT — see [LICENSE](LICENSE). If you find TinyModels useful, a ⭐ on GitHub is appreciated.
