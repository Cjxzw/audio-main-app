#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
config_file="$repo_root/local.properties"
output_file="$repo_root/app/src/main/res/raw/inactivity_sleep.wav"
response_file="${TMPDIR:-/tmp}/audio-main-inactivity-tts.json"

api_key=$(sed -n 's/^LLM_API_KEY=//p' "$config_file" | head -1)
base_url=$(sed -n 's/^LLM_BASE_URL=//p' "$config_file" | head -1)
voice=$(sed -n 's/^TTS_VOICE=//p' "$config_file" | head -1)
voice=${voice:-冰糖}

if [[ -z "$api_key" || -z "$base_url" ]]; then
  echo "LLM_API_KEY or LLM_BASE_URL is missing from local.properties" >&2
  exit 1
fi

style_prompt='请用自然、连贯、略快的中文口语语气朗读。保持前后内容的语气连续，不要每句话重新起调。停顿自然，不要刻意拖长语速。只朗读正文，不要添加说明。'
payload=$(jq -nc --arg voice "$voice" --arg style "$style_prompt" '{
  model: "mimo-v2.5-tts",
  messages: [
    {role: "user", content: $style},
    {role: "assistant", content: "即将休眠。"}
  ],
  audio: {format: "wav", voice: $voice}
}')

curl -fsS --retry 2 --max-time 30 \
  -H 'Content-Type: application/json' \
  -H "api-key: $api_key" \
  -H "Authorization: Bearer $api_key" \
  --data "$payload" \
  "$base_url/chat/completions" > "$response_file"

jq -er '.choices[0].message.audio.data' "$response_file" | base64 --decode > "$output_file"
printf '%s: %s bytes, voice=%s\n' "$output_file" "$(wc -c < "$output_file" | tr -d ' ')" "$voice"
