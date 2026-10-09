本仓库是 Shroudway 当前重点开发的 Android 语音 Main App（Hanwo/喊我）。开始新话题时，先完整阅读总项目导航：

`/Users/mac/Desktop/shordway/AGENTS.md`

同时阅读本仓库 `README.md`，检查 `git status --short --branch`、Gitea 远端分支和现有未提交改动。当前开发现场可能不在 `main`，不得擅自切换、重置或覆盖。

## Gitea 推送认证

- 主远端是 `origin`：`http://192.168.8.2:8418/agent/audio-main-app.git`。
- Gitea 账号名是 `agent`；密码保存在 macOS Keychain 的 `192.168.8.2:8418` Internet password 项中，不得把密码或 Token 写入本文件、Git URL、脚本、提交或日志。
- 本仓库通过 `/Users/mac/.local/bin/git-credential-gitea-keychain` 读取 Keychain，配置项为 `credential.helper`。正常情况下直接执行 `git push origin <branch>` 即可。
- 推送认证失败时，先检查 `git config --get credential.helper` 和上述 helper 是否可执行，再检查 Keychain 项；不要退回明文凭据文件。

## Android 无线 ADB 连接约定

排查或验证 Android App 前，先执行 `adb devices -l` 和 `adb mdns services` 自动发现无线调试服务；发现 `_adb-tls-connect._tcp` 后，优先使用发现的服务连接并再次确认 `adb devices -l` 显示状态为 `device`。如果自动发现或连接失败，先执行 `adb kill-server`、`adb start-server`，再重新执行 mDNS 发现。只有用户提供了明确的新连接端口且自动发现仍不可用时，才执行 `ADB_MDNS=0 adb connect <IP>:<端口>`；不要假设旧 IP 或端口仍有效。
