# Audio Main App 接入枢卫 Hub 开发任务书

更新时间：2026-07-05

本文面向负责 Android 开发的 coding agent。目标是在现有 `audio-main-app` MVP 基础上，补齐与枢卫 Hub 的连接能力和 main agent 的基础工具调用能力。

## 一、项目背景

`audio-main-app` 是一个 Android 端语音优先的 main agent 原型。它的定位不是普通聊天 App，而是用户随身的语音秘书：

- 通过手机、蓝牙耳机或智能音频眼镜与用户语音交互。
- 使用 Android `ForegroundService` 和 `MediaSessionCompat` 模拟音乐 App，实现后台常驻、息屏运行、媒体按键唤醒/休眠。
- 当前已跑通 MVP 闭环：录音、云端 ASR、云端 LLM、云端 TTS、播报、后台运行。
- 当前为了先跑通闭环，采用 `ASR -> 文本 LLM -> TTS` 路线；未来可能升级为音频直入多模态 LLM，但本任务不做该升级。

枢卫 Hub 是中央中枢，负责执行 agent 管理、任务事实维护、安全审计和协议校验。Android main 不直接调用执行 agent，必须通过 Hub 派发任务。

## 二、核心边界

必须保持以下职责边界：

- Android main 负责用户入口、语音交互、会话、本地状态机、本地工具、本地任务播报状态。
- Hub 负责执行 agent 注册、在线状态、任务事实、任务派发、安全网关、聊天归档。
- Hub 不理解用户自然语言，不决定何时向用户播报任务结果。
- Android main 不绕过 Hub 调用执行 agent。
- Android main 可以离线独立聊天；未连接 Hub 时只禁用派活、任务查询、会诊等 Hub 工具。

## 三、当前代码现状

主要文件：

- `app/src/main/java/com/agent/voiceassistant/service/VoiceAgentService.kt`
  - 当前主运行循环。
  - 负责唤醒、休眠、录音、ASR、LLM、TTS、播放。

- `app/src/main/java/com/agent/voiceassistant/cloud/CloudSpeechClient.kt`
  - MiMo ASR、LLM、TTS HTTP/SSE 调用。

- `app/src/main/java/com/agent/voiceassistant/cloud/SimpleVadRecorder.kt`
  - 轻量 VAD 和 WAV 生成。

- `app/src/main/java/com/agent/voiceassistant/service/TaskDispatcher.kt`
  - 早期任务派发占位，目前只模拟完成。

- `app/src/main/java/com/agent/voiceassistant/report/PendingResultReporter.kt`
  - 早期主动汇报模型，目前未接入当前主闭环。

- `app/src/main/java/com/agent/voiceassistant/agent/AgentTools.kt`
  - 早期 LangChain4j 工具封装，不是当前主链路。

当前真实主链路在 `VoiceAgentService.processTurn()` 中：

```text
recordNextUtterance()
  -> CloudSpeechClient.transcribe()
  -> CloudSpeechClient.streamChat()
  -> StreamingSentenceBuffer
  -> CloudSpeechClient.synthesizeSpeech()
```

开发时要优先改造这条真实链路，不要只改早期占位类。

## 四、本阶段目标

本阶段目标是让 Android main 成为真正可接入枢卫 Hub 的独立 main agent。

必须实现：

1. Hub 连接配置。
2. Main 向 Hub 注册。
3. 从 Hub 获取 facts snapshot。
4. 接收 Hub facts delta 或轮询同步任务表、agent 表。
5. Main 本地维护 agent 列表、任务表、待汇报列表。
6. Main LLM 能通过结构化工具调用请求 Hub 派发任务。
7. Main 能请求 Hub 获取任务详情。
8. Main 能把聊天记录、ASR 转写、回复文本和语音元数据同步回 Hub。
9. 执行 agent 任务完成后，Main 能在合适时机主动汇报摘要。
10. 用户要求“详细说说/展开原文”时，Main 能调用 Hub 获取任务详情并播报或显示。

本阶段不做：

- 音频直入 LLM。
- 本地大模型 ASR/TTS。
- 完整复杂状态机。
- 安全网关实时阻断。
- 自研搜索/抓取工具。
- 让 Android main 直接管理执行 agent。

## 五、Hub 协议

协议基于 HTTP + 可选 WebSocket。第一版可先用 HTTP 轮询，WebSocket 后续增强。

### 5.1 鉴权

请求头二选一：

```http
X-Hub-Channel-Token: <HUB_CHANNEL_TOKEN>
```

或：

```http
Authorization: Bearer <HUB_CHANNEL_TOKEN>
```

Android 配置中需要支持：

- `HUB_BASE_URL`
- `HUB_CHANNEL_TOKEN`
- `MAIN_CLIENT_ID`
- `MAIN_DEVICE_ID`

不得把真实 token 提交到仓库。

### 5.2 注册 Main

接口：

```http
POST /api/main/register
```

请求：

```json
{
  "type": "main.register",
  "clientId": "main:android-phone",
  "name": "Android Main",
  "deviceId": "phone-01",
  "platform": "android",
  "protocolVersion": 1,
  "capabilities": {
    "voiceInput": true,
    "tts": true,
    "localStore": true,
    "audioArchive": true,
    "hubActions": true
  },
  "lastEventId": ""
}
```

响应中会包含：

- `clientId`
- `factsVersion`
- `snapshot`
- `events`

注册成功后，App 应在 UI 日志中显示 Hub 已连接。

### 5.3 获取 facts snapshot

接口：

```http
GET /api/main/facts/snapshot
```

用途：

- 获取当前执行 agent 列表。
- 获取最近任务列表。
- 获取任务结果索引。
- 获取安全/系统事件。

Android main 应将 snapshot 落入本地内存缓存，供 LLM 上下文和本地工具使用。

### 5.4 提交 Hub Action

接口：

```http
POST /api/main/action/submit
```

通用结构：

```json
{
  "requestId": "req_uuid",
  "clientId": "main:android-phone",
  "turnId": "turn_uuid",
  "conversationId": "default",
  "actionType": "dispatch_task",
  "factsVersion": 1,
  "idempotencyKey": "dispatch_task:turn_uuid:1",
  "payload": {}
}
```

必须支持的 action：

- `dispatch_task`
- `start_consult`
- `request_task_detail`
- `cancel_task`
- `sync_chat_archive`

#### dispatch_task

用于派发后台任务。

```json
{
  "requestId": "req_uuid",
  "clientId": "main:android-phone",
  "turnId": "turn_uuid",
  "conversationId": "default",
  "actionType": "dispatch_task",
  "factsVersion": 1,
  "idempotencyKey": "dispatch_task:turn_uuid:1",
  "payload": {
    "targetAgentId": "edge:mimocode-worker",
    "task": {
      "title": "查询天气",
      "summary": "查询用户所在地明天天气，并返回一句话摘要。",
      "urgency": "normal",
      "instructions": "查询用户所在地明天天气，返回简短结论。",
      "expectedOutput": "一句话天气摘要。"
    }
  }
}
```

Hub 会校验以下字段：

- `requestId`
- `actionType`
- `targetAgentId`
- `task.title`
- `task.summary`
- `task.urgency`
- `task.instructions`
- `task.expectedOutput`

缺字段时 Hub 会拒绝。Android main 应将错误结果送回 LLM，让 LLM 修正工具调用或向用户解释。

#### request_task_detail

用于用户要求详细报告或原文时获取任务详情。

```json
{
  "requestId": "req_uuid",
  "clientId": "main:android-phone",
  "turnId": "turn_uuid",
  "conversationId": "default",
  "actionType": "request_task_detail",
  "factsVersion": 1,
  "idempotencyKey": "request_task_detail:turn_uuid:1",
  "payload": {
    "taskId": "task_xxx"
  }
}
```

响应字段通常包含：

- `taskId`
- `summary`
- `details`
- `ttsAllowed`
- `securityStatus`

当前安全网关处于影子模式时，可默认 `ttsAllowed=true`。但代码结构上必须保留该字段：如果后续 `ttsAllowed=false`，Android main 不应播报原文，只提示用户在文本记录中查看。

### 5.5 同步聊天归档

接口：

```http
POST /api/main/transcript
```

每轮对话结束后上传：

```json
{
  "clientId": "main:android-phone",
  "conversationId": "default",
  "turnId": "turn_uuid",
  "items": [
    {
      "role": "user",
      "text": "查一下明天天气",
      "asrText": "查一下明天天气",
      "audioRef": "local://turn_uuid.wav",
      "metadata": {
        "durationMs": 3200,
        "audioQuality": "clear",
        "sourceDevice": "bluetooth_headset",
        "route": "asr_first"
      }
    },
    {
      "role": "main",
      "text": "收到，我安排后台查一下。"
    }
  ]
}
```

注意：

- `transcript.append` 是归档和审计，不是业务输入。
- Hub 不应根据 transcript 文本触发任务。
- Android main 不要依赖 Hub 从 transcript 猜测用户意图。

## 六、Android 侧新增模块建议

建议新增以下包：

```text
app/src/main/java/com/agent/voiceassistant/hub/
├── HubConfig.kt
├── HubClient.kt
├── HubModels.kt
├── HubFactsStore.kt
├── HubSyncManager.kt
└── HubActionExecutor.kt
```

### HubConfig

职责：

- 从 `.env` / `local.properties` / Android 本地设置读取 Hub 配置。
- 字段：
  - `hubBaseUrl`
  - `hubChannelToken`
  - `clientId`
  - `deviceId`
  - `syncIntervalSeconds`

第一版可以继续走 BuildConfig 注入，后续再做 App 内配置页。

### HubClient

职责：

- 封装所有 HTTP 请求。
- 自动加鉴权头。
- 统一解析错误。
- 设置合理超时。

必须实现：

- `register()`
- `getFactsSnapshot()`
- `submitAction(action)`
- `appendTranscript(items)`

可选：

- `connectWebSocket()`
- `pollEvents(lastEventId)`

### HubFactsStore

职责：

- 在 App 内保存 Hub 同步来的事实数据。
- 第一版可用内存数据结构。
- 后续升级 Room/SQLite。

至少维护：

- `factsVersion`
- `agents`
- `tasks`
- `taskResultIndex`
- `pendingReports`
- `lastEventId`

### HubSyncManager

职责：

- App 启动或服务唤醒时注册 Hub。
- 定期同步 facts snapshot。
- 检测新增 completed/failed 任务，加入 Main 本地待汇报队列。

第一版建议：

- 启动时同步一次。
- 每 10-30 秒同步一次。
- App 休眠时仍可低频同步，便于主动汇报。

### HubActionExecutor

职责：

- 执行 LLM 产生的结构化工具调用。
- 做本地字段预校验。
- 调 Hub action。
- 将 Hub 返回结果整理成文本或结构化结果送回 LLM。

## 七、LLM 工具调用方案

当前主链路 `CloudSpeechClient.streamChat()` 只处理普通文本 delta，尚未处理工具调用。

第一版可以使用“结构化 JSON 块”方式，不必立即接入 OpenAI function calling SDK。

### 7.1 系统提示词要求

更新 `DEFAULT_SYSTEM_PROMPT`，明确：

- 你是用户的 Android 语音秘书 main agent。
- 优先简短回答，适合语音播报。
- 简单聊天直接回答。
- 需要后台执行时，先用自然语言简短确认，然后输出一个 `HUB_ACTION` JSON 块。
- 工具调用必须字段完整。
- 不要编造 agent，必须使用上下文中 Hub facts 给出的在线 agent。
- 如果没有合适在线 agent，要说明无法派发，询问用户是否稍后再试。
- 用户要求“详细说说/展开/原文/刚才那个任务”时，从任务表选择最可能的 taskId，调用 `request_task_detail`。

建议格式：

```text
普通回复文本。

<HUB_ACTION>
{
  "actionType": "dispatch_task",
  "targetAgentId": "edge:mimocode-worker",
  "task": {
    "title": "...",
    "summary": "...",
    "urgency": "normal",
    "instructions": "...",
    "expectedOutput": "..."
  }
}
</HUB_ACTION>
```

Android main 解析到 `HUB_ACTION` 后：

1. 从用户可见回复中移除 JSON 块。
2. 先播报/显示自然语言部分。
3. 执行 Hub action。
4. 将 Hub action 结果作为 system/tool result 再送入 LLM，生成最终确认。
5. 同步 transcript。

### 7.2 facts 注入上下文

每轮 LLM 请求时，在 system prompt 后追加一段本地 facts 摘要：

```text
当前 Hub facts：
- 在线执行 agent：
  - edge:mimocode-worker，能力：coding, shell, web_fetch，状态：online
- 进行中任务：
  - task_xxx：查询天气，状态 running
- 待汇报任务：
  - task_yyy：调研完成，summary=...
```

注意：

- 这是 Android main 的本地缓存，不要每轮都同步请求 Hub。
- 若 Hub 未连接，则写明“Hub 未连接，不能派发任务”。

## 八、任务主动汇报机制

第一版实现简单版本即可，不做复杂状态机。

本地维护：

```text
pendingReports: completed/failed but not locally reported tasks
```

触发时机：

- 每次 facts 同步发现新 completed/failed 任务。
- 当前没有录音、没有播放 TTS、没有正在处理用户回合。
- 与上次主动汇报间隔至少 10-20 秒。

汇报策略：

- 最多合并 3 条任务。
- 只播报摘要。
- 播报后本地标记 `summary_reported`。
- 用户要求详细报告时，再调用 `request_task_detail` 获取原文。

Hub 不维护“已播报”状态，Android main 自己维护。

第一版可先只存在内存中；重启后可从 Hub 最近任务中重新构造待汇报列表。

## 九、UI 要求

现有 UI 可以保持简洁，但需要增加调试可见性：

- Hub 连接状态：未配置、连接中、已连接、离线、鉴权失败。
- 当前 factsVersion。
- 在线 agent 数量。
- 进行中任务数。
- 待汇报任务数。
- 最近一次 Hub action 结果。
- 最近一次同步时间。

聊天列表中应显示：

- 用户 ASR 文本。
- main 回复。
- 工具调用小卡片或系统消息，例如：
  - “调用 Hub：dispatch_task -> edge:mimocode-worker”
  - “Hub 返回：task_xxx running”
  - “任务完成：xxx”

## 十、错误处理

必须处理：

- Hub 未配置。
- Hub 不可达。
- token 错误。
- target agent 不在线。
- action 字段缺失。
- Hub 返回 4xx/5xx。
- ASR 为空。
- LLM 输出 malformed JSON。
- TTS 失败。

对用户话术要求：

- 简短。
- 先说结果。
- 不要输出技术栈长篇解释。
- 例如：
  - “Hub 没连上，暂时不能派活。”
  - “这个执行 agent 不在线，我没法现在派发。”
  - “工具调用格式不完整，我重新整理一下。”

## 十一、验收标准

### 11.1 离线模式

条件：不配置 Hub。

要求：

- App 可以启动。
- 可以语音聊天。
- 用户要求派活时，main 明确说明 Hub 未连接，不能派发。
- 不崩溃。

### 11.2 Hub 注册

条件：配置 Hub base URL 和 token。

要求：

- App 启动后调用 `/api/main/register`。
- Hub Web 管理页能看到 Android main client 在线。
- App UI 显示 Hub 已连接。

### 11.3 facts 同步

要求：

- App 能获取 agents/tasks snapshot。
- UI 显示在线 agent 数和任务数。
- LLM 上下文能知道当前有哪些在线 agent。

测试问题：

```text
现在有哪些执行 agent 在线？
```

期望：

- main 根据本地 facts 回答。
- 不应胡编不存在的 agent。

### 11.4 派发任务

测试语音：

```text
让后台执行 agent 查一下明天广州天气，简短汇报。
```

期望：

- main 简短确认。
- 产生 `dispatch_task`。
- App 调 `/api/main/action/submit`。
- Hub 创建任务。
- 在线执行 agent 收到任务。
- App 聊天列表显示工具调用和 taskId。

### 11.5 任务完成与主动汇报

条件：执行 agent 返回 completed/failed。

期望：

- App facts 同步发现任务完成。
- 任务进入本地 pendingReports。
- main 在空闲时主动播报一句摘要。
- 本地标记该任务已摘要播报。

### 11.6 详细报告

测试语音：

```text
刚才那个任务详细说说。
```

期望：

- main 根据本地任务表选择最近 completed 任务。
- 调用 `request_task_detail`。
- 如果 `ttsAllowed=true`，播报原文或摘要后的详细内容。
- 如果 `ttsAllowed=false`，不播报原文，只提示用户查看文本。

### 11.7 transcript 归档

每轮对话后，Hub Web 聊天/归档中能看到：

- 用户 ASR 文本。
- main 回复文本。
- turnId。
- conversationId。
- audio metadata。
- Hub action 请求或结果相关信息。

## 十二、推荐开发顺序

1. 新增 Hub 配置和 `HubClient`。
2. 实现 `/api/main/register` 和 UI 连接状态。
3. 实现 facts snapshot 拉取和本地 `HubFactsStore`。
4. 将 facts 摘要注入 LLM prompt。
5. 设计并解析 `<HUB_ACTION>` JSON 块。
6. 实现 `dispatch_task`。
7. 实现 transcript append。
8. 实现 `request_task_detail`。
9. 实现简单 pending report 队列和主动汇报。
10. 增加调试日志和链路耗时埋点。

## 十三、注意事项

- 不要把真实 API key、Hub token、个人地址提交到仓库。
- 不要把 Hub 的职责塞进 Android main。
- 不要让 Android main 绕过 Hub 直接调用执行 agent。
- 不要只改 LangChain4j 早期骨架，当前真实主链路在 `VoiceAgentService`。
- 不要在本阶段追求完整状态机；先跑通协议闭环。
- 不要删除流式 TTS 代码，后续还要专项验证。
- 保持语音回复短，能一句话说清就不要长篇解释。

## 十四、最终交付物

开发完成后应提交：

- Android 源码改动。
- Hub 接入配置示例 `.env.example` 或 README 更新。
- 简短开发日志，说明：
  - 已实现哪些 Hub 接口。
  - 如何配置 Hub。
  - 如何测试注册、派发、汇报、详情。
  - 当前限制。
- 至少一组实机测试记录：
  - 离线聊天。
  - Hub 注册。
  - 派发任务。
  - 任务完成主动汇报。
  - 请求详细报告。

