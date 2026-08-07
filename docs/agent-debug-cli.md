# Hanwo Agent 调试 CLI

`tools/hanwo-dev` 是 Hanwo debug APK 的 Agent 调试入口。ADB 能直接完成的安装、权限、日志、截图和系统诊断仍使用 ADB；加密配置、会话状态和真实 Agent 回合通过 debug-only Bridge 操作。

## 快速开始

```bash
./tools/hanwo-dev status
./tools/hanwo-dev config show
printf '%s' "$MIMO_API_KEY" | ./tools/hanwo-dev key set
./tools/hanwo-dev provider set \
  --name "MiMo Pro" \
  --base-url https://token-plan-cn.xiaomimimo.com/v1 \
  --model mimo-v2.5-pro \
  --mode mimo
./tools/hanwo-dev turn run "查询今天的重要新闻"
```

多个无线 ADB 条目指向同一台物理设备时，CLI 会通过 `ro.serialno` 自动合并。存在多台物理设备时必须使用 `--serial`。

## 安全边界

- Bridge 只在 `src/debug` 中注册，Release APK 不包含调试 Receiver。
- Receiver 要求调用方持有系统 `android.permission.DUMP`，普通第三方 App 无法调用。
- 命令通过 `run-as` 写入 App 私有 inbox，广播中只有随机 `request_id`。
- Key 只能从 stdin 传入，不出现在命令历史、响应和 App 日志中。
- 配置写入调用正式 Repository，不直接修改 SharedPreferences。
- 清空会话和 Key 必须显式使用 `--confirm`。

## 命令

```text
status
device list
config show
key set
key clear --confirm
provider list
provider set [--id ID] --name NAME --base-url URL --model MODEL
provider activate ID
provider delete ID --confirm
conversation list
conversation new # 走正式 VoiceAgentService 路径，异步返回请求已接收
conversation clear --confirm
agent wake
agent sleep
turn run TEXT [--turn-timeout SECONDS]
```

所有命令输出结构化 JSON。`turn run` 会返回最终助手正文、本轮可见消息和模型消息中的原生工具调用，供 Agent 继续判断。
