# Development Status

Date: 2026-07-03

## Baseline

The app is currently a small cloud-first Android voice-agent baseline. The previous local ASR/TTS model package path is intentionally disabled to reduce APK size and simplify installation during device testing.

## Working Flow

1. User wakes the agent from the app, media session, or assistant entry.
2. Foreground service starts listening.
3. VAD-style recorder captures one utterance and stops listening.
4. WAV audio is sent to MiMo ASR.
5. ASR text is sent to the LLM with short conversation history.
6. LLM streaming text is cut into sentence chunks.
7. TTS playback runs before listening resumes.
8. Sleep mode stops microphone capture while keeping the service available.

## Current Audio Decisions

- Input and output follow Android system default routing. This is required for phone speaker, Bluetooth headset, and audio glasses scenarios.
- `MediaSessionCompat` handles media play/pause controls for wake/sleep behavior.
- Default TTS path is non-streaming cloud TTS for lower observed latency and stability.
- Streaming TTS code remains in place behind `ENABLE_STREAMING_TTS`.

## Known Issues

- End-to-end latency is variable. Structured per-stage timing is needed before more tuning.
- Agent memory is in-process only and limited to recent messages.
- No persisted sessions yet.
- Tool calling is not yet wired into the active cloud loop.
- Streaming TTS behavior needs a dedicated verification pass against MiMo's latest API behavior.

## Repository Scope

This repository should contain source code, Gradle files, and project documentation only.

Excluded from version control:

- `.env`
- `local.properties`
- build outputs
- old local model assets
- AAR binaries and extracted native libraries
- local logs and screenshots
