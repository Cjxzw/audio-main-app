#!/usr/bin/env bash
set -euo pipefail

SERIAL="${1:-${ADB_SERIAL:-172.20.10.3:40165}}"
LINES="${LINES:-160}"
PKG="com.agent.voiceassistant"

echo "== device =="
adb -s "$SERIAL" get-state

echo
echo "== app file diagnostic tail =="
adb -s "$SERIAL" shell run-as "$PKG" cat files/voice-agent.log \
  | grep 'VA_DIAG' \
  | tail -n "$LINES" \
  || true

echo
echo "== compact app runtime tail =="
adb -s "$SERIAL" shell run-as "$PKG" cat files/voice-agent.log \
  | grep -E 'VA_DIAG|LogBus: Agent|AudioRoute: AudioRecord|AudioRoute: setCommunicationDevice|CloudRecorder:' \
  | tail -n "$LINES" \
  || true

echo
echo "== compact system media tail =="
adb -s "$SERIAL" logcat -d \
  | grep -Ei 'MediaButton|MediaSession|HeadsetMediaButton|Avrcp|BluetoothMedia|KeyEvent' \
  | tail -n 80 \
  || true

echo
echo "== media session compact =="
adb -s "$SERIAL" shell dumpsys media_session \
  | grep -E 'Global priority session|Last MediaButtonReceiver|Media button session|VoiceAgentSession|mediaButtonReceiver=|active=|state=PlaybackState' \
  || true

echo
echo "== running service compact =="
adb -s "$SERIAL" shell dumpsys activity services "$PKG" \
  | grep -E 'ServiceRecord|intent=|startRequested|isForeground|foregroundId|processName|app=' \
  || true

echo
echo "== telecom compact =="
adb -s "$SERIAL" shell dumpsys telecom \
  | grep -E 'mCalls:|com.agent.voiceassistant|SelfMgd Call|state=(ACTIVE|DIALING|DISCONNECTED)|SET_(ACTIVE|DISCONNECTED)|REQUEST_DISCONNECT' \
  | tail -n 80 \
  || true
