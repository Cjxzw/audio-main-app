本仓库是 Shroudway 当前重点开发的 Android 语音 Main App（Hanwo/喊我）。开始新话题时，先完整阅读总项目导航：

`/Users/mac/Desktop/shordway/AGENTS.md`

同时阅读本仓库 `README.md` 和 `开发日志.md`，结合日志中的最新交接记录检查相关源码、`git status --short --branch`、Gitea 远端分支和现有未提交改动。当前开发现场可能不在 `main`，不得擅自切换、重置或覆盖。

## 多地接力开发与代码权威

- 本项目由多台 macOS 和 Windows 电脑接力开发。开始工作时先根据当前操作系统、工作目录和实际可用工具判断路径、脚本入口、构建命令及其他核心文件，不要直接照搬另一台电脑的绝对路径或环境假设。
- 应优先在当前工作区查找并阅读实际生效的 `AGENTS.md`、`README.md`、构建配置、调试脚本和相关源码，再决定需要修改或验证的核心文件；macOS 与 Windows 的路径、Shell、凭据和 Android 工具位置可能不同。
- Gitea 远端仓库原则上是项目的权威代码来源。除非用户明确指定其他来源或要求保留本地实验性改动，否则以目标 Gitea 分支的最新代码、提交历史和文件内容为准；同步前仍须检查当前分支、未提交改动和本地领先/落后状态，避免覆盖现场工作。
- 各地开发环境和设备条件不一致。macOS 端可能已安装 Android 模拟器，完成修改后可自行启动模拟器进行实机调试；Windows 端当前可能没有 Android 模拟器，调试应依赖已连接的真实 Android 手机，不要假设任一环境一定存在模拟器或相同设备能力。
- 本版本已完善调试 CLI 入口。只要当前环境具备对应设备或调试条件，就应针对改动点进行实际调试，并同时验证新增功能和相关调试 CLI 命令是否正常；优先使用 CLI 完成状态、日志、配置、会话和回合验证，再补充 UI 或设备侧验证。
- 开始实现前先确定可执行的验收路径、测试命令和调试入口。代码写完必须自行测试；测试或调试出现问题时，先检查并修复测试/调试通道本身，优先保证调试入口可用，再判断功能实现是否有缺陷。功能验收和调试通道验收都通过后，才算完成本次修改。
- `README.md` 是项目首页，负责介绍项目定位、主要能力、环境要求、快速开始和文档入口，保持简洁，避免堆放具体实现细节。具体架构、技术方案、改动记录、验证结果、实机现象和已知边界统一写入 [开发日志](开发日志.md)。开发日志是多地接力开发的核心交接文档；每次代码、配置或开发流程改动完成后，都必须同步追加对应记录。
- 开发日志记录改动目的、实际实现、关键文件、执行过的验证及结果、当前环境限制和待接力事项，并更新“最后更新”日期。文档调整也需要记录；尚未完成或未验证的行为必须明确标注，不得将计划或构建通过写成实机验收通过。

## Gitea 推送认证

- 主远端是 `origin`：`http://192.168.8.2:8418/agent/audio-main-app.git`。
- Gitea 账号名是 `agent`；密码保存在 macOS Keychain 的 `192.168.8.2:8418` Internet password 项中，不得把密码或 Token 写入本文件、Git URL、脚本、提交或日志。
- 本仓库通过 `/Users/mac/.local/bin/git-credential-gitea-keychain` 读取 Keychain，配置项为 `credential.helper`。正常情况下直接执行 `git push origin <branch>` 即可。
- 推送认证失败时，先检查 `git config --get credential.helper` 和上述 helper 是否可执行，再检查 Keychain 项；不要退回明文凭据文件。

## Android 无线 ADB 连接约定

排查或验证 Android App 前，先执行 `adb devices -l` 和 `adb mdns services` 自动发现无线调试服务；发现 `_adb-tls-connect._tcp` 后，优先使用发现的服务连接并再次确认 `adb devices -l` 显示状态为 `device`。如果自动发现或连接失败，先执行 `adb kill-server`、`adb start-server`，再重新执行 mDNS 发现。只有用户提供了明确的新连接端口且自动发现仍不可用时，才执行 `ADB_MDNS=0 adb connect <IP>:<端口>`；不要假设旧 IP 或端口仍有效。

## Debug CLI 与模拟器约定

- 读取状态、日志、会话、凭据摘要、Realtime 生命周期或 HTTP 工具时，优先使用 `tools/hanwo-dev`，避免逐步点击 UI。组合诊断使用 `./tools/hanwo-dev inspect all`，日志使用 `logs tail|grep|snapshot`。
- HTTP 回归使用 `./tools/hanwo-dev http request ...`，它复用 App 的正式 URL 前缀、凭据和响应截断策略；不得在命令行写入真实密钥。
- 本机 Apple Silicon 模拟器使用 AVD `hanwo-api34`。启动命令为 `$ANDROID_HOME/emulator/emulator -avd hanwo-api34 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect`；模拟器用于生命周期、持久化和网络验证，蓝牙、真实音频路由和厂商后台策略仍需无线 ADB 手机实测。
