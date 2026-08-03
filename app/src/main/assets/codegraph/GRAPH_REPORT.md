# Graph Report - audio-main-app  (2026-08-03)

## Corpus Check
- 176 files · ~86,862 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 2587 nodes · 5061 edges · 166 communities (111 shown, 55 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 350 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `3838d703`
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
- HubClient
- Route
- TaskReportPolicyTest
- 小米 MiMo API 接入调研文档
- Command
- MimoApiRepository
- Development Status
- MultimodalImageEncoder
- TaskDatabase
- StructuredOutputParserTest
- ListeningInactivityPolicyTest
- MainMediaSessionInstrumentedTest
- HubAgentFact
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
- AGENTS.md
- LlmProviderRepository
- FallbackLlmClient
- Graph Report - audio-main-app  (2026-07-25)
- MimoWebSearchClient
- TurnMetricsTracker
- LlmProviderEditorActivity
- ConversationStore.kt
- HubConfigRepository
- DeviceContextProvider
- ConversationMemoryCompactor
- HubSettings
- TaskAdapter
- HubRuntime
- .runAgentLoop
- .addLlmMessage
- Listener
- HubConnectionState
- ExaWebSearchClient
- .client
- TurnMetricsTrackerTest
- HubModels.kt
- nextHubReportState
- HanwoDevTest
- 4. 当前已实现
- .pcm16ToWav
- 13. 语音时延、上下文和单回合思考协议
- 7. 当前已知问题
- AudioFeedbackPolicy
- HubClient.kt
- WorkspaceDeletePolicyTest
- 0. 2026-07-21 本轮更新
- DebugBridgeProtocol
- WorkspaceDeletePolicy
- ReplyDetailPolicyTest
- AudioFeedbackPolicyTest
- DebugBridgeProtocolTest
- TextPatchApplierTest
- 17. 可扩展设置中心、模型解耦与个性化播报
- showLightDialog
- TextPatchApplier
- ChatDetailsExpansionPolicy
- MultimodalTranscriberTest
- HubConfigValidationTest
- .`expands details in the three most recent user rounds`
- hanwo-dev script

## God Nodes (most connected - your core abstractions)
1. `VoiceAgentService` - 133 edges
2. `CloudSpeechClient` - 119 edges
3. `Communities (119 total, 33 thin omitted)` - 83 edges
4. `ConversationStore` - 54 edges
5. `SkillRegistry` - 50 edges
6. `TaskEntity` - 49 edges
7. `MainActivity` - 48 edges
8. `MainToolRegistry` - 39 edges
9. `WorkspaceRepository` - 39 edges
10. `FakeRuntime` - 38 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `ConversationStore`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/data/ConversationStore.kt
- `MainActivity` --references--> `TaskRepository`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/tasks/TaskRepository.kt
- `MainActivity` --references--> `HubAgentAdapter`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/ui/HubAgentAdapter.kt
- `MainActivity` --references--> `TaskAdapter`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/ui/TaskAdapter.kt
- `MainActivity` --references--> `WorkspaceRepository`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/workspace/WorkspaceRepository.kt

## Import Cycles
- None detected.

## Communities (166 total, 55 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.14
Nodes (5): AudioTrack, ByteArray, PreparedTtsAudio, StreamingTtsPlaybackSession, WavDataChunk

### Community 1 - "Community 1"
Cohesion: 0.11
Nodes (13): AgentLoop, StateFlow, MainAgentHarness, QueuedInput, State, CANCELLING, FAILED, IDLE (+5 more)

### Community 2 - "Community 2"
Cohesion: 0.14
Nodes (16): ChatCompletion, ChatRequest, ChatStreamAccumulator, ChatStreamEvent, ContentDelta, Finished, ImageInput, LlmMessage (+8 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (21): StoredLocation, android, Job, LocationProvider, LocationTimeoutException, RefreshSnapshot, RefreshState, COOLDOWN (+13 more)

### Community 4 - "Community 4"
Cohesion: 0.22
Nodes (3): JsonObject, MainToolRegistry, JsonPrimitive

### Community 5 - "Community 5"
Cohesion: 0.16
Nodes (3): AgentFactory, AgentTools, Assistant

### Community 6 - "Community 6"
Cohesion: 0.08
Nodes (12): AndroidExecutionEnvInstrumentedTest, AndroidExecutionEnv, CredentialProfileStore, ExecResult, HttpResult, ByteArray, SecretKey, LogEntry (+4 more)

### Community 7 - "Community 7"
Cohesion: 0.30
Nodes (4): CodeGraphIndex, Link, Node, Snapshot

### Community 8 - "Community 8"
Cohesion: 0.07
Nodes (17): ActivitySkillEditorBinding, Registration, Skill, SkillFile, SkillRegistry, Holder, intent(), AppCompatActivity (+9 more)

### Community 9 - "Community 9"
Cohesion: 0.11
Nodes (10): EventBus, ServiceLog, ServiceState, DORMANT, FAILED, IDLE, INITIALIZING, LISTENING (+2 more)

### Community 10 - "Community 10"
Cohesion: 0.06
Nodes (18): DispatchedTask, TaskStatus, completed, failed, in_progress, pending, PendingResult, CoroutineScope (+10 more)

### Community 11 - "Community 11"
Cohesion: 0.07
Nodes (16): MultimodalTranscriber, SpeechSegmenter, State, CODE_FENCE, DISPLAY_DETAIL, MARKDOWN_TABLE, REPLY, TAG (+8 more)

### Community 12 - "Community 12"
Cohesion: 0.13
Nodes (9): AudioInputRoutePolicy, AudioRouteManager, ExternalOutput, AudioRecord, AudioTrack, MediaPlayer, RouteReadiness, AudioDeviceInfo (+1 more)

### Community 13 - "Community 13"
Cohesion: 0.12
Nodes (10): FrameProcessor, CoroutineScope, Job, Frame, FrameDirection, DOWNSTREAM, UPSTREAM, ASRProcessor (+2 more)

### Community 14 - "Community 14"
Cohesion: 0.08
Nodes (13): Holder, ActivityWorkspaceBinding, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup, TrashAdapter (+5 more)

### Community 15 - "Community 15"
Cohesion: 0.16
Nodes (19): BotStartedSpeakingFrame, BotStoppedSpeakingFrame, CancelFrame, DataFrame, EndFrame, FunctionCallResultFrame, InputAudioRawFrame, InterruptionFrame (+11 more)

### Community 16 - "Community 16"
Cohesion: 0.23
Nodes (7): DebugBridgeRequest, DebugBridgeReceiver, Context, Intent, JsonObject, BroadcastReceiver, JsonArray

### Community 17 - "Community 17"
Cohesion: 0.06
Nodes (20): ActivityWorkspacePreviewBinding, Holder, ActivityWorkspaceBinding, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup (+12 more)

### Community 18 - "Community 18"
Cohesion: 0.19
Nodes (19): AgentEvent, AgentFailed, AgentFinished, AgentStarted, AutomaticThinkingEscalated, ContentDelta, FinalResponseRetry, MessageFinished (+11 more)

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
Cohesion: 0.14
Nodes (7): DetailExtraction, ReplyDetailPolicy, ChatAdapter, Markwon, RecyclerView, ViewGroup, VH

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
Cohesion: 0.06
Nodes (22): ActivityMainBinding, ConversationSummary, android, AppCompatActivity, Bundle, Intent, Uri, MainActivity (+14 more)

### Community 30 - "Community 30"
Cohesion: 0.02
Nodes (83): Communities (119 total, 33 thin omitted), Community 0 - "Community 0", Community 100 - "六、模型清单与能力", Community 106 - "十一、流式输出", Community 107 - "十五、计费与配额消耗估算", Community 108 - "二、Token Plan 概述", Community 109 - "三、套餐档位与额度", Community 10 - "Community 10" (+75 more)

### Community 32 - "Community 32"
Cohesion: 0.33
Nodes (3): VoiceBarView, Canvas, View

### Community 33 - "Community 33"
Cohesion: 0.09
Nodes (22): 10. 仓库维护规则, 11. Pi 风格 Harness 与 Android 执行环境, 12. 工具链实机反馈修复, 14. 思考反馈与 MiMo TTS 音色统一, 15. 工具协议、批量读取和正文防护, 16. 标准媒体入口、冷启动注册与通知合并, 18. 去 Telecom 化与纯媒体语音会话, 19. 语义休眠与蓝牙按键边界 (+14 more)

### Community 34 - "Community 34"
Cohesion: 0.40
Nodes (3): Activity, AssistEntryActivity, Bundle

### Community 35 - "Community 35"
Cohesion: 0.60
Nodes (4): fromAssets(), fromContext(), Context, ModelPaths

### Community 37 - "Community 37"
Cohesion: 0.11
Nodes (11): ActivityReflectionsBinding, Holder, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup, ReflectionActivity (+3 more)

### Community 38 - "Community 38"
Cohesion: 0.25
Nodes (5): ReflectionValidator, ReflectionAnalysis, BoundedSourceReader, Result, BufferedSource

### Community 39 - "Community 39"
Cohesion: 0.23
Nodes (4): Mount, VirtualPathResolver, VirtualPathResolverTest, java

### Community 40 - "Community 40"
Cohesion: 0.15
Nodes (3): ConversationMemoryDraft, ConversationStore, StoredMemory

### Community 44 - "Community 44"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

### Community 49 - "AssistantMediaPlayer"
Cohesion: 0.08
Nodes (20): AssistantMediaPlayer, Callbacks, MediaItem, buildForegroundNotification(), ensureStarted(), android, Intent, MediaItem (+12 more)

### Community 50 - "Audio Main App 接入枢卫 Hub 开发任务书"
Cohesion: 0.05
Nodes (36): 11.1 离线模式, 11.2 Hub 注册, 11.3 facts 同步, 11.4 派发任务, 11.5 任务完成与主动汇报, 11.6 详细报告, 11.7 transcript 归档, 5.1 鉴权 (+28 more)

### Community 51 - "RuleStore"
Cohesion: 0.05
Nodes (33): ActivityContextAssetsBinding, ConversationRuleLedger, renderRuleLedger(), RuleChange, RuleOperation, ADD, DELETE, DISABLE (+25 more)

### Community 52 - "ContextAssetsActivity"
Cohesion: 0.18
Nodes (15): ArgumentParser, CompletedProcess, Namespace, RuntimeError, Adb, bridge_command(), build_parser(), CliError (+7 more)

### Community 53 - "VoiceAgentService"
Cohesion: 0.08
Nodes (6): TurnReflectionRecord, AssistantDraft, Job, VoiceAgentService, VoiceReplyPresentation, Service

### Community 55 - "LlmProviderRepository"
Cohesion: 0.11
Nodes (12): buildCurrentTurnUserContent(), buildMainSystemPrompt(), buildTurnGuidance(), LLMConfig, LlmProviderMode, MIMO, OPENAI_COMPATIBLE, mimo() (+4 more)

### Community 56 - "Main Agent 下一版修复改进方案"
Cohesion: 0.07
Nodes (27): 10. 需要用户确认, 1. 背景与实机证据, 2. 本版目标, 3.1 聊天窗口, 3.2 摘要规则, 3. 工具调用记录改造, 4.1 计数范围, 4.2 自动升级流程 (+19 more)

### Community 57 - "Runtime"
Cohesion: 0.11
Nodes (9): buildFinalFormatRepairInstruction(), buildToolCallRepairInstruction(), Completed, Config, ModelTurn, Outcome, Runtime, TerminalExecution (+1 more)

### Community 58 - ".requestTts"
Cohesion: 0.18
Nodes (8): AudioPayload, FirstAudioTimeoutException, ByteArray, IOException, JsonObject, okhttp3, NetworkTimeoutException, VoiceReplyOptions

### Community 59 - "TaskDao"
Cohesion: 0.09
Nodes (3): Flow, TaskDao, TaskReportActionEntity

### Community 60 - "CloudSpeechClient"
Cohesion: 0.08
Nodes (3): CloudSpeechClient, JsonElement, NoopRuntime

### Community 61 - "LLMConfig"
Cohesion: 0.12
Nodes (17): StoredAttachment, StoredMessage, ChatPresentation, PERSONALIZED_VOICE, STANDARD, ChatRole, BOT, SYSTEM (+9 more)

### Community 62 - ".onCreate"
Cohesion: 0.16
Nodes (4): AsyncTaskCoordinator, TaskExecutor, DelayedTestExecutor, TaskExecutionResult

### Community 63 - "VoiceAgentService.kt"
Cohesion: 0.18
Nodes (21): bootstrap(), cancelTask(), compactConversation(), deleteConversation(), Context, Intent, newConversation(), renameConversation() (+13 more)

### Community 64 - ".playAudio"
Cohesion: 0.21
Nodes (5): MediaPlayer, PcmChunk, AudioAttributes, AudioFocusRequest, IllegalStateException

### Community 65 - "MimoSettingsFragment"
Cohesion: 0.10
Nodes (18): ActivitySettingsBinding, SettingsActivityInstrumentedTest, AboutSettingsFragment, bindLightDialogInput(), HubSettingsFragment, AppCompatActivity, Bundle, LlmProvidersFragment (+10 more)

### Community 66 - "MimoApiRepository"
Cohesion: 0.09
Nodes (9): AgentAction, AgentOutput, BodyToolCall, BodyToolCallStreamGate, BodyToolCallTooLargeException, JsonElement, StructuredOutputParser, Update (+1 more)

### Community 67 - "TaskRepository"
Cohesion: 0.12
Nodes (6): TaskEntity, TaskEventEntity, Flow, TaskRepository, Diff, DiffUtil

### Community 68 - "EarconPlayer"
Cohesion: 0.24
Nodes (3): EarconPlayer, AudioTrack, ByteArray

### Community 69 - "高级 TTS 导演"
Cohesion: 0.15
Nodes (12): 使用边界, 原创音色设计, 唱歌, 导演提示格式, 当前封装, 模式选择, 示例：动作与环境, 示例：导演模式 (+4 more)

### Community 70 - "TextEditorActivity.kt"
Cohesion: 0.11
Nodes (18): ActivityTextEditorBinding, AppCompatActivity, Bundle, Context, Markwon, memoryIntent(), newMemoryIntent(), newRuleIntent() (+10 more)

### Community 71 - "TaskEntity"
Cohesion: 0.13
Nodes (12): JsonObject, MalformedToolCallException, ToolCallSafety, VoicePerformance, SINGING, SPEECH, VoiceReplyDirective, VoiceReplyDirectiveParser (+4 more)

### Community 72 - "TaskModels.kt"
Cohesion: 0.13
Nodes (13): TaskArtifactEntity, TaskOrigin, HUB, LOCAL, TaskPriority, NORMAL, URGENT, TaskReportState (+5 more)

### Community 73 - "Hanwo（喊我）"
Cohesion: 0.11
Nodes (17): Hanwo Agent 调试 CLI, 命令, 安全边界, 快速开始, Agent 执行环境, Agent 调试 CLI, Hanwo（喊我）, 下一步 (+9 more)

### Community 74 - ".client"
Cohesion: 0.16
Nodes (7): Execution, JsonObject, Profile, CONNECTED, DIAGNOSTIC, STANDALONE, TaskToolContext

### Community 79 - "TaskStatus"
Cohesion: 0.22
Nodes (9): TaskStatus, BLOCKED, CANCELLED, COMPLETED, CREATED, FAILED, INTERRUPTED, QUEUED (+1 more)

### Community 80 - "5.1 OpenAI 兼容协议"
Cohesion: 0.22
Nodes (9): 5.1 OpenAI 兼容协议, 5.2 Anthropic 兼容协议, Token Plan Base URL, Token Plan Base URL（按区域选择）, 五、接入方式与端点, 按量付费 Base URL, 按量付费 Base URL, 请求头格式 (+1 more)

### Community 82 - "HubClient"
Cohesion: 0.20
Nodes (8): from(), HubClient, Job, JsonObject, StateFlow, HubTaskFact, CompletableDeferred, Json

### Community 83 - "Route"
Cohesion: 0.20
Nodes (6): Route, ACTIVE_AUDIO, DEFER, DORMANT_EXTERNAL_AUDIO, NOTIFICATION, TaskReportPolicy

### Community 85 - "小米 MiMo API 接入调研文档"
Cohesion: 0.25
Nodes (7): 一、文档与来源, 七、模型选择决策树, 二十、参考链接汇总, 十七、注意事项与坑点, 十二、错误码速查, 十八、与现有 LLM 方案对比, 小米 MiMo API 接入调研文档

### Community 86 - "Command"
Cohesion: 0.38
Nodes (4): Command, NEW_TOPIC, SLEEP, LocalConversationCommandPolicy

### Community 87 - "MimoApiRepository"
Cohesion: 0.19
Nodes (7): AppCapabilities, AppCapabilityResolver, detectKeyType(), MimoApiRepository, MimoKeyType, PAY_AS_YOU_GO, TOKEN_PLAN

### Community 88 - "Development Status"
Cohesion: 0.29
Nodes (6): Baseline, Current Audio Decisions, Development Status, Known Issues, Repository Scope, Working Flow

### Community 89 - "MultimodalImageEncoder"
Cohesion: 0.47
Nodes (3): CacheKey, MultimodalImageEncoder, Bitmap

### Community 90 - "TaskDatabase"
Cohesion: 0.40
Nodes (4): get(), Context, TaskDatabase, RoomDatabase

### Community 91 - "StructuredOutputParserTest"
Cohesion: 0.20
Nodes (6): FirstEventTimeoutException, IOException, okhttp3, LlmClient, LlmHttpException, OpenAiCompatibleLlmClient

### Community 94 - "HubAgentFact"
Cohesion: 0.18
Nodes (9): HubAgentFact, areContentsTheSame(), areItemsTheSame(), HubAgentAdapter, ListAdapter, RecyclerView, ViewGroup, ViewHolder (+1 more)

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

### Community 122 - "FallbackLlmClient"
Cohesion: 0.22
Nodes (5): EmptyLlmResponseException, FallbackLlmClient, IOException, FakeClient, FallbackLlmClientTest

### Community 123 - "Graph Report - audio-main-app  (2026-07-25)"
Cohesion: 0.18
Nodes (10): Community Hubs (Navigation), Corpus Check, God Nodes (most connected - your core abstractions), Graph Freshness, Graph Report - audio-main-app  (2026-07-26), Import Cycles, Knowledge Gaps, Suggested Questions (+2 more)

### Community 124 - "MimoWebSearchClient"
Cohesion: 0.27
Nodes (5): JsonElement, JsonObject, MimoWebSearchClient, SearchResult, Source

### Community 125 - "TurnMetricsTracker"
Cohesion: 0.22
Nodes (5): Interval, StartedTool, TurnMetricsTracker, ToolCallMetric, TurnMetrics

### Community 126 - "LlmProviderEditorActivity"
Cohesion: 0.30
Nodes (4): ActivityLlmProviderEditorBinding, AppCompatActivity, Bundle, LlmProviderEditorActivity

### Community 127 - "ConversationStore.kt"
Cohesion: 0.29
Nodes (6): ConversationSession, defaultConversationTitle(), newConversation(), quarantineMalformedToolHistory(), SharedConversationState, StoreState

### Community 128 - "HubConfigRepository"
Cohesion: 0.18
Nodes (5): HubAuthenticator, okhttp3, toHttpUrlOrThrow(), HubConfigRepository, Context

### Community 130 - "ConversationMemoryCompactor"
Cohesion: 0.31
Nodes (3): ConversationMemoryCompactor, ConversationCompressionMessage, ConversationCompressionSource

### Community 131 - "HubSettings"
Cohesion: 0.24
Nodes (3): buildHubWebSocketUrl(), HubSettings, HubProtocolModelsTest

### Community 132 - "TaskAdapter"
Cohesion: 0.25
Nodes (6): ListAdapter, RecyclerView, ViewGroup, ViewHolder, TaskAdapter, ViewHolder

### Community 135 - ".addLlmMessage"
Cohesion: 0.25
Nodes (3): StoredToolCall, StoredToolTrace, ToolHistoryPolicy

### Community 136 - "Listener"
Cohesion: 0.39
Nodes (4): Listener, Response, WebSocket, WebSocketListener

### Community 137 - "HubConnectionState"
Cohesion: 0.22
Nodes (8): HubConnectionState, AUTH_FAILED, CONNECTED, CONNECTING, DISABLED, DISCONNECTED, ERROR, StateFlow

### Community 138 - "ExaWebSearchClient"
Cohesion: 0.39
Nodes (3): ExaWebSearchClient, SearchResult, Source

### Community 141 - "HubModels.kt"
Cohesion: 0.29
Nodes (5): decideHubDelta(), HubDeltaDecision, APPLY, IGNORE, REQUEST_SNAPSHOT

### Community 144 - "4. 当前已实现"
Cohesion: 0.29
Nodes (7): 4.1 最小语音闭环, 4.2 后台运行和休眠, 4.3 音频路由, 4.4 当前 App 包大小, 4.5 Agent 框架与工具调用, 4.6 流式 TTS 首尾噪声修复, 4. 当前已实现

### Community 145 - ".pcm16ToWav"
Cohesion: 0.40
Nodes (3): ByteArray, ShortArray, WavUtil

### Community 146 - "13. 语音时延、上下文和单回合思考协议"
Cohesion: 0.33
Nodes (6): 13.1 统一 5 秒首响应时限, 13.2 KV 缓存友好的上下文顺序, 13.3 单回合深度思考, 13.4 历史和持久化边界, 13.5 构建与资产检查, 13. 语音时延、上下文和单回合思考协议

### Community 147 - "7. 当前已知问题"
Cohesion: 0.33
Nodes (6): 7.1 延迟波动, 7.2 Hub 工具尚未接入, 7.3 流式 TTS 需要专项验证, 7.4 音量问题, 7.5 第三方通话蓝牙路由待验证, 7. 当前已知问题

### Community 151 - "0. 2026-07-21 本轮更新"
Cohesion: 0.40
Nodes (5): 0. 2026-07-21 本轮更新, Skills 与记忆, 工作区、附件和图片, 本地命令与提示音, 音频生命周期和播放音量域

### Community 158 - "17. 可扩展设置中心、模型解耦与个性化播报"
Cohesion: 0.50
Nodes (4): 17.1 多级设置中心, 17.2 模型与语音客户端解耦, 17.3 终止型个性化 TTS, 17. 可扩展设置中心、模型解耦与个性化播报

## Knowledge Gaps
- **384 isolated node(s):** `MIMO`, `OPENAI_COMPATIBLE`, `NEW_TOPIC`, `SLEEP`, `IDLE` (+379 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **55 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VoiceAgentService` connect `VoiceAgentService` to `Community 0`, `DeviceContextProvider`, `Community 3`, `Community 4`, `.runAgentLoop`, `Community 6`, `Community 8`, `Community 11`, `Community 12`, `Community 37`, `Community 40`, `RuleStore`, `.emitLog`, `CloudSpeechClient`, `.onCreate`, `VoiceAgentService.kt`, `.playAudio`, `MimoSettingsFragment`, `TaskRepository`, `EarconPlayer`, `VoiceReplyDirective.kt`, `MimoApiRepository`, `MultimodalImageEncoder`, `LlmProviderRepository`?**
  _High betweenness centrality (0.237) - this node is a cross-community bridge._
- **Why does `ChatMessage` connect `.emitLog` to `ChatDetailsExpansionPolicy`, `.`expands details in the three most recent user rounds``, `Community 40`, `Community 9`, `VoiceAgentService`, `Community 22`, `Community 25`, `LLMConfig`?**
  _High betweenness centrality (0.110) - this node is a cross-community bridge._
- **Why does `CloudSpeechClient` connect `CloudSpeechClient` to `Community 0`, `Community 1`, `Community 2`, `Community 4`, `.runAgentLoop`, `.addLlmMessage`, `Community 11`, `TurnMetricsTrackerTest`, `Community 40`, `VoiceAgentService`, `.emitLog`, `LlmProviderRepository`, `Runtime`, `.requestTts`, `.playAudio`, `TaskEntity`, `.client`, `TaskAdapter`, `MultimodalImageEncoder`, `StructuredOutputParserTest`, `FallbackLlmClient`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `CloudSpeechClient` (e.g. with `.`deep tool history passes reasoning content and omits temperature`()` and `.`payload explicitly disables thinking for fast turns`()`) actually correct?**
  _`CloudSpeechClient` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Are the 5 inferred relationships involving `ConversationStore` (e.g. with `.clearConversations()` and `.conversations()`) actually correct?**
  _`ConversationStore` has 5 INFERRED edges - model-reasoned connections that need verification._
- **Are the 7 inferred relationships involving `SkillRegistry` (e.g. with `.`creates a standard editable single file skill`()` and `.`disabled skill leaves active directory and reports unavailable`()`) actually correct?**
  _`SkillRegistry` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `MIMO`, `OPENAI_COMPATIBLE`, `NEW_TOPIC` to the rest of the system?**
  _384 weakly-connected nodes found - possible documentation gaps or missing edges._