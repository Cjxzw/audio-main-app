# Graph Report - .  (2026-07-17)

## Corpus Check
- cluster-only mode — file stats not available

## Summary
- 882 nodes · 1734 edges · 49 communities (38 shown, 11 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 76 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `e0786c35`
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

## God Nodes (most connected - your core abstractions)
1. `CloudSpeechClient` - 84 edges
2. `VoiceAgentService` - 78 edges
3. `MainToolRegistry` - 26 edges
4. `AudioRouteManager` - 24 edges
5. `ConversationStore` - 21 edges
6. `AndroidExecutionEnv` - 21 edges
7. `LocalToolExecutor` - 21 edges
8. `AgentLoop` - 20 edges
9. `FakeRuntime` - 20 edges
10. `FrameProcessor` - 19 edges

## Surprising Connections (you probably didn't know these)
- `MainActivity` --references--> `ConversationStore`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/MainActivity.kt → app/src/main/java/com/agent/voiceassistant/data/ConversationStore.kt
- `MainAgentHarness` --references--> `AgentLoop`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/agent/runtime/MainAgentHarness.kt → app/src/main/java/com/agent/voiceassistant/agent/runtime/AgentLoop.kt
- `VoiceAgentService` --references--> `SkillRegistry`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/service/VoiceAgentService.kt → app/src/main/java/com/agent/voiceassistant/agent/runtime/SkillRegistry.kt
- `AudioInputProcessor` --inherits--> `FrameProcessor`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/audio/AudioInputProcessor.kt → app/src/main/java/com/agent/voiceassistant/pipeline/FrameProcessor.kt
- `AudioOutputProcessor` --inherits--> `FrameProcessor`  [EXTRACTED]
  app/src/main/java/com/agent/voiceassistant/audio/AudioOutputProcessor.kt → app/src/main/java/com/agent/voiceassistant/pipeline/FrameProcessor.kt

## Import Cycles
- None detected.

## Communities (49 total, 11 thin omitted)

### Community 0 - "Community 0"
Cohesion: 0.06
Nodes (23): AudioTrack, ByteArray, Context, Intent, Job, MediaPlayer, PcmChunk, sendText() (+15 more)

### Community 1 - "Community 1"
Cohesion: 0.06
Nodes (14): AgentLoop, Completed, Config, ModelTurn, Outcome, Runtime, ToolExecution, AudioPayload (+6 more)

### Community 2 - "Community 2"
Cohesion: 0.08
Nodes (26): ChatCompletion, ChatRequest, ChatStreamAccumulator, ChatStreamEvent, ContentDelta, Finished, FirstAudioTimeoutException, FirstEventTimeoutException (+18 more)

### Community 3 - "Community 3"
Cohesion: 0.08
Nodes (11): ConversationSession, ConversationStore, StoredLocation, StoredMemory, StoredMessage, StoredToolCall, StoreState, LocationProvider (+3 more)

### Community 4 - "Community 4"
Cohesion: 0.09
Nodes (11): AgentAction, AgentOutput, StructuredOutputParser, Execution, JsonObject, MainToolRegistry, Profile, CONNECTED (+3 more)

### Community 5 - "Community 5"
Cohesion: 0.07
Nodes (13): AgentFactory, AgentTools, Assistant, auto(), buildCurrentTurnUserContent(), buildMainSystemPrompt(), buildTurnGuidance(), LLMConfig (+5 more)

### Community 6 - "Community 6"
Cohesion: 0.10
Nodes (9): AndroidExecutionEnvInstrumentedTest, AndroidExecutionEnv, CredentialProfileStore, ExecResult, HttpResult, Profile, ReadResult, WriteResult (+1 more)

### Community 7 - "Community 7"
Cohesion: 0.09
Nodes (10): Skill, SkillRegistry, EarconPlayer, AudioTrack, ByteArray, CodeGraphIndex, Link, Node (+2 more)

### Community 8 - "Community 8"
Cohesion: 0.09
Nodes (10): android, AssistantConnectionService, AssistantConnection, AssistantTelecomRegistry, AssistantTelecomSession, CallAudioState, Connection, ConnectionRequest (+2 more)

### Community 9 - "Community 9"
Cohesion: 0.09
Nodes (14): ActivityMainBinding, Bundle, MainActivity, EventBus, ServiceLog, ServiceState, DORMANT, FAILED (+6 more)

### Community 10 - "Community 10"
Cohesion: 0.09
Nodes (13): DispatchedTask, TaskStatus, completed, failed, in_progress, pending, Priority, LOW (+5 more)

### Community 11 - "Community 11"
Cohesion: 0.10
Nodes (9): SpeechSegmenter, State, JSON_ONLY, REPLY, TAG, TEXT, TOOL, StreamingSpeechExtractor (+1 more)

### Community 12 - "Community 12"
Cohesion: 0.17
Nodes (6): AudioRouteManager, AudioRecord, AudioTrack, MediaPlayer, AudioDeviceInfo, AudioRouting

### Community 13 - "Community 13"
Cohesion: 0.12
Nodes (10): FrameProcessor, CoroutineScope, Job, Frame, FrameDirection, DOWNSTREAM, UPSTREAM, ASRProcessor (+2 more)

### Community 14 - "Community 14"
Cohesion: 0.30
Nodes (4): com, JsonObject, LocalToolExecutor, ToolResult

### Community 15 - "Community 15"
Cohesion: 0.16
Nodes (19): BotStartedSpeakingFrame, BotStoppedSpeakingFrame, CancelFrame, DataFrame, EndFrame, FunctionCallResultFrame, InputAudioRawFrame, InterruptionFrame (+11 more)

### Community 16 - "Community 16"
Cohesion: 0.13
Nodes (10): MainAgentHarness, QueuedInput, State, CANCELLING, FAILED, IDLE, RUNNING, MainAgentHarnessTest (+2 more)

### Community 17 - "Community 17"
Cohesion: 0.16
Nodes (4): PendingResult, CoroutineScope, Job, PendingResultReporter

### Community 18 - "Community 18"
Cohesion: 0.23
Nodes (14): AgentEvent, AgentFailed, AgentFinished, AgentStarted, ContentDelta, MessageFinished, MessageStarted, ReasoningDelta (+6 more)

### Community 19 - "Community 19"
Cohesion: 0.22
Nodes (4): CoroutineScope, Job, UserIdleDetector, UserIdleListener

### Community 20 - "Community 20"
Cohesion: 0.32
Nodes (4): AudioRecord, ShortArray, Recording, SimpleVadRecorder

### Community 21 - "Community 21"
Cohesion: 0.33
Nodes (3): AudioOutputProcessor, AudioTrack, Job

### Community 22 - "Community 22"
Cohesion: 0.27
Nodes (4): ChatAdapter, VH, RecyclerView, ViewGroup

### Community 24 - "Community 24"
Cohesion: 0.33
Nodes (3): AudioInputProcessor, AudioRecord, Job

### Community 26 - "Community 26"
Cohesion: 0.32
Nodes (4): App, FileLogTree, Application, Timber

### Community 27 - "Community 27"
Cohesion: 0.47
Nodes (3): AudioConfig, ShortArray, FloatArray

### Community 29 - "Community 29"
Cohesion: 0.40
Nodes (3): ByteArray, ShortArray, WavUtil

### Community 30 - "Community 30"
Cohesion: 0.33
Nodes (4): Context, Intent, MediaButtonReceiver, BroadcastReceiver

### Community 32 - "Community 32"
Cohesion: 0.33
Nodes (3): VoiceBarView, Canvas, View

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

### Community 40 - "Community 40"
Cohesion: 0.40
Nodes (4): ChatRole, BOT, SYSTEM, USER

### Community 44 - "Community 44"
Cohesion: 0.83
Nodes (3): gradlew script, die(), warn()

## Knowledge Gaps
- **39 isolated node(s):** `IDLE`, `RUNNING`, `CANCELLING`, `FAILED`, `DISABLED` (+34 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **11 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `VoiceAgentService` connect `Community 0` to `Community 1`, `Community 3`, `Community 4`, `Community 6`, `Community 7`, `Community 8`, `Community 12`, `Community 18`, `Community 20`?**
  _High betweenness centrality (0.376) - this node is a cross-community bridge._
- **Why does `CloudSpeechClient` connect `Community 1` to `Community 0`, `Community 2`, `Community 3`, `Community 4`, `Community 5`?**
  _High betweenness centrality (0.221) - this node is a cross-community bridge._
- **Why does `ChatMessage` connect `Community 0` to `Community 3`, `Community 40`, `Community 9`, `Community 22`, `Community 25`?**
  _High betweenness centrality (0.194) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `CloudSpeechClient` (e.g. with `.`deep tool history passes reasoning content and omits temperature`()` and `.`payload explicitly disables thinking for fast turns`()`) actually correct?**
  _`CloudSpeechClient` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `IDLE`, `RUNNING`, `CANCELLING` to the rest of the system?**
  _39 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Community 0` be split into smaller, more focused modules?**
  _Cohesion score 0.06460674157303371 - nodes in this community are weakly interconnected._
- **Should `Community 1` be split into smaller, more focused modules?**
  _Cohesion score 0.061938061938061936 - nodes in this community are weakly interconnected._