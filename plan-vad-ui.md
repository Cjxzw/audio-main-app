# VAD 修复 + UI 调整 实现方案

> 创建日期：2026-07-02

---

## 一、总览

| 模块 | 改动 | 涉及文件 |
|------|------|----------|
| VAD 修复 | 移植 Pipecat 三重防护模式 | `VADProcessor.kt` |
| UI 布局重构 | 日志缩小、聊天列表、语音条 | `MainActivity.kt` + `activity_main.xml` + 新文件 |
| 聊天历史 | 用户/助手对话记录展示 | 新增 `ChatMessage.kt` + `ChatAdapter.kt` + `item_chat_message.xml` |
| 语音音量条 | 实时显示录音 RMS | 新增 `VoiceBarView.kt` + AudioInputProcessor 发射音量事件 |
| EventBus 扩展 | 新增 flow 类型 | `EventBus.kt` |
| 日志补充 | 全链路调试日志 | 多处 |

---

## 二、Part 1：VAD 修复（移植 Pipecat 三重防护）

### 2.1 问题根因

当前 `VADProcessor.kt` 只有单门控（模型 threshold=0.5）+ 布尔 `inSpeech`。Bot 播报时喇叭声被麦克风拾取，VAD 误判为用户说话 → 触发 `InterruptionFrame` → 打断自身回复。

### 2.2 三重防护设计

```
音频帧进入
    │
    ▼
[① Bot 播报保护] botSpeaking=true → 跳过 VAD，不检测用户语音
    │ botSpeaking=false
    ▼
[② 音量预过滤] smoothedVolume < VOLUME_GATE → 视为静音，跳过 VAD
    │   (RMS 门控，过滤残留回声/底噪)
    ▼
[③ 四状态去抖] QUIET→STARTING→SPEAKING→STOPPING，需连续 N 帧确认
```

对应 Pipecat 代码（`d:\solo工作区\pipecat\src\pipecat\audio\vad\`）：
- `silero.py::_get_smoothed_volume()` → 指数平滑
- `_voice_confidence` + `_voice_volume` 双门控
- `_VoiceProbTracker` 四状态状态机

### 2.3 VADProcessor.kt 全量重写

```kotlin
package com.agent.voiceassistant.pipeline.processors

import com.agent.voiceassistant.audio.AudioConfig
import com.agent.voiceassistant.model.ModelPaths
import com.agent.voiceassistant.pipeline.FrameProcessor
import com.agent.voiceassistant.pipeline.frames.DataFrame
import com.agent.voiceassistant.pipeline.frames.Frame
import com.agent.voiceassistant.pipeline.frames.FrameDirection
import com.agent.voiceassistant.pipeline.frames.SystemFrame
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sqrt

/**
 * VADProcessor：移植 Pipecat 三重防护模式。
 *
 * 三重防护（依次生效）：
 * 1. Bot 播报保护 — botSpeaking=true 时直接跳过，喇叭回声不触发 VAD
 * 2. 音量预过滤 — smoothedVolume < VOLUME_GATE 时跳过 VAD，过滤底噪
 * 3. 四状态去抖   — QUIET→STARTING(6帧)→SPEAKING→STOPPING(6帧)→QUIET
 *
 * 关键常量对应 Pipecat：
 * - VOLUME_SMOOTH_FACTOR = 0.2f（指数平滑因子）
 * - VOLUME_GATE = 0.02f（RMS 静音门控，~ -34dB）
 * - START_FRAMES = 6（连续 6 帧=192ms 确认语音起始）
 * - STOP_FRAMES = 6（连续 6 帧=192ms 确认语音结束）
 */
class VADProcessor(
    private val paths: ModelPaths,
    private val assetManager: AssetManager? = null,
    private val threshold: Float = 0.5f,
    private val minSilenceDurationSec: Float = 0.4f,   // 稍放宽，减少误判
    private val minSpeechDurationSec: Float = 0.25f,
    private val maxSpeechDurationSec: Float = 20.0f,
) : FrameProcessor() {

    companion object {
        private const val VOLUME_SMOOTH_FACTOR = 0.2f
        private const val VOLUME_GATE = 0.02f          // RMS 静音门控
        private const val START_FRAMES = 6              // 0.2s / 32ms ≈ 6
        private const val STOP_FRAMES = 6               // 0.2s / 32ms ≈ 6
    }

    private var vad: Vad? = null
    private val botSpeaking = AtomicBoolean(false)
    private val inSpeech = AtomicBoolean(false)

    // 音量平滑
    private var smoothedVolume = 0.0f

    // 四状态去抖
    private var vadState = VadState.QUIET
    private var consecutiveFrames = 0

    private val framesReceived = AtomicLong(0)
    private val framesByPassed = AtomicLong(0)  // 被音量过滤跳过的帧
    private val segmentsOutput = AtomicLong(0)

    private val _userSpeaking = MutableStateFlow(false)
    val userSpeaking = _userSpeaking.asStateFlow()

    enum class VadState { QUIET, STARTING, SPEAKING, STOPPING }

    override suspend fun processFrame(frame: Frame, direction: FrameDirection) {
        when (frame) {
            is SystemFrame.StartFrame -> initVad()
            is SystemFrame.EndFrame, is SystemFrame.CancelFrame -> releaseVad()
            is DataFrame.InputAudioRawFrame -> processAudio(frame)
            is SystemFrame.BotStartedSpeakingFrame -> {
                botSpeaking.set(true)
                Timber.i("VAD: botSpeaking=true, protect mode ON")
            }
            is SystemFrame.BotStoppedSpeakingFrame -> {
                botSpeaking.set(false)
                Timber.i("VAD: botSpeaking=false, protect mode OFF")
            }
            is SystemFrame.InterruptionFrame -> {
                resetStateMachine()
            }
            is SystemFrame -> Unit
            else -> pushFrame(frame, direction)
        }
    }

    private fun initVad() { /* 同旧实现，省略 */ }

    private fun releaseVad() { /* 同旧实现，省略 */ }

    private suspend fun processAudio(frame: DataFrame.InputAudioRawFrame) {
        val detector = vad ?: return
        val frameNum = framesReceived.incrementAndGet()
        if (frame.samples.size < AudioConfig.VAD_FRAME_SAMPLES) return

        // ─── 防护 ① Bot 播报保护 ───
        if (botSpeaking.get()) {
            Timber.d("VAD#$frameNum: botSpeaking, skip VAD")
            return
        }

        // ─── 防护 ② 音量预过滤 ───
        val rms = computeRms(frame.samples)
        smoothedVolume = smoothedVolume + VOLUME_SMOOTH_FACTOR * (rms - smoothedVolume)

        if (smoothedVolume < VOLUME_GATE) {
            framesByPassed.incrementAndGet()
            // 音量极低 → 直接当作静音，走状态机的「不达标」分支
            onVadResult(frameNum, speechDetected = false)
            Timber.d("VAD#$frameNum: low volume (${"%.4f".format(smoothedVolume)} < $VOLUME_GATE), skip VAD call")
            return
        }

        // ─── 正常 VAD 推理 ───
        val speechNow = try {
            detector.acceptWaveform(frame.samples)
            detector.isSpeechDetected()
        } catch (e: Throwable) {
            Timber.e(e, "VAD#$frameNum: native call failed")
            return
        }

        onVadResult(frameNum, speechNow)

        // 取出已结束的语音段（保持不变）
        processSegment(detector, frameNum)
    }

    /**
     * 四状态去抖状态机
     */
    private suspend fun onVadResult(frameNum: Int, speechDetected: Boolean) {
        when (vadState) {
            VadState.QUIET -> {
                if (speechDetected) {
                    vadState = VadState.STARTING
                    consecutiveFrames = 1
                    Timber.d("VAD#$frameNum: QUIET→STARTING (1/$START_FRAMES)")
                }
            }
            VadState.STARTING -> {
                if (speechDetected) {
                    consecutiveFrames++
                    if (consecutiveFrames >= START_FRAMES) {
                        vadState = VadState.SPEAKING
                        consecutiveFrames = 0
                        Timber.i("VAD#$frameNum: STARTING→SPEECH CONFIRMED ($START_FRAMES frames)")
                        emitUserStartedSpeaking()
                    } else {
                        Timber.d("VAD#$frameNum: STARTING ($consecutiveFrames/$START_FRAMES)")
                    }
                } else {
                    // 未达标 → 回退
                    vadState = VadState.QUIET
                    consecutiveFrames = 0
                    Timber.d("VAD#$frameNum: STARTING→QUIET (reset, missed $consecutiveFrames frames)")
                }
            }
            VadState.SPEAKING -> {
                if (!speechDetected) {
                    vadState = VadState.STOPPING
                    consecutiveFrames = 1
                    Timber.d("VAD#$frameNum: SPEAKING→STOPPING (1/$STOP_FRAMES)")
                }
            }
            VadState.STOPPING -> {
                if (speechDetected) {
                    // 恢复说话
                    vadState = VadState.SPEAKING
                    consecutiveFrames = 0
                    Timber.d("VAD#$frameNum: STOPPING→SPEAKING (recovered)")
                } else {
                    consecutiveFrames++
                    if (consecutiveFrames >= STOP_FRAMES) {
                        vadState = VadState.QUIET
                        consecutiveFrames = 0
                        Timber.i("VAD#$frameNum: STOPPING→SPEECH END CONFIRMED ($STOP_FRAMES frames)")
                        emitUserStoppedSpeaking()
                    } else {
                        Timber.d("VAD#$frameNum: STOPPING ($consecutiveFrames/$STOP_FRAMES)")
                    }
                }
            }
        }
    }

    private suspend fun emitUserStartedSpeaking() {
        if (inSpeech.compareAndSet(false, true)) {
            _userSpeaking.value = true
            pushFrame(SystemFrame.UserStartedSpeakingFrame, FrameDirection.DOWNSTREAM)
            Timber.i("VAD: ★ UserStartedSpeaking EMITTED")
        }
    }

    private suspend fun emitUserStoppedSpeaking() {
        if (inSpeech.compareAndSet(true, false)) {
            _userSpeaking.value = false
            pushFrame(SystemFrame.UserStoppedSpeakingFrame, FrameDirection.DOWNSTREAM)
            Timber.i("VAD: ★ UserStoppedSpeaking EMITTED")
        }
    }

    private fun computeRss(samples: FloatArray): Float {
        var sumSq = 0.0
        for (s in samples) sumSq += s * s
        return sqrt(sumSq / samples.size).toFloat()
    }

    private fun resetStateMachine() {
        vadState = VadState.QUIET
        consecutiveFrames = 0
        smoothedVolume = 0.0f
        inSpeech.set(false)
        _userSpeaking.value = false
        Timber.i("VAD: state machine reset by interruption")
    }

    private suspend fun processSegment(detector: Vad, frameNum: Int) {
        try {
            if (!detector.empty()) {
                val seg = detector.front()
                detector.pop()
                segmentsOutput.incrementAndGet()
                val ms = seg.samples.size * 1000L / AudioConfig.INPUT_SAMPLE_RATE
                Timber.i("VAD#$frameNum: segment output (${seg.samples.size} samples / ${ms}ms)")
                val wasInSpeech = inSpeech.get()
                // 注意：分段期间不改变 inSpeech 状态（状态机由 isSpeechDetected 驱动）
                pushFrame(
                    DataFrame.InputAudioRawFrame(seg.samples, AudioConfig.INPUT_SAMPLE_RATE),
                    FrameDirection.DOWNSTREAM,
                )
            }
        } catch (e: Throwable) {
            Timber.e(e, "VAD#$frameNum: segment processing failed")
        }
    }

    override suspend fun cleanup() {
        super.cleanup()
        resetStateMachine()
        try { vad?.clear() } catch (_: Throwable) {}
        Timber.i("VAD: cleanup done (frames=${framesReceived.get()}, bypassed=${framesByPassed.get()}, segments=${segmentsOutput.get()})")
    }
}
```

### 2.4 关键日志点

| 场景 | 日志级别 | 消息示例 |
|------|----------|----------|
| 音量过滤 | D | `VAD#42: low volume (0.0083 < 0.02), skip VAD call` |
| 状态机 I→S | D | `VAD#42: STARTING (3/6)` |
| 语音确认 | I | `VAD#42: ★ STARTING→SPEECH CONFIRMED` |
| 语音结束 | I | `VAD#42: ★ STOPPING→SPEECH END CONFIRMED` |
| Bot 保护 | I | `VAD: botSpeaking=true, protect mode ON` |
| 心跳 | I | `VAD heartbeat: 42 frames, 3 bypassed, 1 segments` |

---

## 三、Part 2：UI 布局重构

### 3.1 新布局结构

```
┌─────────────────────────────────────┐
│ tvStatus (状态栏, h=wrap)            │
│ tvPendingCount (待汇报, h=wrap)      │
├──────────────────┬──────────────────┤
│ tvVolume (音量条) │ VoiceBar (语音条) │  ← 48dp 高
├──────────────────┴──────────────────┤
│ rvChat (聊天列表, weight=1)           │  ← 占据主体，占满剩余空间
│  ┌──────────────────────────────┐   │
│  │ [你] 帮我查一下明天北京天气     │   │
│  │ [小助] 明天北京多云 22-30°C    │   │
│  └──────────────────────────────┘   │
├─────────────────────────────────────┤
│ svLog (日志, 固定 120dp, 可滚动)     │  ← 缩小日志，固定高度
├─────────────────────────────────────┤
│ btnToggle          btnClear          │  ← 底部按钮
└─────────────────────────────────────┘
```

### 3.2 activity_main.xml 重写

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:padding="@dimen/margin_md">

    <!-- 状态显示 -->
    <TextView
        android:id="@+id/tvStatus"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/status_idle"
        android:textSize="@dimen/text_status"
        android:textStyle="bold"
        android:textColor="@color/purple_700"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <TextView
        android:id="@+id/tvPendingCount"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:text="@string/pending_zero"
        android:textSize="@dimen/text_status"
        android:textColor="@color/gray_300"
        android:layout_marginTop="@dimen/margin_sm"
        app:layout_constraintTop_toBottomOf="@id/tvStatus"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- 语音音量条 -->
    <TextView
        android:id="@+id/tvVolumeLabel"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="录音音量:"
        android:textSize="@dimen/text_log"
        android:layout_marginTop="@dimen/margin_sm"
        app:layout_constraintTop_toBottomOf="@id/tvPendingCount"
        app:layout_constraintStart_toStartOf="parent" />

    <com.agent.voiceassistant.ui.VoiceBarView
        android:id="@+id/voiceBar"
        android:layout_width="0dp"
        android:layout_height="24dp"
        android:layout_marginStart="@dimen/margin_sm"
        app:layout_constraintTop_toTopOf="@id/tvVolumeLabel"
        app:layout_constraintBottom_toBottomOf="@id/tvVolumeLabel"
        app:layout_constraintStart_toEndOf="@id/tvVolumeLabel"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- 聊天记录列表 -->
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/rvChat"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:layout_marginTop="@dimen/margin_sm"
        android:background="@color/white"
        android:padding="@dimen/margin_sm"
        android:clipToPadding="false"
        app:layout_constraintTop_toBottomOf="@id/tvVolumeLabel"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@id/svLog" />

    <!-- 日志区（缩小，固定高度） -->
    <ScrollView
        android:id="@+id/svLog"
        android:layout_width="0dp"
        android:layout_height="120dp"
        android:layout_marginTop="@dimen/margin_sm"
        android:background="@color/gray_300"
        android:padding="@dimen/margin_sm"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent"
        app:layout_constraintBottom_toTopOf="@id/btnToggle">

        <TextView
            android:id="@+id/tvLog"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="@dimen/text_log"
            android:fontFamily="monospace"
            android:textColor="@color/black"
            android:textIsSelectable="true" />
    </ScrollView>

    <!-- 按钮区 -->
    <Button
        android:id="@+id/btnToggle"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginEnd="4dp"
        android:text="@string/btn_start"
        android:textSize="@dimen/text_button"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toStartOf="@id/btnClear" />

    <Button
        android:id="@+id/btnClear"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_marginStart="4dp"
        android:text="@string/btn_clear"
        android:backgroundTint="@color/gray_300"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toEndOf="@id/btnToggle"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

### 3.3 dimens.xml 新增

```xml
<dimen name="voice_bar_height">24dp</dimen>
<dimen name="voice_bar_bar_width">3dp</dimen>
<dimen name="voice_bar_bar_gap">2dp</dimen>
```

---

## 四、Part 3：聊天历史

### 4.1 数据类 ChatMessage.kt

```kotlin
package com.agent.voiceassistant.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ChatRole { USER, BOT, SYSTEM }

data class ChatMessage(
    val role: ChatRole,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
) {
    val timeStr: String
        get() = SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(Date(timestamp))
}
```

### 4.2 布局文件 item_chat_message.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:layout_marginVertical="4dp"
    android:gravity="end"
    android:id="@+id/llBubble">

    <TextView
        android:id="@+id/tvRole"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="12sp"
        android:textColor="@color/gray_500"
        android:paddingHorizontal="12dp" />

    <TextView
        android:id="@+id/tvText"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="2dp"
        android:background="@drawable/bubble_user"
        android:padding="12dp"
        android:maxWidth="280dp"
        android:textSize="15sp"
        android:textColor="@color/white" />

    <TextView
        android:id="@+id/tvTime"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:textSize="10sp"
        android:textColor="@color/gray_400"
        android:paddingHorizontal="12dp"
        android:layout_marginTop="2dp" />

</LinearLayout>
```

### 4.3 drawable/bubble_user.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#4CAF50" />
    <corners
        android:topLeftRadius="16dp"
        android:topRightRadius="4dp"
        android:bottomLeftRadius="16dp"
        android:bottomRightRadius="16dp" />
</shape>
```

### 4.4 drawable/bubble_bot.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#E0E0E0" />
    <corners
        android:topLeftRadius="4dp"
        android:topRightRadius="16dp"
        android:bottomLeftRadius="16dp"
        android:bottomRightRadius="16dp" />
</shape>
```

### 4.5 类 ChatAdapter.kt

```kotlin
package com.agent.voiceassistant.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.agent.voiceassistant.R
import com.agent.voiceassistant.ui.ChatRole.BOT
import com.agent.voiceassistant.ui.ChatRole.SYSTEM
import com.agent.voiceassistant.ui.ChatRole.USER

class ChatAdapter : RecyclerView.Adapter<ChatAdapter.VH>() {

    private val messages = mutableListOf<ChatMessage>()
    private val maxMessages = 100  // 防止内存溢出

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        if (messages.size > maxMessages) {
            messages.removeAt(0)
            notifyItemRemoved(0)
        }
        notifyItemInserted(messages.size - 1)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(messages[position])

    override fun getItemCount() = messages.size

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val llBubble = view.findViewById<LinearLayout>(R.id.llBubble)
        private val tvRole = view.findViewById<TextView>(R.id.tvRole)
        private val tvText = view.findViewById<TextView>(R.id.tvText)
        private val tvTime = view.findViewById<TextView>(R.id.tvTime)

        fun bind(msg: ChatMessage) {
            val ctx = itemView.context
            when (msg.role) {
                USER -> {
                    llBubble.gravity = android.view.Gravity.END
                    tvRole.text = "我"
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_user)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                }
                BOT -> {
                    llBubble.gravity = android.view.Gravity.START
                    tvRole.text = "小助"
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_bot)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.black))
                }
                SYSTEM -> {
                    llBubble.gravity = android.view.Gravity.CENTER
                    tvRole.text = "系统"
                    tvText.background = ContextCompat.getDrawable(ctx, R.drawable.bubble_system)
                    tvText.setTextColor(ContextCompat.getColor(ctx, android.R.color.white))
                }
            }
            tvText.text = msg.text
            tvTime.text = msg.timeStr
        }
    }
}
```

### 4.6 如何触发聊天消息发射

在 `LLMProcessor.kt` 中：

```kotlin
// handleTranscription() 用户语音识别结果 → 发射 USER 消息
private suspend fun handleTranscription(frame: DataFrame.TranscriptionFrame) {
    if (!frame.isFinal) return
    // 发射用户聊天消息
    EventBus.emitChatMessage(ChatMessage(ChatRole.USER, frame.text))
    // ... 原有代码
}

// chat 响应返回后 → 发射 BOT 消息
EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, response))

// inject 注入返回后 → 发射 BOT 消息
EventBus.emitChatMessage(ChatMessage(ChatRole.BOT, response))
```

---

## 五、Part 4：语音音量条

### 5.1 VoiceBarView.kt（自定义 View）

```kotlin
package com.agent.voiceassistant.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.agent.voiceassistant.R
import kotlin.math.roundToInt

class VoiceBarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.gray_200)
    }

    private val barCount = 24
    private var level = 0.0f  // 0.0 ~ 1.0

    private val barWidth = resources.getDimension(R.dimen.voice_bar_bar_width)
    private val barGap = resources.getDimension(R.dimen.voice_bar_bar_gap)

    fun setLevel(newLevel: Float) {
        level = newLevel.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val activeCount = (level * barCount).roundToInt()
        for (i in 0 until barCount) {
            val left = i * (barWidth + barGap)
            val right = left + barWidth
            if (i < activeCount) {
                // 颜色渐变：低→绿，中→黄，高→红
                barPaint.color = when {
                    i < barCount * 0.5 -> 0xFF4CAF50.toInt()
                    i < barCount * 0.8 -> 0xFFFFC107.toInt()
                    else -> 0xFFF44336.toInt()
                }
                canvas.drawRoundRect(left, 0f, right, height.toFloat(), 2f, 2f, barPaint)
            } else {
                canvas.drawRoundRect(left, 0f, right, height.toFloat(), 2f, 2f, bgPaint)
            }
        }
    }
}
```

### 5.2 发射音量事件

**AudioInputProcessor.kt** — 节流器控制发射频率，避免 UI 过载：

```kotlin
// 在录音循环中已有 RMS 计算，新增：
private var lastVolumeEmit = 0L

// 在每秒统计块内新增：
val now = System.currentTimeMillis()
if (now - lastVolumeEmit >= 100) {  // 10Hz 更新 UI，避免过度刷新
    EventBus.emitVolume(rms.toFloat())
    lastVolumeEmit = now
}
```

---

## 六、Part 5：EventBus 扩展

```kotlin
object EventBus {
    // ... 现有字段 ...

    // 实时录音音量（0.0 ~ 1.0）
    private val _volumeEvents = MutableSharedFlow<Float>(
        replay = 1, extraBufferCapacity = 8
    )
    val volumeEvents: SharedFlow<Float> = _volumeEvents.asSharedFlow()

    // 聊天消息
    private val _chatMessages = MutableSharedFlow<ChatMessage>(
        replay = 0, extraBufferCapacity = 64
    )
    val chatMessages: SharedFlow<ChatMessage> = _chatMessages.asSharedFlow()

    fun emitVolume(level: Float) = _volumeEvents.tryEmit(level)
    fun emitChatMessage(msg: ChatMessage) = _chatMessages.tryEmit(msg)
}
```

---

## 七、Part 6：MainActivity.kt 改造

```kotlin
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val chatAdapter = ChatAdapter()
    private val logBuilder = StringBuilder()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.CHINA)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // RecyclerView 初始化
        binding.rvChat.apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true  // 自动滚到底部
            }
            adapter = chatAdapter
            itemAnimator = DefaultItemAnimator().apply { addDuration = 150 }
        }

        // 按钮监听（同旧实现，改为使用 binding.btnToggle / btnClear）
        binding.btnToggle.setOnClickListener { ... }
        binding.btnClear.setOnClickListener { ... }

        observeEventBus()
    }

    private fun observeEventBus() {
        // 日志（同旧实现）
        lifecycleScope.launch { EventBus.logs.collectLatest { appendLog(it.message) } }
        // 状态（同旧实现）
        lifecycleScope.launch { EventBus.states.collectLatest { updateStateDisplay(it) } }
        // 待汇报计数（同旧实现）
        lifecycleScope.launch { EventBus.pendingCounts.collectLatest { binding.tvPendingCount.text = "待汇报: $it" } }
        // 聊天消息 ← 新增
        lifecycleScope.launch {
            EventBus.chatMessages.collectLatest { msg ->
                chatAdapter.addMessage(msg)
                binding.rvChat.scrollToPosition(chatAdapter.itemCount - 1)
            }
        }
        // 音量条 ← 新增
        lifecycleScope.launch {
            EventBus.volumeEvents.collectLatest { level ->
                binding.voiceBar.setLevel(level)
                Timber.v("Volume UI: ${"%.4f".format(level)}")
            }
        }
    }

    private fun appendLog(msg: String) {
        // 同旧实现，写入 logBuilder + 滚动到底部
        // 同时输出到 Timber.d 以便文件日志保存
        Timber.d("UI_LOG: $msg")
    }
}
```

---

## 八、文件变更汇总

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `pipeline/processors/VADProcessor.kt` | 三重防护重写 |
| 修改 | `MainActivity.kt` | 聊天列表 + 音量条订阅 |
| 修改 | `pipeline/processors/LLMProcessor.kt` | 发射 USER/BOT 聊天消息 |
| 修改 | `service/EventBus.kt` | 新增 volumeEvents + chatMessages |
| 修改 | `res/layout/activity_main.xml` | 新布局：聊天列表 + 音量条 + 缩小日志 |
| 新增 | `ui/ChatMessage.kt` | 数据类 + 角色枚举 |
| 新增 | `ui/ChatAdapter.kt` | RecyclerView.Adapter |
| 新增 | `ui/VoiceBarView.kt` | 自定义音量条 View |
| 新增 | `res/layout/item_chat_message.xml` | 聊天气泡 |
| 新增 | `res/drawable/bubble_user.xml` | 用户聊天气泡（绿色） |
| 新增 | `res/drawable/bubble_bot.xml` | 底 Hir 聊天气泡（灰色） |
| 新增 | `res/drawable/bubble_system.xml` | 系统消息气泡（蓝色） |
| 修改 | `res/values/dimens.xml` | 新增语音条尺寸 |
| 修改 | `res/values/colors.xml` |（若无）补充 gray_200, gray_400, gray_500 |

---

## 九、构建与验证步骤

```bash
# 1. 清理 + 全量编译
cd d:\安卓语音agent
rd /s /q app\build
java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain assembleDebug --no-build-cache --rerun-tasks

# 2. 卸载旧版本 + 安装
adb uninstall com.agent.voiceassistant
adb install -t app\build\outputs\apk\debug\app-debug-ort1171.apk

# 3. 启动 + 抓日志
adb shell am start -n com.agent.voiceassistant/.MainActivity
adb shell run-as com.agent.voiceassistant cat files/voice-agent.log | findstr "VAD"

# 4. 验证 VAD 修复
# - 启动 Agent 后与助手对话
# - Bot 播报时观察日志是否有 "botSpeaking, skip VAD"
# - 观察音量条是否在安静时全灭、说话时跳动
# - 验证 Bot 不再自打断

# 5. 验证 UI
# - 聊天列表应实时显示用户和助手的对话
# - 日志区缩小为固定 120dp
# - 音量条在语音输入时动态变化
```

---

## 十、风险点

| 风险 | 缓解 |
|------|------|
| VAD 去抖延迟（192ms）影响实时感知 | START_FRAMES/STOP_FRAMES 可调，先跑再测 |
| 音量门控阈值 VOLUME_GATE=0.02 需要实测调参 | 日志输出 smoothedVolume，可 adb 抓取后分析分布 |
| RecyclerView 频繁更新（~30fps 音量） | EventBus 限频 10Hz，聊天消息不受影响 |
| APK 体积增大（新增 3 个 drawable + 布局） | 可忽略，< 1KB |
