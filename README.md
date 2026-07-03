# Audio Main App

Audio Main App 是一个 Android 端语音 Agent 应用原型，目标是做成可通过手机、蓝牙耳机或智能音频眼镜随时唤醒的随身秘书。

当前版本优先保证最小闭环稳定：语音输入、云端 ASR、云端 LLM、云端 TTS、语音播报、后台息屏运行、休眠/唤醒。后续会继续补会话管理、工具调用、任务派发和主动汇报。

## 当前能力

- 使用 Android `ForegroundService` 保持后台运行。
- 使用 `AudioRecord` 走系统默认输入通道，支持手机麦克风、蓝牙耳机、音频眼镜等系统路由。
- 使用 MiMo 云端 ASR 将录音 WAV 转文字。
- 使用 MiMo/OpenAI 兼容 LLM 接口进行对话。
- 使用 MiMo 云端 TTS 播放回复。
- 默认非流式 TTS，流式 TTS 管线保留但暂未默认启用。
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
├── audio/AudioRouteManager.kt          # 系统音频路由诊断
├── ui/                                # 主界面、聊天列表、音量条
├── agent/                             # Agent 配置和旧工具封装
├── pipeline/                          # 早期管线骨架，目前不是主路径
└── report/                            # 主动汇报相关早期模型
```

## 当前限制

- 会话历史只保存在进程内，尚未持久化。
- 工具调用还没有接入当前云端最小闭环。
- TTS 流式管线存在，但默认关闭，需要后续专项验证首包延迟和分块格式。
- 延迟波动尚未做结构化耗时埋点。

## 下一步

- 增加完整链路耗时日志：录音结束、ASR 完成、LLM 首字、首句生成、TTS 返回、播放开始、播放结束。
- 增加会话 ID、持久化历史和上下文裁剪。
- 接入第一个工具闭环，例如天气查询。
- 完善耳机/音频眼镜使用场景下的唤醒、休眠和主动汇报。
