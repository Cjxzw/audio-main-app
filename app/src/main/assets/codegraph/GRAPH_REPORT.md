# Graph Report - audio-main-app  (2026-07-26)

## Corpus Check
- 134 files · ~68,749 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2057 nodes · 3881 edges · 119 communities (86 shown, 33 thin omitted)
- Extraction: 95% EXTRACTED · 5% INFERRED · 0% AMBIGUOUS · INFERRED: 202 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `2c089274`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- Community 0
- Community 1
- Community 2
- Community 3
- Community 4
- Community 5
- Community 6
- Community 7
- Community 8
- Community 9
- Community 10
- Community 11
- Community 12
- Community 13
- Community 14
- Community 15
- Community 16
- Community 17
- Community 18
- Community 19
- Community 20
- Community 21
- Community 22
- Community 23
- Community 24
- Community 25
- Community 26
- Community 27
- Community 28
- Community 29
- Community 30
- Community 31
- Community 32
- Community 33
- Community 34
- Community 35
- Community 36
- Community 37
- Community 38
- Community 39
- Community 40
- Community 41
- Community 42
- Community 43
- Community 44
- Community 46
- AssistantMediaPlayer
- Audio Main App 接入枢卫 Hub 开发任务书
- RuleStore
- ContextAssetsActivity
- VoiceAgentService
- .emitLog
- LlmProviderRepository
- Main Agent 下一版修复改进方案
- Runtime
- .requestTts
- TaskDao
- CloudSpeechClient
- LLMConfig
- .onCreate
- VoiceAgentService.kt
- .playAudio
- MimoSettingsFragment
- MimoApiRepository
- TaskRepository
- EarconPlayer
- 高级 TTS 导演
- TextEditorActivity.kt
- TaskEntity
- TaskModels.kt
- Hanwo（喊我）
- .client
- SongGenerationExecutor
- VoiceReplyDirective.kt
- TaskAdapter
- SimpleVadRecorder
- TaskStatus
- 5.1 OpenAI 兼容协议
- SettingsActivity
- Route
- TaskReportPolicyTest
- 小米 MiMo API 接入调研文档
- Command
- Development Status
- MultimodalImageEncoder
- TaskDatabase
- StructuredOutputParserTest
- ListeningInactivityPolicyTest
- MainMediaSessionInstrumentedTest
- LocalConversationCommandPolicyTest
- SpokenReplyPolicyTest
- VadThresholdPolicyTest
- MimoApiRepositoryTest
- 十六、完整接入示例
- 六、模型清单与能力
- AssistantNotificationContract
- AudioInputRoutePolicyTest
- ConversationMemoryCompactorTest
- ToolHistoryPolicyTest
- WeatherClientTest
- 十一、流式输出
- 十五、计费与配额消耗估算
- 二、Token Plan 概述
- 三、套餐档位与额度
- 八、mimo-v2.5 文本模型接入要点
- ExaWebSearchClientTest
- generate-thinking-audio.sh
- 十、TTS 接入方式
- 十三、响应格式参考
- 十四、工具集成（Token Plan 共享额度）
- 十九、结论与建议
- 四、账号、认证与 API Key
- 九、ASR 接入方式
- generate-inactivity-audio.sh
- Graph Report - audio-main-app  (2026-07-25)

## God Nodes (most connected - your core abstractions)
1. `VoiceAgentService` - 117 edges
2. `CloudSpeechClient` - 108 edges
3. `Communities (122 total, 34 thin omitted)` - 85 edges
4. `SkillRegistry` - 50 edges
5. `ConversationStore` - 47 edges
6. `MainActivity` - 41 edges
7. `WorkspaceRepository` - 37 edges
8. `AudioRouteManager` - 36 edges
9. `MainToolRegistry` - 36 edges
10. `TaskEntity` - 35 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `ConversationStore`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/data/ConversationStore.kt
- `MainActivity` --references--> `TaskRepository`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/tasks/TaskRepository.kt
- `MainActivity` --references--> `WorkspaceRepository`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/workspace/WorkspaceRepository.kt
- `MainAgentHarness` --references--> `AgentLoop`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/agent/runtime/MainAgentHarness.kt → app/src/main/java/com/agent/voiceassistant/agent/runtime/AgentLoop.kt
- `NoopRuntime` --implements--> `AgentLoop`  [EXTRACTED]
  app/src/test/java/com/agent/voiceassistant/agent/runtime/MainAgentHarnessTest.kt → app/src/main/java/com/agent/voiceassistant/agent/runtime/AgentLoop.kt

## Import Cycles
- None detected.

## Communities (119 total, 33 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.14
Nodes (5): AudioTrack, ByteArray, PreparedTtsAudio, StreamingTtsPlaybackSession, WavDataChunk

### Community 1 - "Community 1"
Cohesion: 0.16
Nodes (3): AgentLoop, AgentLoopTest, FakeRuntime

### Community 2 - "Community 2"
Cohesion: 0.05
Nodes (30): ChatCompletion, ChatRequest, ChatStreamAccumulator, ChatStreamEvent, ContentDelta, Finished, FirstEventTimeoutException, ImageInput (+22 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (21): StoredLocation, android, Job, LocationProvider, LocationTimeoutException, RefreshSnapshot, RefreshState, COOLDOWN (+13 more)

### Community 5 - "Community 5"
Cohesion: 0.16
Nodes (3): AgentFactory, AgentTools, Assistant

### Community 6 - "Community 6"
Cohesion: 0.08
Nodes (11): AndroidExecutionEnvInstrumentedTest, AndroidExecutionEnv, CredentialProfileStore, ExecResult, HttpResult, SecretKey, LogEntry, LogFilterPolicy (+3 more)

### Community 7 - "Community 7"
Cohesion: 0.30
Nodes (4): CodeGraphIndex, Link, Node, Snapshot

### Community 8 - "Community 8"
Cohesion: 0.05
Nodes (23): ActivitySkillEditorBinding, ActivityTextEditorBinding, Registration, Skill, SkillFile, SkillRegistry, AppCompatActivity, Bundle (+15 more)

### Community 9 - "Community 9"
Cohesion: 0.11
Nodes (10): EventBus, ServiceLog, ServiceState, DORMANT, FAILED, IDLE, INITIALIZING, LISTENING (+2 more)

### Community 10 - "Community 10"
Cohesion: 0.06
Nodes (18): DispatchedTask, TaskStatus, completed, failed, in_progress, pending, PendingResult, CoroutineScope (+10 more)

### Community 11 - "Community 11"
Cohesion: 0.08
Nodes (10): SpeechSegmenter, State, CODE_FENCE, JSON_ONLY, REPLY, TAG, TEXT, TOOL (+2 more)

### Community 12 - "Community 12"
Cohesion: 0.13
Nodes (9): AudioInputRoutePolicy, AudioRouteManager, ExternalOutput, AudioRecord, AudioTrack, MediaPlayer, RouteReadiness, AudioDeviceInfo (+1 more)

### Community 13 - "Community 13"
Cohesion: 0.12
Nodes (10): FrameProcessor, CoroutineScope, Job, Frame, FrameDirection, DOWNSTREAM, UPSTREAM, ASRProcessor (+2 more)

### Community 14 - "Community 14"
Cohesion: 0.28
Nodes (4): com, JsonObject, LocalToolExecutor, ToolResult

### Community 15 - "Community 15"
Cohesion: 0.16
Nodes (19): BotStartedSpeakingFrame, BotStoppedSpeakingFrame, CancelFrame, DataFrame, EndFrame, FunctionCallResultFrame, InputAudioRawFrame, InterruptionFrame (+11 more)

### Community 16 - "Community 16"
Cohesion: 0.16
Nodes (5): Flow, TaskEntity, Flow, Diff, DiffUtil

### Community 17 - "Community 17"
Cohesion: 0.06
Nodes (19): ActivityWorkspaceBinding, ActivityWorkspacePreviewBinding, Holder, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup (+11 more)

### Community 18 - "Community 18"
Cohesion: 0.22
Nodes (17): AgentEvent, AgentFailed, AgentFinished, AgentStarted, AutomaticThinkingEscalated, ContentDelta, MessageFinished, MessageStarted (+9 more)

### Community 19 - "Community 19"
Cohesion: 0.22
Nodes (4): CoroutineScope, Job, UserIdleDetector, UserIdleListener

### Community 20 - "Community 20"
Cohesion: 0.14
Nodes (14): Action, CONTINUE, SLEEP, WARN, CaptureResult, InactivitySleep, InactivityWarning, ShortArray (+6 more)

### Community 21 - "Community 21"
Cohesion: 0.33
Nodes (3): AudioOutputProcessor, AudioTrack, Job

### Community 22 - "Community 22"
Cohesion: 0.22
Nodes (4): ChatAdapter, RecyclerView, ViewGroup, VH

### Community 24 - "Community 24"
Cohesion: 0.33
Nodes (3): AudioInputProcessor, AudioRecord, Job

### Community 26 - "Community 26"
Cohesion: 0.31
Nodes (4): App, FileLogTree, Application, Timber

### Community 27 - "Community 27"
Cohesion: 0.47
Nodes (3): AudioConfig, ShortArray, FloatArray

### Community 29 - "Community 29"
Cohesion: 0.05
Nodes (27): ActivityMainBinding, ConversationSummary, android, AppCompatActivity, Bundle, Intent, Uri, MainActivity (+19 more)

### Community 30 - "Community 30"
Cohesion: 0.02
Nodes (85): Communities (122 total, 34 thin omitted), Community 0 - "Community 0", Community 100 - "六、模型清单与能力", Community 106 - "十一、流式输出", Community 107 - "十五、计费与配额消耗估算", Community 108 - "二、Token Plan 概述", Community 109 - "三、套餐档位与额度", Community 10 - "Community 10" (+77 more)

### Community 32 - "Community 32"
Cohesion: 0.33
Nodes (3): VoiceBarView, Canvas, View

### Community 33 - "Community 33"
Cohesion: 0.04
Nodes (44): 0. 2026-07-21 本轮更新, 10. 仓库维护规则, 11. Pi 风格 Harness 与 Android 执行环境, 12. 工具链实机反馈修复, 13.1 统一 5 秒首响应时限, 13.2 KV 缓存友好的上下文顺序, 13.3 单回合深度思考, 13.4 历史和持久化边界 (+36 more)

### Community 34 - "Community 34"
Cohesion: 0.40
Nodes (3): Activity, AssistEntryActivity, Bundle

### Community 35 - "Community 35"
Cohesion: 0.60
Nodes (4): fromAssets(), fromContext(), Context, ModelPaths

### Community 37 - "Community 37"
Cohesion: 0.40
Nodes (5): State, FAILED, INITIALIZING, LISTENING, READY

### Community 38 - "Community 38"
Cohesion: 0.50
Nodes (3): BoundedSourceReader, Result, BufferedSource

### Community 39 - "Community 39"
Cohesion: 0.23
Nodes (4): Mount, VirtualPathResolver, VirtualPathResolverTest, java

### Community 40 - "Community 40"
Cohesion: 0.05
Nodes (31): ConversationMemoryCompactor, ConversationCompressionMessage, ConversationCompressionSource, ConversationMemoryDraft, ConversationSession, ConversationStore, defaultConversationTitle(), newConversation() (+23 more)

### Community 44 - "Community 44"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 49 - "AssistantMediaPlayer"
Cohesion: 0.08
Nodes (19): AssistantMediaPlayer, Callbacks, MediaItem, buildForegroundNotification(), ensureStarted(), android, Intent, MediaItem (+11 more)

### Community 50 - "Audio Main App 接入枢卫 Hub 开发任务书"
Cohesion: 0.05
Nodes (36): 11.1 离线模式, 11.2 Hub 注册, 11.3 facts 同步, 11.4 派发任务, 11.5 任务完成与主动汇报, 11.6 详细报告, 11.7 transcript 归档, 5.1 鉴权 (+28 more)

### Community 51 - "RuleStore"
Cohesion: 0.11
Nodes (15): ConversationRuleLedger, renderRuleLedger(), RuleChange, RuleOperation, ADD, DELETE, DISABLE, ENABLE (+7 more)

### Community 52 - "ContextAssetsActivity"
Cohesion: 0.10
Nodes (18): ActivityContextAssetsBinding, ContextAssetAdapter, ContextAssetItem, ContextAssetsActivity, Holder, Kind, MEMORY, RULE (+10 more)

### Community 54 - ".emitLog"
Cohesion: 0.11
Nodes (6): buildMainSystemPrompt(), Job, Notification, VoiceAgentService, ChatMessage, Service

### Community 55 - "LlmProviderRepository"
Cohesion: 0.06
Nodes (18): ActivityLlmProviderEditorBinding, buildCurrentTurnUserContent(), buildTurnGuidance(), LLMConfig, LlmProviderMode, MIMO, OPENAI_COMPATIBLE, mimo() (+10 more)

### Community 56 - "Main Agent 下一版修复改进方案"
Cohesion: 0.07
Nodes (27): 10. 需要用户确认, 1. 背景与实机证据, 2. 本版目标, 3.1 聊天窗口, 3.2 摘要规则, 3. 工具调用记录改造, 4.1 计数范围, 4.2 自动升级流程 (+19 more)

### Community 57 - "Runtime"
Cohesion: 0.13
Nodes (7): Completed, Config, ModelTurn, Outcome, Runtime, TerminalExecution, ToolExecution

### Community 58 - ".requestTts"
Cohesion: 0.18
Nodes (7): AudioPayload, FirstAudioTimeoutException, ByteArray, JsonObject, okhttp3, NetworkTimeoutException, VoiceReplyOptions

### Community 60 - "CloudSpeechClient"
Cohesion: 0.13
Nodes (3): CloudSpeechClient, JsonElement, NoopRuntime

### Community 61 - "LLMConfig"
Cohesion: 0.13
Nodes (10): MainAgentHarness, QueuedInput, State, CANCELLING, FAILED, IDLE, RUNNING, MainAgentHarnessTest (+2 more)

### Community 62 - ".onCreate"
Cohesion: 0.16
Nodes (4): AsyncTaskCoordinator, TaskExecutor, DelayedTestExecutor, TaskExecutionResult

### Community 63 - "VoiceAgentService.kt"
Cohesion: 0.27
Nodes (16): bootstrap(), cancelTask(), compactConversation(), deleteConversation(), Context, Intent, newConversation(), renameConversation() (+8 more)

### Community 64 - ".playAudio"
Cohesion: 0.20
Nodes (4): MediaPlayer, PcmChunk, AudioAttributes, AudioFocusRequest

### Community 65 - "MimoSettingsFragment"
Cohesion: 0.07
Nodes (22): ActivitySettingsBinding, SettingsActivityInstrumentedTest, AppCapabilities, AppCapabilityResolver, detectKeyType(), MimoApiRepository, MimoKeyType, PAY_AS_YOU_GO (+14 more)

### Community 66 - "MimoApiRepository"
Cohesion: 0.26
Nodes (3): AgentAction, AgentOutput, StructuredOutputParser

### Community 67 - "TaskRepository"
Cohesion: 0.15
Nodes (3): TaskEventEntity, TaskSubmission, TaskRepository

### Community 68 - "EarconPlayer"
Cohesion: 0.24
Nodes (3): EarconPlayer, AudioTrack, ByteArray

### Community 69 - "高级 TTS 导演"
Cohesion: 0.15
Nodes (12): 使用边界, 原创音色设计, 唱歌, 导演提示格式, 当前封装, 模式选择, 示例：动作与环境, 示例：导演模式 (+4 more)

### Community 70 - "TextEditorActivity.kt"
Cohesion: 0.22
Nodes (12): Context, memoryIntent(), newMemoryIntent(), newRuleIntent(), ruleIntent(), skillIntent(), Source, MEMORY (+4 more)

### Community 71 - "TaskEntity"
Cohesion: 0.13
Nodes (11): VoicePerformance, SINGING, SPEECH, VoiceReplyDirective, VoiceReplyDirectiveParser, VoiceReplyMode, DESIGN, PRESET (+3 more)

### Community 72 - "TaskModels.kt"
Cohesion: 0.15
Nodes (11): TaskArtifactEntity, TaskOrigin, HUB, LOCAL, TaskPriority, NORMAL, URGENT, TaskReportState (+3 more)

### Community 73 - "Hanwo（喊我）"
Cohesion: 0.15
Nodes (12): Agent 执行环境, Hanwo（喊我）, 下一步, 主要代码结构, 使用, 安装, 当前能力, 当前限制 (+4 more)

### Community 74 - ".client"
Cohesion: 0.15
Nodes (7): Execution, JsonObject, Profile, CONNECTED, DIAGNOSTIC, STANDALONE, TaskToolContext

### Community 79 - "TaskStatus"
Cohesion: 0.22
Nodes (9): TaskStatus, BLOCKED, CANCELLED, COMPLETED, CREATED, FAILED, INTERRUPTED, QUEUED (+1 more)

### Community 80 - "5.1 OpenAI 兼容协议"
Cohesion: 0.22
Nodes (9): 5.1 OpenAI 兼容协议, 5.2 Anthropic 兼容协议, Token Plan Base URL, Token Plan Base URL（按区域选择）, 五、接入方式与端点, 按量付费 Base URL, 按量付费 Base URL, 请求头格式 (+1 more)

### Community 83 - "Route"
Cohesion: 0.29
Nodes (6): Route, ACTIVE_AUDIO, DEFER, DORMANT_EXTERNAL_AUDIO, NOTIFICATION, TaskReportPolicy

### Community 85 - "小米 MiMo API 接入调研文档"
Cohesion: 0.25
Nodes (7): 一、文档与来源, 七、模型选择决策树, 二十、参考链接汇总, 十七、注意事项与坑点, 十二、错误码速查, 十八、与现有 LLM 方案对比, 小米 MiMo API 接入调研文档

### Community 86 - "Command"
Cohesion: 0.38
Nodes (4): Command, NEW_TOPIC, SLEEP, LocalConversationCommandPolicy

### Community 88 - "Development Status"
Cohesion: 0.29
Nodes (6): Baseline, Current Audio Decisions, Development Status, Known Issues, Repository Scope, Working Flow

### Community 89 - "MultimodalImageEncoder"
Cohesion: 0.47
Nodes (3): CacheKey, MultimodalImageEncoder, Bitmap

### Community 90 - "TaskDatabase"
Cohesion: 0.40
Nodes (4): get(), Context, TaskDatabase, RoomDatabase

### Community 99 - "十六、完整接入示例"
Cohesion: 0.40
Nodes (5): 16.1 文本对话（OpenAI，流式）, 16.2 ASR（OpenAI 兼容）, 16.3 TTS（OpenAI 兼容）, 16.4 Kotlin 接入示例（OpenAI 兼容）, 十六、完整接入示例

### Community 100 - "六、模型清单与能力"
Cohesion: 0.40
Nodes (5): 6.1 文本生成模型, 6.2 ASR（语音识别）, 6.3 TTS（语音合成）, 6.4 旧版本状态, 六、模型清单与能力

### Community 106 - "十一、流式输出"
Cohesion: 0.50
Nodes (4): 11.1 支持流式的模型, 11.2 OpenAI 兼容流式调用, 11.3 流式响应格式, 十一、流式输出

### Community 107 - "十五、计费与配额消耗估算"
Cohesion: 0.50
Nodes (4): 15.1 文本模型 Credits 消耗参考, 15.2 套餐能支撑多少对话？, 15.3 并行消耗规则, 十五、计费与配额消耗估算

### Community 108 - "二、Token Plan 概述"
Cohesion: 0.50
Nodes (4): 2.1 产品定位, 2.2 接入模式, 2.3 账号与密钥体系, 二、Token Plan 概述

### Community 109 - "三、套餐档位与额度"
Cohesion: 0.50
Nodes (4): 3.1 月付套餐, 3.2 年付套餐（享 88 折）, 3.3 折扣与计费规则, 三、套餐档位与额度

### Community 110 - "八、mimo-v2.5 文本模型接入要点"
Cohesion: 0.50
Nodes (4): 8.1 基本参数, 8.2 思考模式（多轮工具调用）, 8.3 联网搜索, 八、mimo-v2.5 文本模型接入要点

### Community 113 - "十、TTS 接入方式"
Cohesion: 0.67
Nodes (3): 10.1 三种模式对比, 10.2 消息格式要求, 十、TTS 接入方式

### Community 114 - "十三、响应格式参考"
Cohesion: 0.67
Nodes (3): 13.1 文本对话响应（非流式）, 13.2 流式响应（SSE data 片段）, 十三、响应格式参考

### Community 115 - "十四、工具集成（Token Plan 共享额度）"
Cohesion: 0.67
Nodes (3): 14.1 已支持工具列表, 14.2 工具配置要点, 十四、工具集成（Token Plan 共享额度）

### Community 116 - "十九、结论与建议"
Cohesion: 0.67
Nodes (3): 19.1 推荐策略, 19.2 下一步行动, 十九、结论与建议

### Community 117 - "四、账号、认证与 API Key"
Cohesion: 0.67
Nodes (3): 4.1 登录与注册, 4.2 API Key 安全须知, 四、账号、认证与 API Key

### Community 118 - "九、ASR 接入方式"
Cohesion: 0.67
Nodes (3): 9.1 Token Plan 计费特点, 9.2 请求格式, 九、ASR 接入方式

### Community 123 - "Graph Report - audio-main-app  (2026-07-25)"
Cohesion: 0.18
Nodes (10): Community Hubs (Navigation), Corpus Check, God Nodes (most connected - your core abstractions), Graph Freshness, Graph Report - audio-main-app  (2026-07-26), Import Cycles, Knowledge Gaps, Suggested Questions (+2 more)

## Knowledge Gaps
- **364 isolated node(s):** `MIMO`, `OPENAI_COMPATIBLE`, `NEW_TOPIC`, `SLEEP`, `IDLE` (+359 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **33 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VoiceAgentService` connect `.emitLog` to `Community 0`, `Community 3`, `Community 4`, `Community 6`, `Community 8`, `Community 12`, `Community 16`, `Community 40`, `RuleStore`, `VoiceAgentService`, `LlmProviderRepository`, `CloudSpeechClient`, `.onCreate`, `VoiceAgentService.kt`, `.playAudio`, `MimoSettingsFragment`, `TaskRepository`, `EarconPlayer`, `VoiceReplyDirective.kt`, `MultimodalImageEncoder`?**
  _High betweenness centrality (0.278) - this node is a cross-community bridge._
- **Why does `CloudSpeechClient` connect `CloudSpeechClient` to `.playAudio`, `Community 0`, `Community 2`, `Community 1`, `Community 4`, `Community 40`, `.client`, `TaskAdapter`, `VoiceAgentService`, `.emitLog`, `LlmProviderRepository`, `MultimodalImageEncoder`, `.requestTts`, `Runtime`?**
  _High betweenness centrality (0.098) - this node is a cross-community bridge._
- **Why does `ChatMessage` connect `.emitLog` to `Community 40`, `Community 9`, `VoiceAgentService`, `Community 22`, `Community 25`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Are the 5 inferred relationships involving `CloudSpeechClient` (e.g. with `.`deep tool history passes reasoning content and omits temperature`()` and `.`payload explicitly disables thinking for fast turns`()`) actually correct?**
  _`CloudSpeechClient` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `SkillRegistry` (e.g. with `.`creates a standard editable single file skill`()` and `.`disabled skill leaves active directory and reports unavailable`()`) actually correct?**
  _`SkillRegistry` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `MIMO`, `OPENAI_COMPATIBLE`, `NEW_TOPIC` to the rest of the system?**
  _364 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.14285714285714285 - nodes in this community are weakly interconnected._