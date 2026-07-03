# Audio Main App

Android voice-agent main app for hands-free conversation, headset/media-button wake-sleep control, and cloud ASR/LLM/TTS.

## Current Status

- Minimal voice loop is running on device:
  - `AudioRecord` captures speech through the system default input route.
  - MiMo cloud ASR transcribes recorded WAV.
  - MiMo-compatible chat completion streams LLM text.
  - Sentences are queued for TTS playback.
  - Cloud TTS currently defaults to non-streaming for stability.
- Streaming TTS pipeline is preserved behind `ENABLE_STREAMING_TTS` in `VoiceAgentService`.
- Foreground service can stay alive in background/screen-off mode.
- MediaSession is registered for play/pause style wake and sleep.
- Local Sherpa ASR/TTS assets are disabled for the current APK to keep package size small.

## Requirements

- Android Studio or Gradle/JDK 17
- Android SDK 34
- USB or Wi-Fi ADB device for testing
- MiMo/OpenAI-compatible API key

## Configuration

Create `.env` or set the same values in `local.properties`.

```properties
LLM_API_KEY=your_key_here
LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1
LLM_MODEL=mimo-v2.5
```

Do not commit `.env` or `local.properties`.

## Build

```powershell
.\gradlew.bat :app:assembleDebug
```

Debug APK output:

```text
app/build/outputs/apk/debug/app-debug-ort1171.apk
```

Install to connected device:

```powershell
adb install -r app\build\outputs\apk\debug\app-debug-ort1171.apk
```

## Architecture Notes

- `VoiceAgentService` owns the current minimal loop and wake/sleep lifecycle.
- `SimpleVadRecorder` performs lightweight RMS-based endpointing and writes WAV for ASR.
- `CloudSpeechClient` calls MiMo-compatible ASR, chat, and TTS endpoints.
- `AudioRouteManager` logs and respects Android system default audio routing.
- Legacy pipeline processors remain as placeholders while the app uses the cloud loop.

## Next Work

- Add structured latency tracing for ASR, LLM first token, first sentence, TTS response, playback start, and playback end.
- Add persistent session/conversation management.
- Add first real tool-call loop, such as weather or search.
- Re-enable and verify streaming TTS after confirming the exact MiMo streaming payload and first-audio timing.
