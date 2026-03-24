package com.livelens.translator.model

enum class TranslationMode {
    CONVERSATION,   // Mode 1: Mic input with speaker diarization
    MEDIA,          // Mode 2: AudioPlaybackCapture
    IMAGE           // Mode 3: Camera/Gallery image
}
