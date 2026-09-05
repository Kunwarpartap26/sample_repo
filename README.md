# DetAssist — Deterministic Offline Voice Assistant

**Production-grade, 100% offline Android OS-level voice assistant with zero neural network dependencies for intent parsing.**

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Android OS Layer                              │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  VoiceInteractionService (DetAssistVoiceInteractionService) │    │
│  │  - OS registration as default assistant                     │    │
│  │  - Home button long-press handler                           │    │
│  │  - Lock-screen session support                              │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │  VoiceInteractionSession (DetAssistVoiceInteractionSession) │    │
│  │  - SpeechRecognizer for OS-provided STT (Option A)          │    │
│  │  - VoskTranscriber for wake-word audio path (offline)       │    │
│  │  - Routes recognized text to parser → dispatcher            │    │
│  └─────────────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────────────┘
                                │
        ┌───────────────────────┼───────────────────────┐
        ▼                       ▼                       ▼
┌───────────────┐    ┌──────────────────┐    ┌──────────────────┐
│ AudioListener │    │ RegexCommand     │    │ Action           │
│ Service       │    │ Parser           │    │ Dispatcher       │
│               │    │                  │    │                  │
│ • AudioRecord │    │ • Sequence split │    │ • Intent exec    │
│ • Ring buffer │    │ • Named groups   │    │ • Deep links     │
│ • RMS energy  │    │ • Levenshtein    │    │ • Hardware ctrl  │
│ • State FSM   │    │ • Zero alloc     │    │ • Cancellable    │
└───────┬───────┘    └──────────────────┘    └──────────────────┘
        │
        ▼
┌──────────────────┐    ┌──────────────────┐
│ NativeBridge     │    │ AppIndex         │
│ (JNI)            │    │                  │
│                  │    │ • Package cache  │
│ • PCM processing │    │ • Binary search  │
│ • Zero-copy      │    │ • Fuzzy match    │
│ • Wake engine    │    │ • Levenshtein    │
└──────────────────┘    └──────────────────┘
```

---

## Voice Input Paths

### Path A: OS Long-Press (primary)
1. User long-presses home → OS calls `VoiceInteractionSessionService.onNewSession()`
2. `DetAssistVoiceInteractionSession.onShow()` starts `SpeechRecognizer`
3. `SpeechRecognizer.onResults()` provides transcribed text
4. Text → `RegexCommandParser.parse()` → `ActionDispatcher.executeSequence()`

### Path B: Wake-Word (hands-free, requires engine integration)
1. `AudioListenerService` foreground service continuously listens
2. Native keyword spotter detects wake word → state → `COMMAND_CAPTURE`
3. Audio captured to ring buffer with RMS endpointing
4. On silence timeout → `VoskTranscriber.transcribe()` for offline STT
5. Transcribed text → same pipeline as Path A

**NOTE:** Wake-word detection requires integrating a real engine (Porcupine or Pocketsphinx). See Integration section below. Without an engine, only Path A works.

---

## Fixes Applied

| Issue | Fix | File(s) |
|-------|-----|---------|
| 1.1 `onCreateSession` wrong API | Changed to `onNewSession(Bundle)` | `DetAssistVoiceInteractionSessionService.java` |
| 1.2 Fictitious STT APIs | Replaced with `SpeechRecognizer` (Option A) + `VoskTranscriber` for wake path | `DetAssistVoiceInteractionSession.java`, `VoskTranscriber.java` |
| 1.3 XML recognitionService mismatch | Removed `android:recognitionService` attribute | `voice_interaction_service.xml` |
| 1.4 Wake path has no transcription | Added `VoskTranscriber` + documented engine integration | `VoskTranscriber.java`, `NativeBridge.cpp` |
| 2.1 NPE crash on boot without permission | Permission check + `START_NOT_STICKY` + null guards | `AudioListenerService.java`, `BootReceiver.java` |
| 2.2 Exported BootReceiver security hole | Set `android:exported="false"` | `AndroidManifest.xml` |
| 3.1 Missing `<cmath>` include | Added `#include <cmath>`, uses `std::sqrt()` | `NativeBridge.cpp` |
| 4.1 Audio starvation during DECODING | Continuous `read()` in all states, discard buffer | `AudioListenerService.java` |
| 4.2 Race condition on teardown | Shutdown handshake: `quitSafely()` + `join()` before `release()` | `AudioListenerService.java` |
| 5.1 Toggle feedback overstated | "Opening settings — please toggle there" | `ActionDispatcher.java` |
| 5.2 Close app feedback | Explicit: "can't force-close, here's settings" | `ActionDispatcher.java` |
| 5.3 Contact disambiguation | Fuzzy ranking + ambiguous flag | `ActionDispatcher.java` |
| 5.4 Search internet claim | Feedback acknowledges "requires internet" | `ActionDispatcher.java` |
| 6.1 AppIndex visibility split | Single `volatile IndexSnapshot` | `AppIndex.java` |
| 6.2 Duplicate parser instances | Session uses shared parser from `DetAssistApp` | `DetAssistVoiceInteractionSession.java` |
| 7.1 Per-query string allocation | Precomputed `labelNormalized` in `AppEntry` | `AppIndex.java` |
| 7.2 Dual RMS computation | Documented; consolidation deferred to engine integration | `AudioListenerService.java` |
| 7.3 Thread.sleep blocking | `Handler.postDelayed()` + cancellation via sequence ID | `ActionDispatcher.java` |
| 7.4 Silent timer default | Returns `-1` sentinel; dispatcher surfaces error | `RegexCommandParser.java`, `ActionDispatcher.java` |
| 8.1 No test suite | JUnit tests for parser, app index, duration | `app/src/test/` |
| 8.2 No engine scaffolding | Directory structure + .gitignore | `assets/`, `jniLibs/` |

---

## Integration Guide

### Enabling Wake-Word Detection

**Option A: Picovoice Porcupine (recommended)**
1. Register at [console.picovoice.ai](https://console.picovoice.ai/)
2. Create a custom wake word → download `.ppn` model
3. Place model in `app/src/main/assets/porcupine/`
4. Place `libpv_porcupine.so` in `app/src/main/jniLibs/<abi>/`
5. Uncomment `PORCUPINE_INTEGRATION` in `NativeBridge.cpp`
6. Fill in the Porcupine API calls (marked with comments)

**Option B: CMU Pocketsphinx (open source)**
1. Clone and build Pocketsphinx for Android
2. Create JSGF grammar for wake word: `#JSGF V1.0; grammar wakeword; public <wake> = (hey assist | ok assist);`
3. Place acoustic model in assets
4. Uncomment `POCKETSPHINX_INTEGRATION` in `NativeBridge.cpp`

### Enabling Offline STT (Vosk)

1. Download model from [alphacephei.com/vosk/models](https://alphacephei.com/vosk/models)
   - Recommended: `vosk-model-small-en-us-0.15` (~40MB)
2. Extract into `app/src/main/assets/vosk-model/`
3. Uncomment in `build.gradle`:
   ```gradle
   implementation 'com.alphacephei:vosk-android:0.3.47'
   ```
4. Rebuild — `VoskTranscriber` will auto-detect the library at runtime

---

## Build Instructions

```bash
# Build
cd det-assist/
./gradlew assembleDebug

# Install
adb install app/build/outputs/apk/debug/app-debug.apk

# Grant permissions
adb shell pm grant com.detassist android.permission.RECORD_AUDIO
adb shell pm grant com.detassist android.permission.READ_CONTACTS

# Set as default assistant
adb shell settings put secure voice_interaction_service \
    com.detassist/.DetAssistVoiceInteractionService

# Run tests
./gradlew test
```

---

## File Structure

```
det-assist/
├── app/
│   ├── build.gradle
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/detassist/
│       │   │   ├── DetAssistApp.java
│       │   │   ├── MainActivity.java
│       │   │   ├── AudioListenerService.java         # Wake engine
│       │   │   ├── DetAssistVoiceInteractionService.java
│       │   │   ├── DetAssistVoiceInteractionSessionService.java
│       │   │   ├── DetAssistVoiceInteractionSession.java  # STT + pipeline
│       │   │   ├── VoskTranscriber.java                    # Offline STT
│       │   │   ├── RegexCommandParser.java           # Deterministic parser
│       │   │   ├── AppIndex.java                     # Package cache
│       │   │   ├── ActionDispatcher.java             # Intent executor
│       │   │   ├── NativeBridge.java                 # JNI bridge
│       │   │   └── BootReceiver.java
│       │   ├── cpp/
│       │   │   ├── CMakeLists.txt
│       │   │   └── NativeBridge.cpp                  # Keyword spotter
│       │   ├── assets/
│       │   │   ├── porcupine/     (place .ppn model here)
│       │   │   └── vosk-model/    (place Vosk model here)
│       │   ├── jniLibs/           (place .so files here)
│       │   └── res/xml/
│       │       └── voice_interaction_service.xml
│       └── test/
│           └── java/com/detassist/
│               ├── RegexCommandParserTest.java
│               └── AppIndexTest.java
├── build.gradle (project)
├── settings.gradle
├── .gitignore
└── README.md
```

---

## Security & Privacy

| Constraint | Enforcement |
|------------|-------------|
| Zero network access | No `INTERNET` permission |
| Zero cloud calls | No SDK dependencies on cloud services |
| Zero AI for intent parsing | No TF-Lite, ML Kit, or neural networks in parser |
| Zero accessibility | No `AccessibilityService` |
| BootReceiver protected | `exported="false"` — only system can trigger |
| Permission-gated service | `START_NOT_STICKY` when perms missing — no crash loop |

---

## Testing

```bash
# Unit tests (JVM, no device needed)
./gradlew test

# Tests cover:
# - Sequence splitting ("open whatsapp and then call mom" → 2 commands)
# - All command types (open, toggle, send, call, timer, navigate, search)
# - Wake word stripping
# - Levenshtein distance
# - Duration parsing with failure sentinel (returns -1 for "a couple minutes")
# - AppEntry label normalization (precomputed, zero allocation)
# - Fuzzy matching threshold behavior
```
