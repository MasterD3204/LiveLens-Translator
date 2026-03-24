<div align="center">

<img src="app/src/main/res/drawable/ic_launcher_round.xml" width="96" height="96" alt="LiveLens Logo"/>

# LiveLens Translator

**Dịch tiếng Anh → Tiếng Việt 100% on-device, không cần internet**

[![Android](https://img.shields.io/badge/Android-10%2B-brightgreen?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1-blue?logo=kotlin)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-2025-blueviolet?logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

</div>

---

## 📖 Giới thiệu

LiveLens Translator là ứng dụng Android dịch thuật **hoàn toàn offline**, chạy trực tiếp trên thiết bị mà không gửi bất kỳ dữ liệu nào lên server. Mọi xử lý — nhận diện giọng nói, dịch thuật, chuyển văn bản thành giọng nói — đều diễn ra ngay trên điện thoại của bạn.

### ✨ Tính năng chính

| Chế độ | Mô tả |
|--------|-------|
| 🎤 **Conversation** | Dịch trực tiếp từ micro, phân biệt Speaker A/B |
| 📱 **Media** | Dịch phụ đề từ YouTube, VLC, podcast... |
| 📷 **Image** | Dịch văn bản trong ảnh chụp hoặc từ thư viện |

### 🔑 Điểm nổi bật

- 🔒 **Privacy-first** — không gửi dữ liệu ra ngoài, hoạt động hoàn toàn offline
- ⚡ **Real-time streaming** — hiển thị token dịch ngay khi Gemma sinh ra
- 🪟 **Floating overlay** — hiển thị bản dịch đè lên mọi ứng dụng khác
- 🗣️ **Speaker diarization** — phân biệt màu sắc theo người nói (A/B)
- 📝 **Lịch sử dịch** — lưu tối đa 500 câu, tìm theo ngày
- 🔊 **TTS tiếng Việt** — đọc bản dịch bằng giọng Piper Vietnamese

---

## 🏗️ Kiến trúc

```
Audio / Camera
      │
      ▼
Silero VAD (sherpa-onnx)          ← Phát hiện giọng nói
      │
      ▼
Zipformer STT (sherpa-onnx)       ← Chuyển giọng nói → văn bản
      │
      ▼
Gemma Translate (MediaPipe)       ← Dịch EN → VI (streaming)
      │
      ▼
Floating Overlay / Main UI        ← Hiển thị kết quả
```

### Tech Stack

| Layer | Công nghệ |
|-------|-----------|
| Language | Kotlin 2.1 |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt |
| DB | Room + DataStore |
| Async | Coroutines + Flow |
| STT / VAD / TTS | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) |
| Translation LLM | [MediaPipe LLM Inference API](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) |
| Camera | CameraX |

---

## 📁 Cấu trúc dự án

```
app/
├── data/
│   ├── db/                  # Room: TranslationEntity, Dao, AppDatabase
│   └── repository/          # TranslationRepository, SettingsRepository
├── di/                      # Hilt modules
├── model/
│   ├── ModelLoader.kt       # Quản lý file model (tìm trong Download, internal)
│   ├── SherpaOnnxManager.kt # VAD + STT + TTS + Diarization
│   └── GemmaTranslateManager.kt  # MediaPipe LLM (text + image)
├── service/
│   ├── AudioCaptureService.kt         # Foreground service (mic + media capture)
│   ├── TranslationManager.kt          # Queue + drop logic + streaming
│   ├── TranslatorAccessibilityService.kt  # Overlay lifecycle
│   └── TtsPlaybackManager.kt          # TTS playback
├── ui/
│   ├── overlay/             # FloatingBubble, TranslationOverlayCard, Controller
│   ├── home/                # HomeScreen + ViewModel
│   ├── image/               # ImageTranslationScreen + ViewModel
│   ├── history/             # HistoryScreen + ViewModel
│   ├── settings/            # SettingsScreen + ViewModel
│   ├── modelsetup/          # ModelSetupScreen + ViewModel (import Gemma)
│   ├── navigation/          # NavHost
│   ├── theme/               # LiveLensTheme, Typography
│   └── components/          # PermissionComponents
└── util/                    # AudioUtils, BitmapUtils, PermissionHelper
```

---

## 🤖 Cài đặt AI Models

> ⚠️ **Model files không được bao gồm trong repo** do kích thước lớn.
> Tải về và cài đặt theo hướng dẫn bên dưới.

### Tổng quan

| Model | Chức năng | Kích thước | Bắt buộc? |
|-------|-----------|------------|-----------|
| Silero VAD | Phát hiện giọng nói | ~2 MB | ✅ |
| Zipformer STT | Speech-to-Text tiếng Anh | ~90 MB | ✅ |
| Gemma Translate | Dịch EN→VI (LLM) | ~1.5 GB | ✅ |
| Piper TTS | Text-to-Speech tiếng Việt | ~50 MB | ❌ |
| Speaker Diarization | Phân biệt người nói | ~20 MB | ❌ |

---

### 1. 🤖 Gemma Translate (.task) — QUAN TRỌNG NHẤT

Tải model từ một trong các nguồn sau:

**Option A — Kaggle (khuyên dùng):**
```
https://www.kaggle.com/models/google/gemma/frameworks/tfLite
→ Chọn: gemma-2b-it hoặc gemma-3-1b-it
→ Format: MediaPipe (.task)
```

**Option B — AI Edge Gallery:**
```
https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android
→ Download model dạng .task
```

**Cách cài vào app:**
1. Tải file `.task` về → tự động lưu vào thư mục **Downloads** của thiết bị
2. Mở app → **Settings → AI Model Setup**
3. App tự quét và liệt kê file trong danh sách
4. Nhấn **Import** → chờ copy hoàn tất (có thanh tiến độ)

---

### 2. 🎙️ STT + VAD (sherpa-onnx)

Tải từ [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models):

```bash
# STT — Zipformer streaming
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/\
sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2
tar -xf sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2

# VAD — Silero
wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/\
silero_vad.onnx
```

**Đổi tên và đặt vào:**
```
/sdcard/Android/data/com.livelens.translator/files/models/
├── stt/
│   ├── encoder.onnx     ← encoder-epoch-99-avg-1-chunk-16-left-128.onnx
│   ├── decoder.onnx     ← decoder-epoch-99-avg-1.onnx
│   ├── joiner.onnx      ← joiner-epoch-99-avg-1-chunk-16-left-128.onnx
│   └── tokens.txt
└── vad/
    └── silero_vad.onnx
```

Sau đó vào app → **Model Setup** → nhấn **Import STT / VAD / TTS**.

---

### 3. 🔊 TTS — Piper Vietnamese (tùy chọn)

Tải từ [Hugging Face — rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/main/vi/vi_VN/vivos/medium):

```
vi_VN-vivos-medium.onnx      → đổi tên → vi-voice.onnx
vi_VN-vivos-medium.onnx.json → đổi tên → vi-voice.onnx.json
```

Đặt vào: `models/tts/`

---

### 4. 👥 Speaker Diarization (tùy chọn)

Tải từ [sherpa-onnx speaker-segmentation-models](https://github.com/k2-fsa/sherpa-onnx/releases/tag/speaker-segmentation-models):

```
models/diarization/
├── seg-model.onnx      ← sherpa-onnx-pyannote-segmentation-3-0/*.onnx
└── wespeaker.onnx      ← wespeaker-voxceleb-resnet34-LM.onnx
```

---

## 🚀 Build & Chạy

### Yêu cầu

- Android Studio Koala (2024.1.1) trở lên
- JDK 17
- Android SDK 35
- Thiết bị Android 10+ (API 29+), khuyên dùng chip Snapdragon 8 Elite

### Build

```bash
git clone https://github.com/MasterD3204/LiveLens-Translator.git
cd LiveLens-Translator

# Bước 1 — Tải sherpa-onnx AAR và đặt vào app/libs/
# Download tại: https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.12.32
# File cần: sherpa-onnx-1.12.32.aar → đặt vào app/libs/

# Bước 2 — Build
./gradlew assembleDebug

# Cài lên thiết bị
./gradlew installDebug
```

### Lần đầu chạy

1. Cấp quyền **Microphone** và **Camera** khi được hỏi
2. Vào **Settings → AI Model Setup** để cài model
3. Vào **Settings → Display over other apps** → bật quyền overlay
4. Kích hoạt **Accessibility Service** trong Android Settings
5. Nhấn **Start Service** trên màn hình chính

---

## 📱 Permissions cần thiết

| Permission | Lý do |
|-----------|-------|
| `RECORD_AUDIO` | Mode 1: dịch từ micro |
| `SYSTEM_ALERT_WINDOW` | Hiển thị floating overlay |
| `MEDIA_PROJECTION` | Mode 2: dịch âm thanh từ app khác |
| `CAMERA` | Mode 3: chụp ảnh để dịch |
| `READ_MEDIA_IMAGES` | Mode 3: chọn ảnh từ thư viện |
| `POST_NOTIFICATIONS` | Foreground service notification |
| `FOREGROUND_SERVICE_MICROPHONE` | Audio capture service |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Media capture service |

---

## 🔧 Cấu hình nâng cao

### Sentence Drop Logic

Khi queue dịch bị đầy (người nói quá nhanh), app tự động xóa backlog:

```kotlin
// TranslationManager.kt
if (_sentences.value.size >= dropThreshold) {
    _sentences.value = emptyList()  // clear backlog
}
```

Điều chỉnh `dropThreshold` trong **Settings → Sentence Drop Threshold** (1/2/3).

### TTS Speed

Điều chỉnh tốc độ đọc trong **Settings → TTS Speed** (0.75× / 1.0× / 1.25×).

---

## 📋 Roadmap

- [ ] Hỗ trợ thêm ngôn ngữ (JP, KR, ZH)
- [ ] Fine-tune Gemma riêng cho EN→VI
- [ ] Widget trên màn hình chờ
- [ ] Export lịch sử ra CSV
- [ ] Sync lịch sử qua Google Drive (optional, opt-in)

---

## 📄 License

```
MIT License

Copyright (c) 2026 MasterD3204

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

<div align="center">
Made with ❤️ in Vietnam · Chạy offline · Bảo vệ quyền riêng tư
</div>
