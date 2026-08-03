# Development Status

Date: 2026-08-03

## Baseline

Hanwo is a cloud-first Android voice agent. The active path is `VoiceAgentService` plus `agent/runtime` (`AgentLoop` and `MainAgentHarness`); the older `pipeline/` and `report/` directories are not the primary runtime. Local ASR/TTS model assets remain disabled to keep device builds small and repeatable.

## Working Flow

1. The user wakes the agent from the app, Media3 session, or assistant entry.
2. `VoiceAgentService` starts the foreground microphone session and `SimpleVadRecorder` captures one utterance.
3. MiMo ASR transcribes the WAV input.
4. `AgentLoop` sends the conversation and registered JSON-Schema tools to the configured LLM.
5. Native `tool_calls` are executed, persisted with their results, and fed back into the loop until a final answer is available.
6. Streaming text is rendered in the chat immediately; TTS consumes only the spoken portion and does not block the visible reply.
7. The service resumes listening after playback, or releases the microphone while remaining available in sleep mode.

## Current Capabilities

- Persistent conversations, long-term memory, skills, editable workspace text, and user rules.
- Local tools for memory, location, weather, search, files, shell, HTTP, async tasks, and code-graph navigation.
- Tool-round limits, repeated-call protection, invalid tool-call repair, automatic reasoning escalation, and a tool-free final summary.
- Blank final model responses are retried twice inside the same AgentLoop turn. Retry diagnostics are logged without exposing the internal error to the chat UI.
- `<DETAILS>...</DETAILS>` is retained in message history but rendered as a separate, collapsible detail block with a divider. Details from the three most recent user rounds are expanded by default; older details are collapsed.
- Media3-based wake/sleep controls and external Bluetooth/USB/audio-glasses routing with route verification.
- Debug-only ADB bridge and `tools/hanwo-dev` for state, configuration, session, and real-turn diagnostics.
- Graphify snapshot bundled in the APK under `assets/codegraph/`; the current snapshot contains 2,587 nodes and 5,061 links and is built from commit `3838d703`.

## Verification

```bash
graphify update .
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/hanwo-debug-0.1.0.apk
```

The 2026-08-03 Debug APK was installed on the connected NX769J device with `adb install -r`. The APK contained the current Graphify snapshot and report; installation did not clear app data.

## Current Boundaries

- Hub remote tools and task/fact synchronization are still being integrated; the local Main experience remains independent of Hub connectivity.
- Custom chat providers affect the LLM only. ASR, standard TTS, and personalized TTS still require MiMo credentials.
- Android 14+ vendors can reject background microphone promotion from a media command; the service keeps sleep state and asks the user to activate it from the notification or app.
- Classic Bluetooth HFP/SCO can reinterpret a multifunction button as call hang-up while the microphone route is active; this requires a vendor BLE/HID control path to solve completely.
- End-to-end latency and long-context growth remain under observation through structured runtime logs.

## Repository Scope

This repository contains source code, Gradle files, generated code-graph assets, and project documentation. It must not contain credentials, `local.properties`, build outputs, old local model assets, AAR binaries, private logs, or screenshots.
