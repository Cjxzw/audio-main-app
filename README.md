# Hanwo（喊我）

喊我 Hanwo 是一个以小米 MiMo 生态为核心的 Android 语音 Agent，可通过手机、蓝牙耳机或智能音频眼镜进行自然对话。

当前版本已跑通语音闭环、持久会话、本地工具、原生函数调用和本地异步任务汇报。Hub 远程工具和任务事实同步仍在继续完善。

## 许可证

本项目依据 [PolyForm Noncommercial License 1.0.0](LICENSE) 提供，仅允许非商业用途。商业使用需要获得作者另行书面授权；第三方组件继续适用其各自的许可证。

## 当前能力

- 使用 Android `ForegroundService` 保持后台运行。
- 使用 `AudioRouteManager` 优先选择可用的外部通信设备，并为 `AudioRecord`、`AudioTrack` 设置首选设备；无外设时回退手机音频。
- 使用 MiMo 云端 ASR 将录音 WAV 转文字。
- 聊天模型与 MiMo 语音链路已解耦：只配置 MiMo Key 即可使用全部能力，也可让聊天请求改走专属 OpenAI 兼容供应商。
- 自定义供应商支持多配置、连接测试和按回合热切换；API Key 使用 Android Keystore 加密，不写入普通配置或模型上下文。
- 使用 MiMo 云端流式 TTS 按句播放回复。
- 高级个性化播报通过内置“高级 TTS 导演”系统 Skill 按回合开放；普通回复继续使用自动 TTS。
- 支持终止型 `agent_sleep` 语义休眠：用户明确表示结束交互时直接复用完整休眠流程，不再等待静默超时。
- MiMo Key 使用 Android Keystore 加密保存；`sk` Key 自动选择按量付费地址，`tp` Key 自动选择 Token Plan 地址。
- 没有 MiMo Key但配置了专属 LLM 时，可使用纯文本交流；设置中可关闭文字输入回合的语音播报。
- TTS SSE 只解析真实音频字段，忽略文本预览和用量等控制事件；PCM 使用边界淡入淡出，避免首尾爆音。
- 使用原生 `tool_calls`、JSON Schema 和 `role=tool` 运行多轮 AgentLoop。
- 原生工具表固定为记忆、休眠、Hub 委派、深度思考、`skill_use` 和快速网络检索；本地执行能力由系统 Skill 渐进披露，避免破坏请求前缀和工具 Schema 的 KV 缓存。
- 空正文、伪工具协议和工具故障不会生成本地兜底答复；AgentLoop 必须取得有效正文并完成必要摘要后才结束。网络超时进入等待重试状态，保留相同 `turnId` 和当前回合上下文。
- 工具型任务从模型首个有效事件开始累计 30 秒有效执行时间，模型请求的首事件等待不计入；超限后停止新增本地工具，但仍允许总结或 Hub 委派。
- 未完成回合在关键边界写入最小原子检查点，进程重启后继续原会话和 `turnId`；正常完成即删除。路由反思只在异常条件下后台执行。
- 聊天气泡将 `<DETAILS>...</DETAILS>` 与正文分开渲染，带分隔线和展开/折叠控件；最近三轮默认展开，更早详情自动折叠。
- AgentLoop 已从语音 Service 抽离；Harness 提供串行回合、取消、`steer`/`followUp` 队列和统一事件。
- 支持记忆、后台位置上下文、快速网络搜索，以及通过“本地执行”系统 Skill 按回合开放的文件、命令、HTTP 和代码图谱工具。
- APK 自动携带不含密钥的源码快照，Agent 可读取源码与轮转日志进行交叉诊断。
- 支持 Agent Skills 渐进加载：常驻原生 `skill_use` 负责按中文名称加载主文件和文本附件；系统 Skill 按回合驻留，普通 Skill 默认按会话和版本驻留。
- 会话与本地记忆持久化；支持历史会话切换、重命名、删除和继续聊天。
- 新建会话前自动提炼稳定偏好、反复话题和用户明确要求记录的信息，并在新会话中加载长期记忆。
- 每个用户回合默认关闭深度思考；模型或用户可为当前回合升级一次，下一回合自动恢复关闭。
- 使用标准 Media3 `MediaLibraryService` 注册为媒体应用，支持手机控制面板、手表、耳机和音频眼镜的播放/暂停控制。
- 冷启动即发布持久 MediaStyle 卡片；卡片与前台服务共用同一个通知，提供唤醒/休眠按钮且不产生重复通知。
- 保留 Android 语音助手 Activity 入口作为兼容层，媒体控制仍是主要的跨设备入口。
- 支持息屏后台继续响应。
- 静默监听采用 15 秒总截止时间：第 10 秒播放 APK 内置语音“即将休眠”，剩余时间继续监听；第 15 秒仍无有效语音则直接休眠。该提示不依赖网络或云端 TTS。
- 本地 ASR/TTS 模型资产已从当前构建链路移除，避免 APK 过大。

## 环境要求

- Windows 或 macOS/Linux
- JDK 17
- Android SDK 34
- Android Studio 或 Gradle 命令行
- Android 8.0 及以上设备
- MiMo API Key，或可选的 OpenAI Chat Completions 兼容 LLM Key

## 配置

首次打开 App 后进入“设置 -> 小米 MiMo 服务”，填写自己的 MiMo API Key：

- `sk...`：按量付费，自动使用 `https://api.xiaomimimo.com/v1`。
- `tp...`：Token Plan 套餐，自动使用 `https://token-plan-cn.xiaomimimo.com/v1`。

ASR 和 TTS 当前只支持 MiMo。需要让聊天模型走其他服务时，可在“设置 -> 专属 LLM”中填写 Base URL、API Key 和模型 ID。

发行包不会编译任何 API Key。不要把真实密钥提交到仓库。

## 编译

代码变更后先刷新 APK 内置的 Graphify 代码图谱，再运行测试、Lint 和 Debug 构建：

```bash
graphify update .
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows PowerShell 使用 `./gradlew.bat testDebugUnitTest lintDebug assembleDebug`。

生成的调试 APK：

```text
app/build/outputs/apk/debug/hanwo-debug-0.1.0.apk
```

## 安装

确认手机已连接：

```powershell
adb devices
```

安装：

```powershell
adb install -r app\build\outputs\apk\debug\hanwo-debug-0.1.0.apk
```

`-r` 为覆盖安装，不会主动清除会话、记忆或 Android Keystore 数据。

## 代码图谱

Graphify 快照随 Debug APK 打包到 `assets/codegraph/`，供 App 内的 `code_graph_search` 和 `code_graph_explain` 只读查询。图谱只用于导航，修改和诊断结论仍需核对源码与运行日志。

```bash
graphify update .
./gradlew :app:assembleDebug
```

更新后的报告位于 [app/src/main/assets/codegraph/GRAPH_REPORT.md](app/src/main/assets/codegraph/GRAPH_REPORT.md)，压缩图谱位于 `app/src/main/assets/codegraph/graph.json.gz`。

## Agent 调试 CLI

Debug APK 提供仅限 ADB shell 调用的 Agent 调试入口。配置、会话和真实 Agent 回合可以通过结构化 CLI 操作，不需要依赖坐标点击：

```bash
./tools/hanwo-dev status
./tools/hanwo-dev config show
./tools/hanwo-dev turn run "查询今天的重要新闻"
```

Key 必须通过 stdin 写入，不能放在命令参数中。完整命令和安全边界见 [Agent 调试 CLI](docs/agent-debug-cli.md)。Release APK 不包含该调试入口。

## 使用

1. 打开 App。
2. 在设置中配置 MiMo Key或专属 LLM。
3. 点击电话按钮并授权麦克风权限，或直接在输入框发送文字。
4. 通过左上角按钮打开历史会话，切换或管理已有聊天。
5. 再次点击电话按钮，或使用媒体播放/暂停控制让 Agent 进入休眠。

休眠状态会停止收音，但服务仍保留在后台，后续用于接收后端任务结果和主动汇报。

## 主要代码结构

```text
app/src/main/java/com/agent/voiceassistant/
├── service/VoiceAgentService.kt        # Android 生命周期、语音采集和 TTS 播放
├── cloud/CloudSpeechClient.kt          # MiMo ASR / TTS 调用
├── cloud/LlmClient.kt                  # 可替换的聊天模型客户端
├── cloud/SimpleVadRecorder.kt          # 轻量录音端点检测与 WAV 生成
├── audio/AudioRouteManager.kt          # 外部音频设备选择、路由和释放
├── ui/                                # 主界面、聊天列表、音量条
├── settings/                          # 多级设置、供应商管理与加密配置
├── agent/runtime/                     # Harness、AgentLoop、事件和 Skill 索引
├── agent/                             # Agent 提示词、逐回合推理策略和旧兼容封装
├── tools/                             # 工具注册、Android 执行环境与虚拟文件系统
├── pipeline/                          # 早期管线骨架，目前不是主路径
└── report/                            # 主动汇报相关早期模型
```

## 当前限制

- Hub 委派工具保持固定 Schema；离线时执行会返回当前不可用，不会改变工具表。
- 自定义聊天供应商只影响 LLM；当前 ASR、标准 TTS 和个性化 TTS 仍依赖用户配置的 MiMo Key。
- 主动汇报相关早期模型尚未接入当前 AgentLoop。
- 语音打断和完整用户状态机尚未实现。
- App 已移除自管理 Telecom/PhoneAccount/ConnectionService，媒体键和通知由标准 Media3 会话统一管理；蓝牙麦克风由 `AudioManager` 通信路由直接建立。
- Android 14+ 从后台媒体命令升级到麦克风前台服务仍存在厂商差异；系统拒绝时会保留休眠状态，并要求用户点击通知或打开 App 后重试。
- 休眠唤醒后的蓝牙通信路由会主动等待并核验，路由失败不会伪装成已开始监听。
- 经典蓝牙设备启用 SCO 麦克风后，多功能键可能由 AVRCP 播放/暂停切换为 HFP 接听/挂断；系统不会把这类挂断事件交给纯媒体 App，需使用独立 BLE/HID 控制或避免持续占用 SCO。
- 延迟已有关键埋点，但仍需持续采集实机数据调优。
- `/source` 和 `/logs` 的只读限制由虚拟文件工具强制执行；`exec` 仍拥有 Android App UID 沙箱内的完整权限，不能把它当作独立安全沙箱。

## 开发文档

- [开发日志](开发日志.md)：按日期记录架构、实机验证和已知边界。
- [Development Status](docs/development-status.md)：当前可运行能力、验证命令和限制摘要。
- [Agent 调试 CLI](docs/agent-debug-cli.md)：通过无线 ADB 执行脱敏配置、会话和真实 Agent 回合诊断。

## Agent 执行环境

本地执行 Skill 激活后，模型看到三个稳定虚拟根：

```text
/source      随 APK 构建的只读源码快照
/logs        最多四份、每份 512 KiB 的轮转日志
/workspace   Agent 可读写工作区
```

Skill 目录不属于通用虚拟文件系统；`read`、`write` 和 `exec` 均不能进入。Skill 内容只能通过 `skill_use`、`skill_create` 和 `skill_edit` 访问。

核心工具：

- `read(path, offset?, limit?, tail_lines?)`，文件读取、目录列表和日志尾读共用一个入口
- `write(path, content, mode?)`，只允许写 `/workspace`
- `exec(argv, cwd?, timeout_seconds?)`，`cwd` 使用虚拟目录，默认 30 秒、最大 120 秒
- `http_request(method, url, body?, credential_profile?)`
- `agent_sleep()`，仅用于用户明确要求助手离开或休眠的终止型操作

`exec` 默认工作目录是 `/workspace` 对应的物理目录，也可把 `cwd` 设为 `/source` 或 `/logs`。命令参数禁止用 `..` 离开授权目录；命令有超时和输出上限，不支持交互式或长期驻留任务。

凭据 profile 只把名称和可公开的基础地址注入上下文。指定 profile 后，`http_request` 可使用 `/api/...` 相对路径；认证 Header 在本地拼接，不会返回模型。

## 下一步

- 按 Hub 协议实现工具 Profile 和任务派发。
- 接入后台任务结果同步与主动汇报。
- 完善耳机/音频眼镜使用场景下的唤醒、休眠、打断和状态机。
