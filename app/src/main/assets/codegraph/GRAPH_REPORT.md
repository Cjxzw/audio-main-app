# Graph Report - audio-main-app  (2026-10-08)

## Corpus Check
- 217 files · ~116,951 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 3243 nodes · 6371 edges · 212 communities (141 shown, 71 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 419 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `5dccf6cd`
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
- .append
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
- SkillRegistry
- OpenAiCompatibleLlmClient
- .executeBackgroundLlm
- HubSettings
- TaskAdapter
- HubRuntime
- .runAgentLoop
- .addLlmMessage
- Listener
- HubConnectionState
- ExaWebSearchClient
- .error
- TurnMetricsTrackerTest
- HubModels.kt
- nextHubReportState
- HanwoDevTest
- 4. 当前已实现
- .pcm16ToWav
- 13. 语音时延、上下文和单回合思考协议
- 7. 当前已知问题
- AudioFeedbackPolicy
- SpeechSegmenter
- WorkspaceDeletePolicyTest
- 0. 2026-07-21 本轮更新
- DeviceContextProvider
- WorkspaceDeletePolicy
- ReplyDetailPolicyTest
- AudioFeedbackPolicyTest
- DebugBridgeProtocolTest
- TextPatchApplierTest
- 17. 可扩展设置中心、模型解耦与个性化播报
- showLightDialog
- TextPatchApplier
- .playAudio
- MultimodalTranscriberTest
- HubConfigValidationTest
- RealtimePipelineRepository
- hanwo-dev script
- .client
- .use
- README.md
- ExperimentalReplyParserTest
- State
- TextEditorActivity.kt
- AppVisibility
- JsonElement
- IntentRouting.kt
- Gitea.Common.psm1
- 27. 渐进式 Skill、回合韧性与委派约束
- 28. 跨设备签名、手动恢复与长详情转储
- HubSettingsFragment
- LongDetailsPolicyTest
- BackgroundLlmRetryPlanTest
- 26. 空正文重试、详情折叠与代码图谱同步
- BackgroundLlmRetryPlan
- ActiveTurnCheckpointStoreTest
- ExperimentConfig.kt
- .write
- ChatAdapter
- AndroidExecutionEnv.kt
- State
- FileLogTree
- ExecArgumentParserTest
- RealtimeTranscriptAccumulatorTest
- RealtimePlainTextPolicy
- RealtimeTranscriptAccumulator
- State
- RealtimePlainTextPolicyTest
- VoiceReplyLengthGate
- TaskStatus
- SettingsActivity
- MainRealtimeIntegrationTest
- LongDetailsPolicy
- buildCurrentTurnUserContent
- ServiceState
- ProcessScrollView
- MainAgentHarnessTest
- RealtimeState
- BackgroundTurnNotificationPolicy
- BackgroundTurnNotificationPolicyTest
- ChatDetailsExpansionPolicy

## God Nodes (most connected - your core abstractions)
1. `VoiceAgentService` - 179 edges
2. `CloudSpeechClient` - 153 edges
3. `Communities (218 total, 77 thin omitted)` - 137 edges
4. `ConversationStore` - 79 edges
5. `SkillRegistry` - 63 edges
6. `MainActivity` - 52 edges
7. `TaskEntity` - 49 edges
8. `FakeRuntime` - 45 edges
9. `MainToolRegistry` - 43 edges
10. `AgentLoop` - 42 edges

## Surprising Connections (you probably didn't know these)
- `withoutPrivateReasoning()` --references--> `CloudSpeechClient`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/agent/runtime/IntentRouting.kt → app/src/main/java/com/agent/voiceassistant/cloud/CloudSpeechClient.kt
- `MainActivity` --references--> `ConversationStore`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/data/ConversationStore.kt
- `MainActivity` --references--> `TaskRepository`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/tasks/TaskRepository.kt
- `MainActivity` --references--> `ChatAdapter`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/ui/ChatAdapter.kt
- `MainActivity` --references--> `ConversationAdapter`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/ui/ConversationAdapter.kt

## Import Cycles
- None detected.

## Communities (212 total, 71 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.13
Nodes (5): AudioTrack, ByteArray, PreparedTtsAudio, StreamingTtsPlaybackSession, WavDataChunk

### Community 1 - "Community 1"
Cohesion: 0.20
Nodes (3): AgentLoop, AgentLoopTest, FakeRuntime

### Community 2 - "Community 2"
Cohesion: 0.10
Nodes (22): ChatCompletion, ChatRequest, ChatStreamAccumulator, ChatStreamEvent, ChatUsage, ContentDelta, Finished, ImageInput (+14 more)

### Community 3 - "Community 3"
Cohesion: 0.06
Nodes (21): StoredLocation, android, Job, LocationProvider, LocationTimeoutException, RefreshSnapshot, RefreshState, COOLDOWN (+13 more)

### Community 4 - "Community 4"
Cohesion: 0.05
Nodes (23): JsonObject, StepFunRealtimeProtocol, ByteArray, ShortArray, WavUtil, EncryptedSecretStore, SecretKey, Execution (+15 more)

### Community 5 - "Community 5"
Cohesion: 0.08
Nodes (3): CloudSpeechClient, JsonElement, NoopRuntime

### Community 6 - "Community 6"
Cohesion: 0.19
Nodes (3): CredentialProfileStore, okhttp3, SecretKey

### Community 7 - "Community 7"
Cohesion: 0.27
Nodes (5): CodeGraphIndex, Link, Node, Snapshot, Snapshot

### Community 8 - "Community 8"
Cohesion: 0.27
Nodes (4): ActivitySkillEditorBinding, AppCompatActivity, Bundle, SkillEditorActivity

### Community 9 - "Community 9"
Cohesion: 0.15
Nodes (4): EventBus, StateFlow, ServiceLog, SharedFlow

### Community 10 - "Community 10"
Cohesion: 0.06
Nodes (18): DispatchedTask, TaskStatus, completed, failed, in_progress, pending, PendingResult, CoroutineScope (+10 more)

### Community 11 - "Community 11"
Cohesion: 0.05
Nodes (19): LongDetailsPolicy, Result, MultimodalTranscriber, SpeechSegmenter, State, CODE_FENCE, DISPLAY_DETAIL, MARKDOWN_TABLE (+11 more)

### Community 12 - "Community 12"
Cohesion: 0.13
Nodes (9): AudioInputRoutePolicy, AudioRouteManager, ExternalOutput, AudioRecord, AudioTrack, MediaPlayer, RouteReadiness, AudioDeviceInfo (+1 more)

### Community 13 - "Community 13"
Cohesion: 0.12
Nodes (10): FrameProcessor, CoroutineScope, Job, Frame, FrameDirection, DOWNSTREAM, UPSTREAM, ASRProcessor (+2 more)

### Community 14 - "Community 14"
Cohesion: 0.09
Nodes (13): Holder, ActivityWorkspaceBinding, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup, TrashAdapter (+5 more)

### Community 15 - "Community 15"
Cohesion: 0.16
Nodes (19): BotStartedSpeakingFrame, BotStoppedSpeakingFrame, CancelFrame, DataFrame, EndFrame, FunctionCallResultFrame, InputAudioRawFrame, InterruptionFrame (+11 more)

### Community 16 - "Community 16"
Cohesion: 0.08
Nodes (21): ActivityLlmProviderEditorBinding, DebugBridgeProtocol, DebugBridgeRequest, DebugBridgeReceiver, Context, Intent, JsonArray, JsonObject (+13 more)

### Community 17 - "Community 17"
Cohesion: 0.06
Nodes (20): ActivityWorkspacePreviewBinding, Holder, ActivityWorkspaceBinding, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup (+12 more)

### Community 18 - "Community 18"
Cohesion: 0.07
Nodes (32): ActiveBudgetStarted, ActiveToolBudgetExceeded, AgentEvent, AgentFailed, AgentFinished, AgentInterrupted, AgentStarted, AutomaticThinkingEscalated (+24 more)

### Community 19 - "Community 19"
Cohesion: 0.22
Nodes (4): CoroutineScope, Job, UserIdleDetector, UserIdleListener

### Community 20 - "Community 20"
Cohesion: 0.11
Nodes (16): Action, CONTINUE, SLEEP, WARN, CaptureResult, InactivitySleep, InactivityWarning, AudioRecord (+8 more)

### Community 21 - "Community 21"
Cohesion: 0.33
Nodes (3): AudioOutputProcessor, AudioTrack, Job

### Community 22 - "Community 22"
Cohesion: 0.09
Nodes (11): DetailExtraction, ReplyDetailPolicy, AppCompatActivity, Bundle, RealtimeActivity, ChatAdapter, Markwon, RecyclerView (+3 more)

### Community 24 - "Community 24"
Cohesion: 0.33
Nodes (3): AudioInputProcessor, AudioRecord, Job

### Community 26 - "Community 26"
Cohesion: 0.16
Nodes (3): AgentFactory, AgentTools, Assistant

### Community 27 - "Community 27"
Cohesion: 0.47
Nodes (3): AudioConfig, ShortArray, FloatArray

### Community 29 - "Community 29"
Cohesion: 0.14
Nodes (7): ActivityMainBinding, AppCompatActivity, Uri, MainActivity, PageHomeBinding, PageHubBinding, PageTasksBinding

### Community 30 - "Community 30"
Cohesion: 0.01
Nodes (137): Communities (218 total, 77 thin omitted), Community 0 - "Community 0", Community 100 - "六、模型清单与能力", Community 106 - "十一、流式输出", Community 107 - "十五、计费与配额消耗估算", Community 108 - "二、Token Plan 概述", Community 109 - "三、套餐档位与额度", Community 10 - "Community 10" (+129 more)

### Community 31 - "Community 31"
Cohesion: 0.10
Nodes (14): Audio, AudioDone, Drain, Event, Interrupt, AudioRecord, AudioTrack, ByteArray (+6 more)

### Community 32 - "Community 32"
Cohesion: 0.33
Nodes (3): VoiceBarView, Canvas, View

### Community 33 - "Community 33"
Cohesion: 0.08
Nodes (25): 0.1 2026-10-01 工具写入与恢复修复, 10. 仓库维护规则, 11. Pi 风格 Harness 与 Android 执行环境, 12. 工具链实机反馈修复, 14. 思考反馈与 MiMo TTS 音色统一, 15. 工具协议、批量读取和正文防护, 16. 标准媒体入口、冷启动注册与通知合并, 18. 去 Telecom 化与纯媒体语音会话 (+17 more)

### Community 34 - "Community 34"
Cohesion: 0.40
Nodes (3): AssistEntryActivity, Activity, Bundle

### Community 35 - "Community 35"
Cohesion: 0.60
Nodes (4): fromAssets(), fromContext(), Context, ModelPaths

### Community 37 - "Community 37"
Cohesion: 0.12
Nodes (12): ActivityReflectionsBinding, Holder, AppCompatActivity, Bundle, Holder, RecyclerView, ViewGroup, ReflectionActivity (+4 more)

### Community 38 - "Community 38"
Cohesion: 0.50
Nodes (3): BoundedSourceReader, Result, BufferedSource

### Community 39 - "Community 39"
Cohesion: 0.23
Nodes (4): Mount, VirtualPathResolver, VirtualPathResolverTest, java

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
Cohesion: 0.07
Nodes (7): AgentKeepAlive, com, Job, VoiceAgentService, VoiceReplyPresentation, SpeechPreferences, Service

### Community 55 - "LlmProviderRepository"
Cohesion: 0.07
Nodes (17): FirstEventTimeoutException, StreamIdleTimeoutException, EmptyLlmResponseException, FallbackLlmClient, IOException, firstEventTimeoutMs(), IOException, JsonObject (+9 more)

### Community 56 - "Main Agent 下一版修复改进方案"
Cohesion: 0.07
Nodes (27): 10. 需要用户确认, 1. 背景与实机证据, 2. 本版目标, 3.1 聊天窗口, 3.2 摘要规则, 3. 工具调用记录改造, 4.1 计数范围, 4.2 自动升级流程 (+19 more)

### Community 57 - "Runtime"
Cohesion: 0.19
Nodes (7): AppCapabilities, AppCapabilityResolver, detectKeyType(), MimoApiRepository, MimoKeyType, PAY_AS_YOU_GO, TOKEN_PLAN

### Community 58 - ".requestTts"
Cohesion: 0.22
Nodes (4): Flow, TaskEntity, Diff, DiffUtil

### Community 60 - "CloudSpeechClient"
Cohesion: 0.07
Nodes (14): buildFinalFormatRepairInstruction(), buildToolCallRepairInstruction(), CheckpointPhase, RUNNING, WAITING_NETWORK, WAITING_RECOVERY, Completed, Config (+6 more)

### Community 61 - "LLMConfig"
Cohesion: 0.11
Nodes (21): StoredAttachment, toUi(), ChatPresentation, PERSONALIZED_VOICE, STANDARD, ChatRole, BOT, SYSTEM (+13 more)

### Community 62 - ".onCreate"
Cohesion: 0.18
Nodes (4): AsyncTaskCoordinator, TaskExecutor, DelayedTestExecutor, TaskExecutionResult

### Community 63 - "VoiceAgentService.kt"
Cohesion: 0.17
Nodes (24): bootstrap(), cancelAgent(), cancelTask(), compactConversation(), deleteConversation(), Context, Intent, newConversation() (+16 more)

### Community 64 - ".playAudio"
Cohesion: 0.18
Nodes (8): AudioPayload, FirstAudioTimeoutException, ByteArray, IOException, JsonObject, okhttp3, NetworkTimeoutException, VoiceReplyOptions

### Community 65 - "MimoSettingsFragment"
Cohesion: 0.23
Nodes (4): SettingsActivityInstrumentedTest, CredentialProfilesFragment, Bundle, LlmProvidersFragment

### Community 66 - "MimoApiRepository"
Cohesion: 0.07
Nodes (11): AgentAction, AgentOutput, BodyToolCall, BodyToolCallMatch, BodyToolCallStreamGate, BodyToolCallTooLargeException, JsonElement, StructuredOutputParser (+3 more)

### Community 67 - "TaskRepository"
Cohesion: 0.11
Nodes (4): TaskEventEntity, TaskSubmission, Flow, TaskRepository

### Community 68 - "EarconPlayer"
Cohesion: 0.24
Nodes (3): EarconPlayer, AudioTrack, ByteArray

### Community 70 - "TextEditorActivity.kt"
Cohesion: 0.23
Nodes (6): ActivityTextEditorBinding, AppCompatActivity, Bundle, Markwon, TextEditorActivity, Source

### Community 71 - "TaskEntity"
Cohesion: 0.21
Nodes (3): Bundle, Intent, TabLayoutMediator

### Community 72 - "TaskModels.kt"
Cohesion: 0.13
Nodes (12): TaskArtifactEntity, TaskOrigin, HUB, LOCAL, TaskPriority, NORMAL, URGENT, TaskReportState (+4 more)

### Community 73 - "Hanwo（喊我）"
Cohesion: 0.12
Nodes (17): Agent 执行环境, Agent 调试 CLI, Hanwo（喊我）, 下一步, 主要代码结构, 代码图谱, 会话提示词边界, 使用 (+9 more)

### Community 74 - ".client"
Cohesion: 0.12
Nodes (10): AssistantDraft, CompletableDeferred, Job, StepFunRealtimePipeline, TextSource, AUDIO_TRANSCRIPT, TEXT, ToolOutput (+2 more)

### Community 76 - "VoiceReplyDirective.kt"
Cohesion: 0.09
Nodes (21): 1. 背景, 2.1 目标, 2.2 约束, 2. 目标与约束, 3.1 改名, 3.2 提示词原则, 3.3 第一阶段改动范围, 3.4 实机验证用例 (+13 more)

### Community 77 - "TaskAdapter"
Cohesion: 0.31
Nodes (5): ConversationAdapter, RecyclerView, ViewGroup, ViewHolder, ViewHolder

### Community 78 - "SimpleVadRecorder"
Cohesion: 0.12
Nodes (12): ConnectionState, CONNECTED, CONNECTING, DISCONNECTED, FAILED, ByteArray, CompletableDeferred, Flow (+4 more)

### Community 79 - ".append"
Cohesion: 0.21
Nodes (8): Holder, intent(), Context, Holder, RecyclerView, ViewGroup, newIntent(), SkillFileAdapter

### Community 80 - "5.1 OpenAI 兼容协议"
Cohesion: 0.22
Nodes (9): 5.1 OpenAI 兼容协议, 5.2 Anthropic 兼容协议, Token Plan Base URL, Token Plan Base URL（按区域选择）, 五、接入方式与端点, 按量付费 Base URL, 按量付费 Base URL, 请求头格式 (+1 more)

### Community 82 - "HubClient"
Cohesion: 0.20
Nodes (8): from(), HubClient, CompletableDeferred, Job, JsonObject, StateFlow, HubTaskFact, Json

### Community 83 - "Route"
Cohesion: 0.18
Nodes (7): Route, ACTIVE_AUDIO, DEFER, DORMANT_EXTERNAL_AUDIO, NOTIFICATION, REALTIME, TaskReportPolicy

### Community 85 - "小米 MiMo API 接入调研文档"
Cohesion: 0.25
Nodes (7): 一、文档与来源, 七、模型选择决策树, 二十、参考链接汇总, 十七、注意事项与坑点, 十二、错误码速查, 十八、与现有 LLM 方案对比, 小米 MiMo API 接入调研文档

### Community 86 - "Command"
Cohesion: 0.38
Nodes (4): Command, NEW_TOPIC, SLEEP, LocalConversationCommandPolicy

### Community 87 - "MimoApiRepository"
Cohesion: 0.22
Nodes (8): VoicePerformance, SINGING, SPEECH, VoiceReplyDirective, VoiceReplyDirectiveParser, VoiceReplyMode, DESIGN, PRESET

### Community 88 - "Development Status"
Cohesion: 0.33
Nodes (5): RecyclerView, ViewGroup, MainPage, MainPageAdapter, PageViewHolder

### Community 89 - "MultimodalImageEncoder"
Cohesion: 0.13
Nodes (7): RealtimePipelineRepository, StepFunRealtimeConfig, VoicePipeline, MIMO_STANDARD, STEPFUN_REALTIME, StepFunRealtimeSettingsFragment, StepFunRealtimeProtocolTest

### Community 90 - "TaskDatabase"
Cohesion: 0.40
Nodes (4): get(), Context, TaskDatabase, RoomDatabase

### Community 94 - "HubAgentFact"
Cohesion: 0.29
Nodes (6): HubAgentAdapter, ListAdapter, RecyclerView, ViewGroup, ViewHolder, ViewHolder

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
Cohesion: 0.20
Nodes (10): Community Hubs (Navigation), Corpus Check, God Nodes (most connected - your core abstractions), Graph Freshness, Graph Report - audio-main-app  (2026-10-08), Import Cycles, Knowledge Gaps, Suggested Questions (+2 more)

### Community 124 - "MimoWebSearchClient"
Cohesion: 0.24
Nodes (18): AudioDelta, AudioDone, AudioTranscriptDelta, Error, Event, FunctionArgumentsDone, ResponseCreated, ResponseDone (+10 more)

### Community 130 - ".executeBackgroundLlm"
Cohesion: 0.20
Nodes (5): BackgroundLlmResult, BackgroundLlmTask, Result, Memory, T

### Community 131 - "HubSettings"
Cohesion: 0.15
Nodes (4): buildHubWebSocketUrl(), HubConfigRepository, HubSettings, HubProtocolModelsTest

### Community 132 - "TaskAdapter"
Cohesion: 0.25
Nodes (6): ListAdapter, RecyclerView, ViewGroup, ViewHolder, TaskAdapter, ViewHolder

### Community 133 - "HubRuntime"
Cohesion: 0.17
Nodes (6): HubAuthenticator, okhttp3, toHttpUrlOrThrow(), HubFacts, HubRuntime, Context

### Community 134 - ".runAgentLoop"
Cohesion: 0.12
Nodes (5): IntentRoutingResult, VoiceReplyLengthGate, Deferred, TurnCheckpointContext, VoiceReplyLengthGateTest

### Community 135 - ".addLlmMessage"
Cohesion: 0.16
Nodes (5): StoredMessage, StoredToolCall, StoredToolTrace, ToolHistoryPolicy, RealtimeContextProjection

### Community 136 - "Listener"
Cohesion: 0.39
Nodes (4): WebSocket, Listener, Response, WebSocketListener

### Community 137 - "HubConnectionState"
Cohesion: 0.22
Nodes (8): HubConnectionState, AUTH_FAILED, CONNECTED, CONNECTING, DISABLED, DISCONNECTED, ERROR, StateFlow

### Community 138 - "ExaWebSearchClient"
Cohesion: 0.39
Nodes (3): ExaWebSearchClient, SearchResult, Source

### Community 139 - ".error"
Cohesion: 0.38
Nodes (3): ConversationMemoryCompactor, Result, ConversationMemoryDraft

### Community 140 - "TurnMetricsTrackerTest"
Cohesion: 0.24
Nodes (8): ExperimentalReplyParser, Result, Section, ANSWER, DETAILS, OUTSIDE, THINKING, Tag

### Community 141 - "HubModels.kt"
Cohesion: 0.29
Nodes (5): decideHubDelta(), HubDeltaDecision, APPLY, IGNORE, REQUEST_SNAPSHOT

### Community 144 - "4. 当前已实现"
Cohesion: 0.29
Nodes (7): 4.1 最小语音闭环, 4.2 后台运行和休眠, 4.3 音频路由, 4.4 当前 App 包大小, 4.5 Agent 框架与工具调用, 4.6 流式 TTS 首尾噪声修复, 4. 当前已实现

### Community 145 - ".pcm16ToWav"
Cohesion: 0.14
Nodes (14): ConversationCompressionMessage, ConversationCompressionSource, ConversationDomain, REALTIME, STANDARD, ConversationSession, defaultConversationTitle(), newConversation() (+6 more)

### Community 146 - "13. 语音时延、上下文和单回合思考协议"
Cohesion: 0.33
Nodes (6): 13.1 统一 5 秒首响应时限, 13.2 KV 缓存友好的上下文顺序, 13.3 单回合深度思考, 13.4 历史和持久化边界, 13.5 构建与资产检查, 13. 语音时延、上下文和单回合思考协议

### Community 147 - "7. 当前已知问题"
Cohesion: 0.33
Nodes (6): 7.1 延迟波动, 7.2 Hub 工具尚未接入, 7.3 流式 TTS 需要专项验证, 7.4 音量问题, 7.5 第三方通话蓝牙路由待验证, 7. 当前已知问题

### Community 149 - "SpeechSegmenter"
Cohesion: 0.18
Nodes (4): Deferred, StateFlow, MainAgentHarness, QueuedInput

### Community 151 - "0. 2026-07-21 本轮更新"
Cohesion: 0.40
Nodes (5): 0. 2026-07-21 本轮更新, Skills 与记忆, 工作区、附件和图片, 本地命令与提示音, 音频生命周期和播放音量域

### Community 152 - "DeviceContextProvider"
Cohesion: 0.43
Nodes (3): JsonObject, MalformedToolCallException, ToolCallSafety

### Community 158 - "17. 可扩展设置中心、模型解耦与个性化播报"
Cohesion: 0.50
Nodes (4): 17.1 多级设置中心, 17.2 模型与语音客户端解耦, 17.3 终止型个性化 TTS, 17. 可扩展设置中心、模型解耦与个性化播报

### Community 161 - ".playAudio"
Cohesion: 0.22
Nodes (5): MediaPlayer, PcmChunk, AudioAttributes, AudioFocusRequest, IllegalStateException

### Community 166 - ".client"
Cohesion: 0.50
Nodes (4): 29.1 两套提示词, 29.2 Realtime 工具边界, 29.3 验证, 29. 主会话与 Realtime 提示词分层

### Community 167 - ".use"
Cohesion: 0.18
Nodes (7): ByteArray, Registration, Residency, CONVERSATION, TURN, SkillFile, UseResult

### Community 168 - "README.md"
Cohesion: 0.12
Nodes (12): Hanwo Agent 调试 CLI, 命令, 安全边界, 快速开始, Baseline, Current Boundaries, Current Capabilities, Development Status (+4 more)

### Community 171 - "TextEditorActivity.kt"
Cohesion: 0.22
Nodes (12): Context, memoryIntent(), newMemoryIntent(), newRuleIntent(), ruleIntent(), skillIntent(), Source, MEMORY (+4 more)

### Community 172 - "AppVisibility"
Cohesion: 0.27
Nodes (4): AppVisibility, Activity, Application, Bundle

### Community 173 - "JsonElement"
Cohesion: 0.22
Nodes (8): http_request 参数, HTTP 工具与凭证, URL 安全规则, 凭证引用, 响应处理, 完整调用示例, 指导用户配置凭证, 适用范围

### Community 174 - "IntentRouting.kt"
Cohesion: 0.17
Nodes (8): IntentCategory, CHAT, QUICK_ANSWER, TASK, IntentRoutingParser, IntentRoutingPrompt, Result, withoutPrivateReasoning()

### Community 175 - "Gitea.Common.psm1"
Cohesion: 0.60
Nodes (3): Get-GiteaContext(), Import-GiteaDotEnv(), Invoke-GiteaRequest()

### Community 176 - "27. 渐进式 Skill、回合韧性与委派约束"
Cohesion: 0.40
Nodes (5): 27.1 固定工具表与系统 Skill, 27.2 上下文与语音摘要, 27.3 有效时间预算、网络等待与恢复, 27.4 快速检索、任务委派与反思, 27. 渐进式 Skill、回合韧性与委派约束

### Community 177 - "28. 跨设备签名、手动恢复与长详情转储"
Cohesion: 0.40
Nodes (5): 28.1 共享 Debug 签名, 28.2 Subagent 路由与 Skill 使用, 28.3 回合恢复与超时口径, 28.4 长详情与流式界面, 28. 跨设备签名、手动恢复与长详情转储

### Community 178 - "HubSettingsFragment"
Cohesion: 0.22
Nodes (10): AboutSettingsFragment, bindLightDialogInput(), HubSettingsFragment, MimoSettingsFragment, RootSettingsFragment, VoiceSettingsFragment, EditTextPreference, Preference (+2 more)

### Community 181 - "26. 空正文重试、详情折叠与代码图谱同步"
Cohesion: 0.50
Nodes (4): 26.1 空正文重试, 26.2 聊天详情折叠, 26.3 Graphify 和实机验证, 26. 空正文重试、详情折叠与代码图谱同步

### Community 187 - ".write"
Cohesion: 0.21
Nodes (5): ExecResult, HttpResult, ByteArray, ReadResult, WriteResult

### Community 188 - "ChatAdapter"
Cohesion: 0.33
Nodes (3): HubAgentFact, areContentsTheSame(), areItemsTheSame()

### Community 189 - "AndroidExecutionEnv.kt"
Cohesion: 0.28
Nodes (3): LogEntry, LogFilterPolicy, Profile

### Community 190 - "State"
Cohesion: 0.22
Nodes (8): State, CANCELLING, FAILED, IDLE, RUNNING, WAITING_NETWORK, WAITING_RECOVERY, WAITING_RETRY

### Community 191 - "FileLogTree"
Cohesion: 0.31
Nodes (4): App, FileLogTree, Application, Timber

### Community 198 - "VoiceReplyLengthGate"
Cohesion: 0.33
Nodes (6): ActiveTurnCheckpointStore, decode(), encode(), Message, Snapshot, ToolCall

### Community 199 - "TaskStatus"
Cohesion: 0.22
Nodes (9): TaskStatus, BLOCKED, CANCELLED, COMPLETED, CREATED, FAILED, INTERRUPTED, QUEUED (+1 more)

### Community 200 - "SettingsActivity"
Cohesion: 0.32
Nodes (4): ActivitySettingsBinding, AppCompatActivity, SettingsActivity, Fragment

### Community 202 - "LongDetailsPolicy"
Cohesion: 0.47
Nodes (3): CacheKey, MultimodalImageEncoder, Bitmap

### Community 203 - "buildCurrentTurnUserContent"
Cohesion: 0.12
Nodes (9): buildCurrentTurnUserContent(), buildMainSystemPrompt(), buildRealtimeSystemPrompt(), LLMConfig, mimo(), unconfigured(), LLMConfigTest, ChatStreamAccumulatorTest (+1 more)

### Community 205 - "ServiceState"
Cohesion: 0.25
Nodes (7): ServiceState, DORMANT, FAILED, IDLE, INITIALIZING, LISTENING, READY

### Community 206 - "ProcessScrollView"
Cohesion: 0.33
Nodes (3): ProcessScrollView, MotionEvent, ScrollView

### Community 210 - "RealtimeState"
Cohesion: 0.33
Nodes (5): RealtimeState, CONNECTING, FAILED, READY, STOPPED

## Knowledge Gaps
- **511 isolated node(s):** `ExperimentConfig`, `OUTSIDE`, `THINKING`, `ANSWER`, `DETAILS` (+506 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **71 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VoiceAgentService` connect `VoiceAgentService` to `SkillRegistry`, `Community 0`, `.executeBackgroundLlm`, `Community 3`, `Community 4`, `Community 5`, `.runAgentLoop`, `Community 11`, `Community 12`, `Community 16`, `Community 17`, `Community 20`, `.playAudio`, `Community 40`, `RuleStore`, `.emitLog`, `Runtime`, `.requestTts`, `.onCreate`, `VoiceAgentService.kt`, `TaskRepository`, `EarconPlayer`, `高级 TTS 导演`, `VoiceReplyLengthGate`, `LongDetailsPolicy`, `.client`, `MultimodalImageEncoder`, `ConversationStore.kt`?**
  _High betweenness centrality (0.205) - this node is a cross-community bridge._
- **Why does `CloudSpeechClient` connect `Community 5` to `Community 0`, `Community 1`, `Community 2`, `.executeBackgroundLlm`, `Community 4`, `.runAgentLoop`, `.addLlmMessage`, `Community 11`, `Community 18`, `DeviceContextProvider`, `.playAudio`, `Community 40`, `IntentRouting.kt`, `VoiceAgentService`, `.emitLog`, `LlmProviderRepository`, `CloudSpeechClient`, `LLMConfig`, `.playAudio`, `MimoApiRepository`, `State`, `高级 TTS 导演`, `VoiceReplyLengthGate`, `LongDetailsPolicy`, `.client`, `buildCurrentTurnUserContent`, `SimpleVadRecorder`?**
  _High betweenness centrality (0.090) - this node is a cross-community bridge._
- **Why does `ConversationStore` connect `Community 40` to `Community 3`, `TextEditorActivity.kt`, `.addLlmMessage`, `TaskEntity`, `MainRealtimeIntegrationTest`, `Community 16`, `.pcm16ToWav`, `Community 29`, `RuleStore`, `VoiceAgentService`, `Community 22`, `LLMConfig`?**
  _High betweenness centrality (0.070) - this node is a cross-community bridge._
- **Are the 6 inferred relationships involving `CloudSpeechClient` (e.g. with `.`deep tool history passes reasoning content and omits temperature`()` and `.`payload explicitly disables thinking for fast turns`()`) actually correct?**
  _`CloudSpeechClient` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Are the 6 inferred relationships involving `ConversationStore` (e.g. with `.longTranscriptIsTransferredOnceAndSurvivesLaterMainTurns()` and `.clearConversations()`) actually correct?**
  _`ConversationStore` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Are the 10 inferred relationships involving `SkillRegistry` (e.g. with `.`creates a standard editable single file skill`()` and `.`disabled skill leaves active directory and reports unavailable`()`) actually correct?**
  _`SkillRegistry` has 10 INFERRED edges - model-reasoned connections that need verification._
- **What connects `ExperimentConfig`, `OUTSIDE`, `THINKING` to the rest of the system?**
  _511 weakly-connected nodes found - possible documentation gaps or missing edges._