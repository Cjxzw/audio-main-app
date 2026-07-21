#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "$0")/.." && pwd)
config_file="$repo_root/local.properties"
output_dir="$repo_root/app/src/main/res/raw"
response_file="${TMPDIR:-/tmp}/audio-main-thinking-tts.json"

mkdir -p "$output_dir"

api_key=$(sed -n 's/^LLM_API_KEY=//p' "$config_file" | head -1)
base_url=$(sed -n 's/^LLM_BASE_URL=//p' "$config_file" | head -1)
voice=$(sed -n 's/^TTS_VOICE=//p' "$config_file" | head -1)
voice=${voice:-冰糖}

if [[ -z "$api_key" || -z "$base_url" ]]; then
  echo "LLM_API_KEY or LLM_BASE_URL is missing from local.properties" >&2
  exit 1
fi

generate() {
  local name="$1"
  local text="$2"
  local direction="$3"
  local payload
  payload=$(jq -nc --arg text "$text" --arg direction "$direction" --arg voice "$voice" '{
    model: "mimo-v2.5-tts",
    messages: [
      {role: "user", content: $direction},
      {role: "assistant", content: $text}
    ],
    audio: {format: "wav", voice: $voice}
  }')

  curl -fsS --retry 2 --max-time 30 \
    -H 'Content-Type: application/json' \
    -H "api-key: $api_key" \
    -H "Authorization: Bearer $api_key" \
    --data "$payload" \
    "$base_url/chat/completions" > "$response_file"

  jq -er '.choices[0].message.audio.data' "$response_file" \
    | base64 --decode > "$output_dir/$name.wav"
  printf '%-36s %8s bytes\n' "$name.wav" "$(wc -c < "$output_dir/$name.wav" | tr -d ' ')"
}

generate thinking_wo_xiang_yixia '我想一下。' \
  '像聊天时意识到问题需要思考，简短自然地说“我想一下”。语速略快，语气随和，尾音轻轻收住，总体不要拖沓。只说这句话。'
generate thinking_wo_xiangxiang '我想想。' \
  '像真人边思考边快速回应，轻声自然地说“我想想”。语速略快，带一点思索感，不能像正式播音。只说这句话。'
generate thinking_lvilv '我先捋一捋。' \
  '像面对信息稍多的问题，口语化、干脆地说“我先捋一捋”。语速略快，表现正在整理思路，不要严肃或缓慢。只说这句话。'
generate thinking_xianrang '先让我想一下。' \
  '像朋友间自然对话，意识到需要多想一步后，略快地说“先让我想一下”。语气亲切但不撒娇，不要逐字强调。只说这句话。'
generate thinking_shaodeng '稍等片刻。' \
  '像正在马上处理事情，简洁明确地说“稍等片刻”。语速略快，语气自然可靠，不要像客服播音，不要拖长尾音。只说这句话。'
generate thinking_zuzhi '我组织一下语言。' \
  '像需要把复杂想法说清楚，口语化地快速说“我组织一下语言”。语气轻松自然，不要正式，不要逐字慢读。只说这句话。'
generate thinking_liaojie '我先了解一下情况。' \
  '像准备立即调查一个问题，干脆自然地说“我先了解一下情况”。语速较快，语气专注可靠，不要像客服话术。只说这句话。'
