# Skill 脚本与聊天确认

Skill 是用户自行判断可信的资源包；支持 `SKILL.md`、文本附件和 QuickJS 入口脚本。QuickJS 代码在 App 内部运行，只能通过宿主提供的白名单命令访问文件、HTTP、工作区和摘要能力。系统不扫描脚本业务行为，也不对脚本结果做额外凭证脱敏。

## 编写与提交

LLM 在 `/workspace` 准备文件。`skill_register` 提交新包，`skill_edit` 提交现有 Skill 的改名、资源增加/删除、局部修改或完整候选替换。工具只提交提案，不能直接发布。

全局最多一个未决事项，初次确认和“再议”均占用槽位。存在待办时，新注册或变更被直接拒绝，不覆盖原提案。

聊天显示请求并弹出“批准 / 拒绝 / 再议”：批准才发布；拒绝不生效；再议允许继续聊天或检查工作区。仅再议后显示发送按钮上方的信封及角标 1。待办、原会话和用户决定均落盘。最终决定回传发起会话；该会话正在执行时在下一次模型请求注入，当前会话空闲时由系统发起结果通知回合，其他会话的结果留待原会话后续请求处理。

系统把候选复制到 `/workspace/.skill-proposals/<request-id>/`，批准绑定整个目录的哈希和原 Skill 版本。用户或 LLM 修改候选后，需拒绝旧请求，再提交新版本；不能静默批准另一版。拒绝后候选保留便于修正。完整包更新不再要求用户到设置删除旧 Skill。

## 读取和运行

```json
{"skill_name":"提取错误日志","mode":"load","resource_name":"main.js"}
```

以上仅阅读脚本。真正执行需要：

```json
{"skill_name":"提取错误日志","mode":"run","resource_name":"main.js","input":"{\"path\":\"/workspace/app.log\"}","timeout_seconds":30}
```

`skill_use(mode=run)` 只能指定已启用 Skill 在 `SKILL.md` 中声明的 `.js` 或 `.mjs` 入口，不能执行任意工作区或手机物理路径。QuickJS Skill 必须定义 `main(input)`；运行前校验 Skill 版本和入口哈希。脚本通过 `hanwo.同名命令(args)` 调用宿主能力，也兼容 `hanwo.call(command, args)`；`hanwo.emit(text)` 输出阶段信息。每次运行使用独立 QuickJS Runtime。

输入作为 JSON 传入 `main(input)`；返回值必须是可序列化 JSON。默认超时 30 秒，最大 120 秒，内存上限默认 8 MiB、最大 64 MiB，`hanwo.emit` 输出最多约 40 KB。宿主命令失败会抛出异常，未捕获的异常会终止脚本。

`SKILL.md` 可以声明 `runtime: quickjs`、`entry: main.js` 和以逗号分隔的 `permissions`。权限声明只允许上述内置命令；未声明时兼容为全部内置命令。注册审批请求会记录运行时、入口、权限、清单版本、审查策略版本和候选哈希，当前仍由用户在聊天中人工批准；安全 LLM 审查接口已预留但未自动接入。

## 运行时命令

顶层 `read`、`write`、`delete`、`http_request` 和 `describe_credentials` 对应 `hanwo.read(args)`、`hanwo.write(args)` 等同名脚本函数。参数原样传入，脚本收到宿主返回值，而不是聊天展示文本。HTTP 共用 URL 白名单、凭证引用、响应截断和下载策略；直接调用时不经过 `run`，便于单独审计。

`run({command, args})` 每次只执行一个命令，固化时用 `hanwo[command](args)`，或直接写 `hanwo.grep(args)`。命令和脚本函数共用参数：

| 命令 | 用途与参数 |
| --- | --- |
| `list` | 列目录：`path`（默认 `/workspace`）、`offset`、`limit` |
| `find` | 按文件或目录名称搜索：`path`、`query`、`max_results`、`max_depth` |
| `grep` | 从文件或目录查找字面文本：`path` 或 `paths`、`pattern`、`ignore_case`、`max_results`；返回虚拟路径、行号和匹配文本。不执行宿主正则；达到扫描或输出上限时返回 `truncated` |
| `stat` | 查看文件或目录类型、大小与修改时间：`path` |
| `wc` | 统计最多 1 MiB 的文本文件中换行符、词与字节数：`path` |
| `sha256` | 计算 UTF-8 字符串的摘要：`value` |

路径仅限 `/source`、`/logs`、`/workspace`。`cat`、`head`、`tail` 的文本读取场景走 `read`（`offset`、`limit`、`tail_lines`），写入和回收站删除走 `write`、`delete`；排序、去重、正则和 JSON 处理可在 Skill 中用 JavaScript。不是完整 Shell 兼容层，不接受管道、重定向、任意系统程序，也不依赖外部运行时。

## 完整脚本示例

这个 Skill 搜索 `ERROR` 并生成工作区报告。`SKILL.md` 可声明 `entry: main.js`、`permissions: grep, write`。把输入 `path` 指向已有日志文件：

```javascript
function main(input) {
    const result = hanwo.grep({path: input.path, pattern: "ERROR", max_results: 20});
    const report = hanwo.write({
        path: "/workspace/errors.txt",
        content: result.matches.map(match => `${match.line}: ${match.text.slice(0, 80)}`).join("\n"),
        mode: "overwrite"
    });
    return {path: report.path, matches: result.matches.length, truncated: result.truncated};
}
```

固化前可先用 `run` 的 `{"command":"grep","args":{"path":"/workspace/app.log","pattern":"ERROR","max_results":20}}` 试跑。脚本把 `command` 变成函数名，`args` 无需改名或重新编码。

## 凭证引用

QuickJS Skill 可把新版 `{{cred:UID.FIELD}}` 或旧版 `{{credential.profile.key}}` 作为 HTTP Header/body 字符串传给 `http_request`，不要把凭证真值写入脚本。普通 `write` 拒绝包含凭证引用的文件内容；脚本可以在执行时从输入接收引用。

```javascript
function main(input) {
    return hanwo.http_request({
        method: "GET",
        url: input.url,
        headers: {Authorization: "Bearer " + input.credential_ref}
    });
}
```

宿主在 HTTP 命令执行前检查引用是否存在和到期，并在请求 Header/body 中展开；JSON 字符串引用会重新编码，避免凭证内容破坏 JSON 结构。引用出现在脚本注释或普通返回值中不会被 App 主动替换。

QuickJS Skill 不直接获得凭证真值；应把凭证引用交给 `http_request`，由宿主沿用现有 URL 白名单和请求策略。Skill 仍被视为用户信任资源，服务器回显和脚本主动输出不由 App 额外兜底。

## 调试入口

```powershell
python tools/hanwo_dev.py skills status
python tools/hanwo_dev.py skills propose register --payload-file registration.json
python tools/hanwo_dev.py skills propose edit --payload-file change.json
python tools/hanwo_dev.py --timeout 50 skills run 提取错误日志 main.js --input-file input.json --timeout-seconds 30
```

CLI 复用正式 `LocalToolExecutor`，提交提案仍需聊天 UI 批准，没有 CLI 批准命令。设备测试：

```powershell
adb shell am instrument -w -e class com.agent.voiceassistant.tools.QuickJsRuntimeInstrumentedTest com.agent.voiceassistant.test/androidx.test.runner.AndroidJUnitRunner
```

QuickJS 仪器测试覆盖宿主命令调用、读写、JSON 返回、阶段输出和超时中断；不调用真实业务服务器。旧 Shell 兼容测试仍保留，表示迁移期间的内部兼容路径，不再作为 Agent 对外工具协议。
