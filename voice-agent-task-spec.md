# 移动端语音 Agent 项目任务文档

> **文档用途**：上下文重置后的完整记忆恢复。本文档记录了从需求调研到架构决策的完整过程，是后续开发的唯一权威参考。
>
> **创建日期**：2026-07-01
> **最后更新**：2026-07-01（v2 - 补充 Sherpa-ONNX 集成细节、模型下载指引、工程确认事项）

---

## 一、项目概述

### 1.1 项目目标

构建一个**移动端独立运行的语音优先 Agent 应用**，具备"秘书级"能力：能聊天、有活能派出去、活干完了能汇报、汇报时机由状态机控制（忙时择机汇报，闲时主动汇报）。

### 1.2 核心需求

| 编号 | 需求 | 优先级 | 说明 |
|------|------|--------|------|
| R1 | 语音对话 | P0 | 语音输入 + 语音输出，支持打断 |
| R2 | Agent 框架本地运行 | P0 | 上下文管理、工具调用解析、状态机全部在手机端执行，不需要后端服务器 |
| R3 | 云端 LLM 调用 | P0 | 手机直接发 HTTP 请求到云端 LLM API（StepFun/OpenAI），不经后端中转 |
| R4 | 工具调用 | P0 | 简单工具：天气查询、搜索等；复杂工作委派给 PC 端 Agent |
| R5 | 异步任务委派 | P1 | 用户语音指令 -> Agent 派活给后台 Worker -> 不阻塞，继续聊天 |
| R6 | 闲时汇报策略 | P1 | Worker 完成后：用户忙时排队等候，用户闲时主动汇报，紧急任务可打断 |
| R7 | 任务委派到 PC | P2 | 通过 WebSocket/HTTP 将复杂任务发送到 PC 端 Agent 执行 |
| R8 | Android 优先 | P0 | 首要平台 Android（Kotlin），未来可扩展 iOS |

### 1.3 关键设计决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 语音模式 | **级联管线**（ASR->LLM->TTS）而非 Realtime | Realtime 模式的优势（低延迟、声纹）在本场景价值有限；级联管线自由度更高，工具调用和结果汇报更可控 |
| Agent 框架 | **混合方案**：LangChain4j（Agent 层）+ 自建语音管线（参考 Pipecat） | LangChain4j 提供成熟的工具调用和上下文管理，省 3000+ 行自研；语音管线是核心差异化，需自己掌控 |
| 不用 Pipecat 后端 | 砍掉 Pipecat 的 Bus/Worker/Transport 层 | 这些是服务端组件，移动端不需要 |
| 不用 Realtime API | 选择级联而非 OpenAI Realtime / StepFun Realtime | 级联管线中 Agent 状态完全在本地，不受服务端会话限制；长文本结果汇报不会污染会话状态 |
| 不自建完整 Pipeline | 参考 Pipecat 的 Frame/Pipeline 设计思想，用 Kotlin 重写 | 逐行翻译 18000 行 Python 成本太高（6-10 周）；保留设计思想用 Kotlin 惯用写法重写，工作量降 65% |

---

## 二、开源调研结论

### 2.1 语音 Agent 框架对比

| 项目 | 定位 | Stars | 语音 | 任务委派 | 主动汇报 | 状态机 | 移动端 |
|------|------|-------|------|----------|----------|--------|--------|
| Pipecat | 语音框架 | 13k | 级联+Realtime | WorkerBus | 需自行组装 | Flows+Idle | 仅客户端入口 |
| LiveKit Agents | 语音框架 | 3.7k | 级联 | TaskGroup | 需自行触发 | TaskGroup | 无 |
| TEN Framework | 语音框架 | 10.8k | 级联+Realtime | 模块化 | 自定义 | 节点级 | 无 |
| GLaDOS | 语音产品 | 5.6k | 级联 <600ms | MCP工具 | 主动行为 | 情感+多Agent | 纯Linux桌面 |

**结论**：开源社区不存在"移动端独立运行 + 语音交互 + LLM 驱动 + 秘书级 Agent 能力"的完整产品。

### 2.2 移动端 Agent 框架对比

| 项目 | 平台 | 语言 | Stars | 语音 | 工具调用 | 状态管理 | Android可用 |
|------|------|------|-------|------|----------|----------|-------------|
| PhoneClaw | iOS | Swift | 1,118 | ASR+TTS+LIVE | Skill系统 | Router | 仅iOS |
| LangChain4j | JVM | Java/Kotlin | 12,484 | 无 | @Tool | ChatMemory | 有兼容性坑 |
| langchain-swift | iOS | Swift | 归档 | 无 | 有 | Memory | 仅iOS |
| Apple Foundation Models | iOS | Swift | 闭源 | 无 | Tool协议 | Session | 仅iOS |

**结论**：Android 上没有任何成熟的、原生的、带语音的 Agent 框架。LangChain4j Agent 能力最强但无语音，且在 Android 上有已知的枚举/反射兼容性问题。

### 2.3 GLaDOS 架构参考价值

| GLaDOS 特性 | 参考价值 | 如何借鉴 |
|-------------|----------|----------|
| 双通道调度（Priority Lane vs Autonomy Lane） | 高 | 用户语音输入走优先通道，后台结果汇报走自主通道，互不阻塞 |
| Slot 状态共享 | 高 | 子任务通过 Slot 共享状态（pending/running/completed + importance + notify_user） |
| Cooldown + Coalesce | 高 | 两次自主汇报之间的最小间隔（默认 20s），防止队列堆积 |
| 自主决策循环（self-prompting） | 中 | 每个 tick 周期决定是 speak 还是 do_nothing |
| Society of Mind 多 Agent | 中 | 子 Agent 不直接通信，通过 Slot 间接交互 |

GLaDOS 关键文档：
- 自主架构: https://github.com/dnhkng/GLaDOS/blob/main/docs/autonomy.md
- MCP 架构: https://github.com/dnhkng/GLaDOS/blob/main/docs/mcp.md

### 2.4 端侧组件成熟度

| 组件 | 首选方案 | Android支持 | 离线 | 质量 |
|------|----------|-------------|------|------|
| ASR | Sherpa-ONNX (Paraformer) | 完善（预编译AAR，NPU加速） | 是 | 中文优秀 |
| VAD | Silero VAD (ONNX) via sherpa-onnx | 完善 | 是 | 业界标准 |
| LLM | 云端 API（StepFun/OpenAI） | N/A | 否 | 最优 |
| TTS | Sherpa-ONNX (Piper) | 完善（预编译AAR） | 是 | Piper轻量 |

---

## 三、系统架构设计

### 3.1 整体架构

```
+--------------------------------------------------------------+
|                    Android App (Kotlin)                        |
|                                                              |
|  +--------------------------------------------------------+  |
|  |       语音管线层（自研，参考 Pipecat 设计）              |  |
|  |                                                        |  |
|  |  AudioRecord -> VAD(Silero via sherpa-onnx)            |  |
|  |      -> ASR(Sherpa-ONNX Paraformer) -> [Frame Bridge] |  |
|  |  TTS(Sherpa-ONNX Piper) <- [Frame Bridge]              |  |
|  |      <- AudioTrack                                     |  |
|  +------------------------+-------------------------------+  |
|                           | 文本/事件                         |
|  +------------------------v-------------------------------+  |
|  |       Agent 层（LangChain4j）                           |  |
|  |                                                        |  |
|  |  ChatMemory (上下文管理: MessageWindow/TokenWindow)    |  |
|  |  @Tool (工具调用: 天气/搜索/任务委派)                    |  |
|  |  AI Services (LLM 调用: StepFun/OpenAI)               |  |
|  +------------------------+-------------------------------+  |
|  +------------------------v-------------------------------+  |
|  |      汇报策略层（自研，参考 GLaDOS）                     |  |
|  |                                                        |  |
|  |  UserIdleDetector -> ResultQueue (优先级队列)          |  |
|  |  PendingResultReporter (闲时择机汇报)                   |  |
|  |  TaskDispatcher (委派到PC端Agent)                        |  |
|  |  双通道: Priority(用户) / Autonomy(汇报)                |  |
|  +--------------------------------------------------------+  |
+--------------------------------------------------------------+
                          | WebSocket / HTTP
+--------------------------v-----------------------------------+
|                PC 端 Agent (Coordinator)                     |
|  Task List + Worker Pool + 多 Agent 协作                     |
+--------------------------------------------------------------+
```

### 3.2 语音管线层设计（参考 Pipecat）

#### Frame 系统（简化版，约15种核心 Frame）

```kotlin
sealed interface Frame {
    val timestamp: Long
}

// 系统帧（高优先级，不可打断）
sealed class SystemFrame : Frame {
    data object StartFrame : SystemFrame()
    data object EndFrame : SystemFrame()
    data object CancelFrame : SystemFrame()
    data class InterruptionFrame : SystemFrame()
    data object UserStartedSpeakingFrame : SystemFrame()
    data object UserStoppedSpeakingFrame : SystemFrame()
    data object BotStartedSpeakingFrame : SystemFrame()
    data object BotStoppedSpeakingFrame : SystemFrame()
}

// 数据帧（普通优先级，可被打断）
sealed class DataFrame : Frame {
    data class InputAudioRawFrame(val audio: ByteArray) : DataFrame()
    data class OutputAudioRawFrame(val audio: ByteArray) : DataFrame()
    data class TextFrame(val text: String) : DataFrame()
    data class LLMTextFrame(val text: String) : DataFrame()
    data class TTSTextFrame(val text: String) : DataFrame()
    data class TranscriptionFrame(val text: String) : DataFrame()
    data class FunctionCallResultFrame(val result: String) : DataFrame()
}
```

#### FrameProcessor 基类

```kotlin
abstract class FrameProcessor {
    private val inputQueue = Channel<Frame>(Channel.UNLIMITED)
    private var next: FrameProcessor? = null
    private var prev: FrameProcessor? = null

    abstract suspend fun processFrame(frame: Frame, direction: FrameDirection)

    // 清空输入队列（打断时调用）
    open suspend fun cleanup() {
        inputQueue.tryReceive().getOrNull() // drain
    }

    suspend fun pushFrame(frame: Frame, direction: FrameDirection = FrameDirection.DOWNSTREAM) {
        if (direction == FrameDirection.DOWNSTREAM) {
            next?.processFrame(frame, direction)
        } else {
            prev?.processFrame(frame, direction)
        }
    }
}

enum class FrameDirection { UPSTREAM, DOWNSTREAM }
```

#### Pipeline 链式管道

```kotlin
class Pipeline(vararg processors: FrameProcessor) {
    init {
        processors.zipWithNext().forEach { (prev, next) ->
            prev.next = next
            next.prev = prev
        }
    }
}
```

#### 管线流转

```
AudioRecord -> VADProcessor -> ASRProcessor -> LLMProcessor -> TTSProcessor -> AudioTrack
     ^                                                          |
     +--------- InterruptionFrame (用户打断时) -----------------+
```

### 3.3 Agent 层设计（LangChain4j）

#### 工具定义示例

```kotlin
object AgentTools {
    @Tool("查询指定城市的天气")
    fun getWeather(@P("城市名称") city: String): String {
        return "明天$city 晴，最高温度 32 度"
    }

    @Tool("委派后台任务到PC端Agent执行")
    fun dispatchTask(
        @P("任务类型") taskType: String,
        @P("任务描述") description: String
    ): String {
        val taskId = taskDispatcher.submit(taskType, description)
        return """{"task_id":"$taskId","status":"dispatched"}"""
    }
}

val agent = AiServices.builder(Assistant::class.java)
    .chatLanguageModel(model)
    .chatMemory(MessageWindowChatMemory.withMaxMessages(20))
    .tools(AgentTools)
    .systemMessage("你是一个语音助手，用简洁的中文回答。")
    .build()
```

### 3.4 汇报策略层设计（参考 GLaDOS）

#### 双通道 + 闲时汇报

```kotlin
class PendingResultReporter(
    private val llmProcessor: LLMProcessor
) : FrameProcessor() {

    private val resultQueue = PriorityBlockingQueue<PendingResult>()
    @Volatile private var userIdle = false
    @Volatile private var botSpeaking = false
    @Volatile private var reporting = false
    private var lastReportTime = 0L
    private val cooldownMs = 20_000L  // 参考 GLaDOS 默认 20s

    fun enqueueResult(result: PendingResult) {
        resultQueue.put(result)
        // 如果此时用户正好空闲，立即汇报
        if (userIdle && !reporting) tryDrain()
    }

    fun onUserIdle() { userIdle = true; tryDrain() }
    fun onUserStartedSpeaking() { userIdle = false }
    fun onBotStartedSpeaking() { botSpeaking = true }
    fun onBotStoppedSpeaking() { botSpeaking = false; tryDrain() }

    private suspend fun tryDrain() {
        if (resultQueue.isEmpty() || reporting || botSpeaking || !userIdle) return
        if (System.currentTimeMillis() - lastReportTime < cooldownMs) return

        reporting = true
        while (resultQueue.isNotEmpty() && userIdle && !botSpeaking) {
            val result = resultQueue.poll()
            if (result.priority == Priority.URGENT || userIdle) {
                injectResult(result)
                lastReportTime = System.currentTimeMillis()
                while (botSpeaking) delay(100)
            } else {
                resultQueue.put(result)
                break
            }
        }
        reporting = false
    }

    private suspend fun injectResult(result: PendingResult) {
        val reportPrompt = "[系统通知] 后台任务 ${result.taskId} 完成：\n${result.summary}\n请用简洁的语音向用户汇报。"
        pushFrame(DataFrame.TextFrame(reportPrompt), FrameDirection.UPSTREAM)
    }
}

data class PendingResult(
    val taskId: String,
    val summary: String,
    val priority: Priority = Priority.NORMAL
)

enum class Priority(val weight: Int) {
    URGENT(0), NORMAL(10), LOW(20)
}
```

#### 汇报时机的四种场景

| 场景 | 用户状态 | 结果优先级 | 行为 |
|------|----------|-----------|------|
| 闲时普通结果 | 空闲 | NORMAL | 主动汇报，注入 LLM 上下文，生成语音 |
| 忙时普通结果 | 正在说话/听回复 | NORMAL | 排队等候，idle 触发后再汇报 |
| 闲时紧急结果 | 空闲 | URGENT | 立即汇报 |
| 忙时紧急结果 | 正在说话 | URGENT | 打断当前对话，优先汇报 |

---

## 四、Sherpa-ONNX 集成方案

### 4.1 依赖集成

**Maven Central 上没有官方包。** 使用预编译 AAR：

| 文件 | 大小 | 下载地址 |
|------|------|----------|
| sherpa-onnx-1.13.2.aar | 54 MB | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar |

**Gradle 集成方式**：
```kotlin
// 将下载的 .aar 放入 app/libs/ 目录
dependencies {
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    // sherpa-onnx 内部依赖 onnxruntime-android，AAR 已包含
}
```

**架构说明**：AAR 内含 JNI 库（`libsherpa-onnx-jni.so` + `libonnxruntime.so`），无需自己写 NDK 代码。纯 Java/Kotlin API 调用。

### 4.2 模型下载指引

#### 模型存放策略

**决定：首次启动从内网 Gitea 下载到内部存储（不放 assets/）。**

理由：
- ASR 模型 ~220MB + TTS 模型 ~60MB + VAD ~2MB = ~280MB，放 assets 会导致 APK 过大
- 模型更新不需要重新发版
- 首次启动下载到 `context.getExternalFilesDir(null)` 即可

Gitea 已有仓库可复用，或新建一个 model-release 仓库。

#### 模型清单

| 用途 | 模型 | 大小 | 下载地址 | 存放路径（设备端）|
|------|------|------|----------|-------------------|
| ASR (int8 量化) | sherpa-onnx-paraformer-zh-2024-03-09 | ~68MB | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2024-03-09.tar.bz2 | `files/models/paraformer/` |
| TTS (轻量) | vits-piper-zh_CN-huayan-medium | ~60MB | https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-zh_CN-huayan-medium.tar.bz2 | `files/models/piper/` |
| VAD | silero_vad.onnx (k2-fsa 导出) | 629KB | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx | `files/models/vad/` |
| ASR (备选流式) | sherpa-onnx-streaming-paraformer-bilingual-zh-en | ~70MB | https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-paraformer-bilingual-zh-en.tar.bz2 | `files/models/streaming-paraformer/` |

**ASR 选型决定**：先用非流式 Paraformer (int8 量化版, 68MB)，延迟足够低（非自回归模型）。后续如需更低首字延迟，再切换流式版。

**TTS 选型决定**：先用 Piper zh_CN-huayan-medium (60MB) 跑通流程。后续可换 Kokoro 高音质版。

#### 模型文件结构

ASR 模型解压后：
```
sherpa-onnx-paraformer-zh-2024-03-09/
  model.int8.onnx    # 量化版（推荐移动端）
  model.onnx          # fp32版（不推荐）
  tokens.txt          # 词表
```

TTS 模型解压后：
```
vits-piper-zh_CN-huayan-medium/
  zh_CN-huayan-medium.onnx
  tokens.txt
  espeak-ng-data/     # 音素数据（需复制到外部存储加载）
```

### 4.3 ASR 调用方式

#### 非流式识别（OfflineRecognizer）

```kotlin
// 初始化
val config = OfflineRecognizerConfig(
    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
    modelConfig = OfflineModelConfig(
        paraformer = OfflineParaformerModelConfig(
            model = "$modelPath/model.int8.onnx"
        ),
        tokens = "$modelPath/tokens.txt"
    )
)
val recognizer = OfflineRecognizer(config = config)

// 识别
val stream = recognizer.createStream()
stream.acceptWaveform(samples, sampleRate = 16000)
recognizer.decode(stream)
val result = recognizer.getResult(stream).text
stream.release()
```

#### 流式识别（OnlineRecognizer）- 备选

```kotlin
// 初始化
val config = OnlineRecognizerConfig(
    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
    modelConfig = OnlineModelConfig(...),
    endpointConfig = EndpointConfig(...),
    enableEndpoint = true
)
val recognizer = OnlineRecognizer(config = config)

// 实时流式识别
val stream = recognizer.createStream()
val buffer = ShortArray(1600)  // 100ms 一帧
while (isRecording) {
    audioRecord.read(buffer, 0, buffer.size)
    val samples = FloatArray(buffer.size) { buffer[it] / 32768.0f }
    stream.acceptWaveform(samples, sampleRate = 16000)
    while (recognizer.isReady(stream)) {
        recognizer.decode(stream)
    }
    if (recognizer.isEndpoint(stream)) {
        val text = recognizer.getResult(stream).text
        recognizer.reset(stream)
    }
}
```

### 4.4 TTS 调用方式

```kotlin
// 初始化
val config = OfflineTtsConfig(
    model = OfflineTtsModelConfig(
        vits = OfflineTtsVitsModelConfig(
            model = "$modelPath/zh_CN-huayan-medium.onnx",
            tokens = "$modelPath/tokens.txt",
            dataDir = "$externalPath/espeak-ng-data"  // espeak-ng-data 必须在外部存储
        )
    )
)
val tts = OfflineTts(config = config)

// 生成语音（带流式回调播放）
val audio = tts.generateWithConfigAndCallback(
    text = "你好世界",
    config = GenerationConfig(sid = 0, speed = 1.0f),
    callback = { samples, sampleRate ->
        // 用 AudioTrack 实时播放
        audioTrack.write(samples, 0, samples.size)
    }
)
```

**关键点**：Piper 模型的 `espeak-ng-data` 目录需从 assets 或压缩包复制到 `getExternalFilesDir` 后用绝对路径加载。

### 4.5 VAD 调用方式（sherpa-onnx 封装）

**决定：使用 sherpa-onnx 封装的 VAD，不直接用 onnxruntime-android 加载。**

理由：sherpa-onnx 已封装好 VAD 状态机、能量阈值、平滑逻辑，无需自己实现。

```kotlin
// 初始化
val config = SileroVadModelConfig(
    model = "$vadPath/silero_vad.onnx",
    threshold = 0.8f,           // 语音检测阈值
    minSilenceDurationMs = 500,  // 静音确认时长
    speechPadMs = 200           // 语音前后padding
)
val vad = VoiceActivityDetector(config = config)

// 检测
val buffer = ShortArray(512)  // 16kHz 下 32ms 一帧
while (isRecording) {
    audioRecord.read(buffer, 0, buffer.size)
    if (vad.acceptWaveform(buffer)) {
        // 检测到语音结束
        val speechSamples = vad.getSpeechSamples()
        // 发送到 ASR
    }
}
```

---

## 五、工程确认事项

### 5.1 已确认的工程决策

| 问题 | 决定 | 理由 |
|------|------|------|
| 模型存放位置 | **首次启动下载到内部存储** | APK 保持小体积（~20MB），模型放 `getExternalFilesDir`，可从内网 Gitea 下载 |
| LLM 供应商优先级 | **StepFun 优先** | 中文场景优势，API 兼容 OpenAI 格式，`base_url` 切换即可。OpenAI 作为备选 |
| MVP UI | **需要最简 Activity** | 一个 Activity：开始/停止录音按钮 + 文本日志显示区。用于端到端验证 |
| ProGuard 配置 | **骨架阶段就配好** | 按文档 8.1 节处理，排除 LangChain4j 的枚举/反射兼容性问题 |
| ASR 模式 | **非流式 Paraformer (int8)** | 非自回归模型，延迟足够低，68MB 体积可接受。后续可换流式版 |
| TTS 模型 | **Piper zh_CN-huayan-medium** | 60MB 轻量版先跑通流程。后续可换 Kokoro 高音质 |
| VAD 集成 | **sherpa-onnx 封装的 VAD** | 不直接用 onnxruntime-android，sherpa-onnx 已封装好状态机 |
| Sherpa-ONNX AAR | **预编译 AAR (v1.13.2)** | Maven Central 无官方包，使用 GitHub Releases 的 AAR |

### 5.2 ProGuard 规则

```proguard
# proguard-rules.pro

# LangChain4j 反射兼容性
-keep class dev.langchain4j.** { *; }
-keepclassmembers class dev.langchain4j.** {
    public *;
}
# 枚举保护
-keep enum dev.langchain4j.agent.tool.ReturnBehavior { *; }
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
# 工具注解保护
-keep @dev.langchain4j.agent.tool.Tool class * { *; }
-keepclassmembers class * {
    @dev.langchain4j.agent.tool.P *;
}
```

### 5.3 Gradle 配置要点

```properties
# gradle.properties
android.overridePathCheck=true
org.gradle.jvmargs=-Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

### 5.4 一期关键风险预判

| 风险 | 影响 | 应对 |
|------|------|------|
| Sherpa-ONNX AAR 依赖 | AAR 内含 .so 库，需 NDK 27 兼容 | 已安装 NDK 27.2.12479018，AAR 已编译好不需要自己构建 |
| Silero VAD 16kHz 采样 | AudioRecord 必须用 16kHz 单声道 16bit PCM | 硬编码采样参数，与 AudioTrack 采样率分开管理 |
| 打断机制 | InterruptionFrame 需清空 ASR/LLM/TTS 输入队列 | FrameProcessor 添加 cleanup() 钩子，打断时逐级调用 |
| 路径含中文 | Gradle 警告 | `android.overridePathCheck=true` + `-Dfile.encoding=UTF-8` |
| LangChain4j Android 兼容性 | 枚举/反射可能崩溃 | 只用核心模块，ProGuard 规则保护，避开 agentic 实验性模块 |

---

## 六、开发环境

### 6.1 已安装环境（验证通过）

| 组件 | 版本 | 路径 |
|------|------|------|
| JDK (Temurin) | 21.0.11 LTS | C:\jdk21\jdk-21.0.11+10 |
| Android SDK cmdline-tools | 12.0 | C:\android-sdk\cmdline-tools\latest |
| Android SDK platform-tools | 37.0.0 | C:\android-sdk\platform-tools |
| Android SDK build-tools | 34.0.0 + 35.0.0 | C:\android-sdk\build-tools |
| Android Platform | API 34 + API 36 | C:\android-sdk\platforms |
| NDK | 27.2.12479018 | C:\android-sdk\ndk\27.2.12479018 |
| CMake (SDK) | 3.22.1 | C:\android-sdk\cmake\3.22.1 |
| CMake (系统) | 4.3.0-rc1 | C:\Program Files\CMake\bin |
| Gradle 缓存 | ~1GB | C:\Users\mm\.gradle\ |

### 6.2 环境变量（已永久配置）

```
JAVA_HOME = C:\jdk21\jdk-21.0.11+10
ANDROID_HOME = C:\android-sdk
PATH += %JAVA_HOME%\bin;%ANDROID_HOME%\cmdline-tools\latest\bin;%ANDROID_HOME%\platform-tools
```

### 6.3 网络注意事项

- GitHub 访问较慢，Gradle 使用腾讯镜像（mirrors.cloud.tencent.com）
- JDK 下载使用清华镜像（mirrors.tuna.tsinghua.edu.cn）
- 项目路径含中文字符，需在 gradle.properties 中设置 android.overridePathCheck=true

### 6.4 已克隆的 Pipecat 仓库（参考用）

| 仓库 | 本地路径 | Gitea 地址 |
|------|----------|-----------|
| pipecat | d:\solo工作区\pipecat | http://192.168.8.2:8418/agent/pipecat.git |
| pipecat-client-android | d:\solo工作区\pipecat-client-android | http://192.168.8.2:8418/agent/pipecat-client-android.git |
| pipecat-client-android-transports | d:\solo工作区\pipecat-client-android-transports | http://192.168.8.2:8418/agent/pipecat-client-android-transports.git |
| pipecat-examples | d:\solo工作区\pipecat-examples | http://192.168.8.2:8418/agent/pipecat-examples.git |

Gitea 账户：agent / 密码：123456789

### 6.5 缺少的（按需安装）

| 组件 | 何时需要 | 安装命令 |
|------|----------|----------|
| Android Emulator | 需要模拟器测试时 | sdkmanager "emulator" "system-images;android-34;google_apis;x86_64" |
| Android Studio | 需要断点调试/UI预览时 | 从 https://developer.android.com/studio 下载 |

---

## 七、Pipecat 源码关键文件索引

移植时需要参考的核心文件：

| 模块 | 文件路径 | 行数 | 移植优先级 |
|------|----------|------|-----------|
| Frame 系统 | src/pipecat/frames/frames.py | 1,630 | P0 |
| FrameProcessor | src/pipecat/processors/frame_processor.py | 854 | P0 |
| Pipeline | src/pipecat/pipeline/pipeline.py | ~400 | P0 |
| PipelineWorker | src/pipecat/pipeline/pipeline_worker.py | 1,142 | P1（简化版） |
| LLM 上下文聚合 | src/pipecat/processors/aggregators/llm_context.py | 436 | P1 |
| LLM 响应聚合 | src/pipecat/processors/aggregators/llm_response.py | 68 | P1 |
| 句子聚合 | src/pipecat/processors/aggregators/sentence.py | 49 | P1 |
| LLMService 基类 | src/pipecat/services/llm_service.py | 1,592 | P1（参考设计） |
| TTSService 基类 | src/pipecat/services/tts_service.py | 1,534 | P1（参考设计） |
| STTService 基类 | src/pipecat/services/stt_service.py | 904 | P1（参考设计） |
| WebsocketService | src/pipecat/services/websocket_service.py | 252 | P2 |
| 工具框架 | src/pipecat/adapters/schemas/function_schema.py | 120 | P2 |
| 工具框架 | src/pipecat/adapters/schemas/tools_schema.py | 109 | P2 |
| VAD (Silero) | src/pipecat/audio/vad/silero.py | 225 | P0（参考，用sherpa-onnx替代） |
| VAD Controller | src/pipecat/audio/vad/vad_controller.py | 198 | P0 |
| 轮次管理 | src/pipecat/turns/user_turn_controller.py | 325 | P1 |
| 空闲检测 | src/pipecat/turns/user_idle_controller.py | 135 | P0 |
| FlowManager | src/pipecat/flows/manager.py | ~900 | P2 |
| FlowActions | src/pipecat/flows/actions.py | ~200 | P2 |

---

## 八、Python -> Kotlin 映射参考

| Python | Kotlin | 说明 |
|--------|--------|------|
| asyncio.Task | kotlinx.coroutines.Job | 异步任务 |
| asyncio.Event | CompletableDeferred<Unit> | 事件同步 |
| asyncio.Queue | kotlinx.coroutines.channels.Channel | 消息队列 |
| asyncio.PriorityQueue | Channel<Frame> + 手动优先级排序 | 优先级队列 |
| asyncio.sleep | delay() | 延时 |
| asyncio.wait_for | withTimeout() | 超时 |
| asyncio.CancelledError | CancellationException | 取消 |
| AsyncGenerator[Frame] | Flow<Frame> | 流式生成（注意：Flow 是冷流） |
| dataclass | data class / sealed class | 数据类 |
| websockets | OkHttp WebSocket | WebSocket 客户端 |
| onnxruntime | onnxruntime-android AAR | ONNX 推理 |
| loguru | Timber | 日志 |
| inspect | kotlin-reflect | 反射 |
| openai Python SDK | 手写 Retrofit + 流式解析 | LLM API |
| pydantic | Kotlin data class | 数据验证 |

---

## 九、开发计划

### 第一期：MVP（2-3 周）-- 能说话能调工具

| 模块 | 参考来源 | 预估代码量 | 难度 |
|------|----------|-----------|------|
| Frame 系统（简化版，约15种） | Pipecat frames.py | ~800行 | 中 |
| FrameProcessor + Pipeline | Pipecat frame_processor.py | ~1,200行 | 高 |
| VAD 集成（Silero via sherpa-onnx） | sherpa-onnx VAD | ~400行 | 低 |
| ASR 集成 | Sherpa-ONNX Paraformer | ~400行 | 低 |
| TTS 集成 | Sherpa-ONNX Piper | ~400行 | 低 |
| LangChain4j 桥接层 | 自研 | ~600行 | 中 |
| 简单上下文管理 | LangChain4j ChatMemory | ~200行 | 低 |
| 工具框架 | LangChain4j @Tool | ~300行 | 低 |
| Android 音频 I/O | Android AudioRecord/AudioTrack | ~500行 | 中 |
| 最简 UI Activity | 自研 | ~300行 | 低 |
| ProGuard + Gradle 配置 | 自研 | ~100行 | 低 |
| 模型下载器 | 自研 | ~300行 | 中 |
| **合计** | | **~5,000行** | |

**MVP 验收标准**：
- 最简 UI：开始/停止录音按钮 + 文本日志
- 能通过语音与 Agent 对话
- Agent 能调用天气/搜索等简单工具
- 支持用户打断
- LLM 回复能通过 TTS 朗读
- 首次启动自动下载模型

### 第二期：状态机 + 汇报策略（2-3 周）

| 模块 | 参考来源 | 预估代码量 | 难度 |
|------|----------|-----------|------|
| 轮次管理完整版 | Pipecat turns/ | ~1,200行 | 中 |
| UserIdleController | Pipecat idle_controller | ~300行 | 低 |
| 结果队列 + 优先级管理 | 自研 | ~400行 | 中 |
| PendingResultReporter | GLaDOS 双通道 | ~800行 | 中 |
| 紧急打断机制 | 自研 | ~400行 | 中高 |
| TTS 句子聚合 | Pipecat sentence.py | ~600行 | 中 |
| **合计** | | **~3,700行** | |

### 第三期：任务委派到 PC（1-2 周）

| 模块 | 参考来源 | 预估代码量 | 难度 |
|------|----------|-----------|------|
| WebSocket 通信层 | 自研 | ~500行 | 中 |
| 任务委派协议 | 自研 | ~300行 | 低 |
| 结果回传处理 | 自研 | ~400行 | 中 |
| PC端 Coordinator 接口 | 自研 | ~300行 | 低 |
| **合计** | | **~1,500行** | |

### 总计

| 阶段 | 代码量 | 时间 |
|------|--------|------|
| 第一期 MVP | ~5,000行 | 2-3周 |
| 第二期 状态机 | ~3,700行 | 2-3周 |
| 第三期 任务委派 | ~1,500行 | 1-2周 |
| **合计** | **~10,200行** | **5-8周** |

---

## 十、技术约束与风险

| 约束 | 说明 | 应对 |
|------|------|------|
| 移动端后台限制 | Android Doze mode 会限制后台进程 | 使用 Foreground Service 保活；汇报依赖推送通知唤醒 |
| 端侧 LLM 算力不足 | 手机端 LLM（1.5-8B）规划能力弱 | LLM 全部走云端 API，端侧只做 ASR/VAD/TTS |
| LangChain4j Android 兼容性 | 已知枚举/反射兼容性坑 | 只用核心模块（ChatModel + Tool + ChatMemory），避开 agentic 实验性模块；ProGuard 规则保护 |
| 项目路径含中文 | Gradle 可能警告 | gradle.properties 中设置 android.overridePathCheck=true + -Dfile.encoding=UTF-8 |
| Flow 冷流语义 | Kotlin Flow 每次 collect 会重新执行 | 注意生命周期管理，必要时用 StateFlow/SharedFlow |
| Sherpa-ONNX AAR 版本 | AAR 内 .so 库需 NDK 27 兼容 | 已安装 NDK 27.2.12479018 |
| 音频采样率 | VAD/ASR 需 16kHz，TTS 可能不同 | AudioRecord 固定 16kHz 单声道 16bit；AudioTrack 按 TTS 输出采样率 |

---

## 十一、用户偏好与工程约定

### 11.1 用户偏好（来自项目记忆）

| 偏好 | 说明 |
|------|------|
| 沟通语言 | 中文 |
| 设计哲学 | 尽量减少工具调用，关键数据常驻内存便于实时决策 |
| 系统架构 | 偏好关键数据结构常驻加载，用于实时决策 |

### 11.2 工程约定（来自项目记忆）

| 约定 | 说明 |
|------|------|
| 任务报告原子性 | 报告内容和状态更新必须封装在单个事务中 |
| Agent 列表 | 包含 agent ID、capabilities、identity、online status，通过工具调用按需加载 |
| 任务列表显示 | 显示所有 in_progress 任务 + 所有 unreported 任务 + 最新 5 条 reported 任务 |
| 任务状态 | pending, in_progress, completed, failed |
| 任务数据结构 | 必须有 reported boolean 字段和 reported_at timestamp |
| Coordinator 架构 | 常驻加载任务列表摘要 + 按需加载 agent 列表 |
| 中间件 | koa-connect wrapper 曾导致 ctx 泄漏，优先使用原生重写而非 wrapper |

---

## 十二、参考项目链接

### 核心参考

| 项目 | GitHub | 用途 |
|------|--------|------|
| Pipecat | https://github.com/pipecat-ai/pipecat | 语音管线 Frame/Pipeline 设计参考 |
| Pipecat Flows | https://docs.pipecat.ai/guides/features/pipecat-flows | 状态机设计参考 |
| Pipecat Idle 检测 | https://docs.pipecat.ai/guides/fundamentals/detecting-user-idle | 空闲检测参考 |
| GLaDOS | https://github.com/dnhkng/GLaDOS | 双通道调度 + 主动行为 + Slot 状态共享参考 |
| GLaDOS 自主架构文档 | https://github.com/dnhkng/GLaDOS/blob/main/docs/autonomy.md | 双通道设计详解 |
| LangChain4j | https://github.com/langchain4j/langchain4j | Agent 框架（工具调用+上下文管理） |
| LangChain4j Tools 文档 | https://docs.langchain4j.dev/tutorials/tools | @Tool 注解用法 |
| LangChain4j Chat Memory | https://docs.langchain4j.dev/tutorials/chat-memory | 上下文管理 |
| PhoneClaw | https://github.com/kellyvv/PhoneClaw | 移动端语音 Agent 架构参考（iOS/Swift） |

### Sherpa-ONNX

| 资源 | 链接 | 用途 |
|------|------|------|
| GitHub 主仓库 | https://github.com/k2-fsa/sherpa-onnx | 源码 + 文档 |
| AAR 下载 (v1.13.2) | https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.2/sherpa-onnx-1.13.2.aar | Android 集成 |
| ASR 模型 | https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models | Paraformer 等模型 |
| TTS 模型 | https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models | Piper/VITS 等模型 |
| Android ASR 示例 | https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnx2Pass | 2-pass ASR 示例 |
| Android TTS 示例 | https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxTts | TTS 示例 |
| Android VAD 示例 | https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxVad | VAD 示例 |
| Silero VAD 文档 | https://k2-fsa.github.io/sherpa/onnx/vad/silero-vad.html | VAD 模型说明 |

### 端侧组件

| 组件 | GitHub | 用途 |
|------|--------|------|
| ONNX Runtime Android | https://github.com/microsoft/onnxruntime | ONNX 模型推理（sherpa-onnx AAR 已内含） |
| Whisper.cpp | https://github.com/ggml-org/whisper.cpp | 备选 ASR |
| Piper TTS | https://github.com/rhasspy/piper | 备选 TTS |

### LLM API

| 服务 | 用途 | 备注 |
|------|------|------|
| StepFun | 中文场景优先 | OpenAI 兼容 API，base_url 可切换 |
| OpenAI GPT-4o | 备选 | 工具调用原生支持 |

---

## 十三、下一步行动

重置上下文后，按以下顺序执行：

1. **创建 Android Kotlin 项目骨架** -- Gradle 配置、依赖引入（sherpa-onnx AAR + LangChain4j + OkHttp）、基础目录结构、ProGuard 规则
2. **实现 Frame 系统** -- 参考 Pipecat frames.py，用 Kotlin sealed class 实现约15种核心 Frame
3. **实现 FrameProcessor + Pipeline** -- 参考本文档第 3.2 节的设计，包含 cleanup() 钩子
4. **集成 Sherpa-ONNX** -- 下载 AAR 放入 libs/，编写模型下载器（首次启动从 Gitea 下载到内部存储）
5. **集成 VAD** -- 使用 sherpa-onnx 的 VoiceActivityDetector
6. **集成 ASR** -- 使用 sherpa-onnx 的 OfflineRecognizer (Paraformer int8)
7. **集成 TTS** -- 使用 sherpa-onnx 的 OfflineTts (Piper zh_CN-huayan-medium)
8. **集成 LangChain4j** -- 配置 StepFun API，实现 @Tool 工具，ChatMemory 上下文
9. **实现最简 UI** -- 一个 Activity：录音按钮 + 文本日志
10. **验证端到端流程** -- 语音输入 -> VAD -> ASR -> LLM(工具调用) -> TTS -> 语音输出

---

## 十四、学术参考

| 论文/项目 | 机构 | 价值 |
|-----------|------|------|
| ProactiveAgent | 清华大学 THUNLP + 面壁智能 | 主动式 Agent 范式：主动推断->提出任务->等待用户接受->执行 |
| ProAct | 上海交通大学 | 主动决策协议 |

GitHub: https://github.com/thunlp/proactive-agent

---

> **文档结束。重置上下文后，将本文档内容提供给新会话即可恢复全部上下文。**
> 
> 文档路径: d:\solo工作区\voice-agent-task-spec.md
