# Audio Main App

Audio Main App 是一个 Android 端语音 Agent 应用原型，目标是做成可通过手机、蓝牙耳机或智能音频眼镜随时唤醒的随身秘书。

当前版本已跑通语音闭环、持久会话、本地工具和原生函数调用。复杂执行任务和主动汇报将在接入 Hub 后继续完善。

## 当前能力

- 使用 Android `ForegroundService` 保持后台运行。
- 使用 `AudioRouteManager` 优先选择可用的外部通信设备，并为 `AudioRecord`、`AudioTrack` 设置首选设备；无外设时回退手机音频。
- 使用 MiMo 云端 ASR 将录音 WAV 转文字。
- 使用 MiMo/OpenAI 兼容 LLM 接口进行流式对话。
- 使用 MiMo 云端流式 TTS 按句播放回复。
- TTS SSE 只解析真实音频字段，忽略文本预览和用量等控制事件；PCM 使用边界淡入淡出，避免首尾爆音。
- 使用原生 `tool_calls`、JSON Schema 和 `role=tool` 运行多轮 AgentLoop。
- 支持记忆、定位、天气和网络搜索工具。
- 会话与本地记忆持久化，支持 `/new` 开启新话题。
- 每个用户回合默认关闭深度思考；模型或用户可为当前回合升级一次，下一回合自动恢复关闭。
- 使用 `MediaSessionCompat` 接收播放/暂停类控制，作为唤醒和休眠入口。
- 支持息屏后台继续响应。
- 本地 ASR/TTS 模型资产已从当前构建链路移除，避免 APK 过大。

## 环境要求

- Windows 或 macOS/Linux
- JDK 17
- Android SDK 34
- Android Studio 或 Gradle 命令行
- Android 8.0 及以上设备
- 可用的 MiMo/OpenAI 兼容 API Key

## 配置

在项目根目录创建 `.env`，或在 `local.properties` 中写入同名配置：

```properties
LLM_API_KEY=你的 API Key
LLM_BASE_URL=https://token-plan-cn.xiaomimimo.com/v1
LLM_MODEL=mimo-v2.5
```

仓库中提供 `.env.example` 作为模板。不要提交 `.env` 或 `local.properties`。

## 编译

```powershell
.\gradlew.bat :app:assembleDebug
```

生成的调试 APK：

```text
app/build/outputs/apk/debug/app-debug-ort1171.apk
```

## 安装

确认手机已连接：

```powershell
adb devices
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\app-debug-ort1171.apk
```

## 使用

1. 打开 App。
2. 授权麦克风权限。
3. 点击启动/唤醒 Agent。
4. 说话后等待识别、回复和播报。
5. 点击休眠或使用媒体播放/暂停控制让 Agent 进入休眠。

休眠状态会停止收音，但服务仍保留在后台，后续用于接收后端任务结果和主动汇报。

## 主要代码结构

```text
app/src/main/java/com/agent/voiceassistant/
├── service/VoiceAgentService.kt        # 当前主运行循环、唤醒休眠、TTS 播放
├── cloud/CloudSpeechClient.kt          # MiMo ASR / LLM / TTS 调用
├── cloud/SimpleVadRecorder.kt          # 轻量录音端点检测与 WAV 生成
├── audio/AudioRouteManager.kt          # 外部音频设备选择、路由和释放
├── ui/                                # 主界面、聊天列表、音量条
├── agent/                             # Agent 提示词、逐回合推理策略和旧兼容封装
├── tools/                             # 原生工具注册表和本地工具执行器
├── pipeline/                          # 早期管线骨架，目前不是主路径
└── report/                            # 主动汇报相关早期模型
```

## 当前限制

- Hub 工具尚未接入新工具注册表，当前 `CONNECTED` Profile 只预留结构。
- 主动汇报相关早期模型尚未接入当前 AgentLoop。
- 语音打断和完整用户状态机尚未实现。
- 自管理 Telecom 与第三方 VoIP 的蓝牙路由兼容性仍需受控实机验证。
- 延迟已有关键埋点，但仍需持续采集实机数据调优。

## 下一步

- 增加受目录权限约束的本地文件工具和 Gitea 协同工具；`exec` 先采用受限能力。
- 按枢卫协议实现 Hub 工具 Profile 和任务派发。
- 接入后台任务结果同步与主动汇报。
- 完善耳机/音频眼镜使用场景下的唤醒、休眠、打断和状态机。
