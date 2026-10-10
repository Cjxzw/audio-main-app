# Hanwo（喊我）

Hanwo（喊我）是 Shroudway 的 Android 语音 Agent 主应用。它把手机、蓝牙耳机和智能音频眼镜连接成一个可以自然对话、持续工作和管理任务的语音入口。

项目面向日常语音交流，也支持文字输入、历史会话、长期记忆、后台运行和主会话任务协作。应用的具体实现、近期改动、验证结果和已知限制请查看[开发日志](开发日志.md)。

## 主要能力

- 语音和文字对话
- 手机、蓝牙耳机及其他外部音频设备使用
- 持久会话、历史记录和长期记忆
- Realtime 持续语音交流
- 后台运行与媒体控制
- 主会话任务处理和结果汇报
- 可配置的模型服务
- Debug 调试 CLI

## 开发环境

- Windows 或 macOS/Linux
- JDK 17
- Android SDK 34
- Android Studio 或 Gradle 命令行
- Android 8.0 及以上设备

不同开发地点的模拟器、真实设备和工具可能不同。请根据当前环境选择模拟器或真实手机进行验证，并以开发日志中的最新记录为准。

## 快速开始

1. 打开项目并使用 Android Studio 或 Gradle 构建。
2. 安装 Debug APK。
3. 打开应用，在设置中配置所需的模型服务。
4. 授予麦克风等必要权限。
5. 使用文字输入或语音按钮开始交流。

常用构建命令：

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell：

```powershell
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Debug APK 默认输出到：

```text
app/build/outputs/apk/debug/hanwo-debug-0.1.0.apk
```

Debug 调试 CLI 位于 `tools/hanwo-dev`，完整用法和安全边界见[调试 CLI 文档](docs/agent-debug-cli.md)。

## 开发文档

- [开发日志](开发日志.md)：架构、技术细节、每次改动、测试验证、实机记录和已知边界，是多地接力开发的核心文档。
- [Development Status](docs/development-status.md)：当前能力和限制的简要状态。
- [Agent 调试 CLI](docs/agent-debug-cli.md)：通过 ADB 进行状态、配置、会话和 Agent 回合诊断。

## 许可证

本项目依据 [PolyForm Noncommercial License 1.0.0](LICENSE) 提供，仅允许非商业用途。商业使用需要获得作者另行书面授权；第三方组件继续适用其各自的许可证。
