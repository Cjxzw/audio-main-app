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

输入作为 JSON 传入 `main(input)`；返回值必须是可序列化 JSON，必须同步返回，不支持 async main 或 Promise。默认超时 30 秒，最大 120 秒，内存上限默认 8 MiB、最大 64 MiB，`hanwo.emit` 输出最多约 40 KB。宿主命令失败会抛出异常，未捕获的异常会终止脚本。

`SKILL.md` 可以声明 `runtime: quickjs`、`entry: main.js` 和以逗号分隔的 `permissions`。权限声明只允许上述内置命令；未声明时兼容为全部内置命令；显式 `permissions: []` 表示无命令权限。支持逗号标量、`[read, write]` 行内列表和 `- read` 块列表。`emit` 是基础输出方法，无需列入 permissions；命名函数与 `hanwo.call` 受同一权限集合约束。注册审批请求会记录运行时、入口、权限、清单版本、审查策略版本和候选哈希，当前仍由用户在聊天中人工批准；安全 LLM 审查接口已预留但未自动接入。

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
| `sha256` | 计算 UTF-8 字符串或文件原始字节摘要：`value` / `path` 必须二选一 |

路径仅限 `/source`、`/logs`、`/workspace`。`cat`、`head`、`tail` 的文本读取场景走 `read`（`offset`、`limit`、`tail_lines`），写入和回收站删除走 `write`、`delete`；排序、去重、正则和 JSON 处理可在 Skill 中用 JavaScript。不是完整 Shell 兼容层，不接受管道、重定向、任意系统程序，也不依赖外部运行时。

## 结构化返回与二进制

顶层 read/write/delete/http_request 和脚本共用 JSON 结果字段，聊天气泡仍使用简短摘要。describe_credentials 返回 `{summary: ...}`。比如 write 返回 `path`、`bytes_written`、`mode`、`sha256` 和可选 `warning`，不能写成 `result.bytes`。批量 read 每项正文最多 1000 字符，需更多内容时单独读取。

read/write 的 `encoding` 为 `utf8`（默认）、`base64` 或 `hex`。UTF-8 保持行号、patch 和日志筛选语义；非法 UTF-8 明确失败，改用二进制编码。所有文件哈希和 expected_sha256 都按磁盘原始字节计算。

二进制 read 每次一个文件，文件上限 8 MiB，使用 `byte_offset`（0 起）、`byte_limit`，不能混用 offset/limit/tail_lines 或日志筛选。base64 每页最多 28000 字节，hex 最多 18000 字节，以便给 JSON 元数据留下空间。结果提供 content、total_bytes、byte_offset、bytes_read、next_byte_offset、has_more，行号字段为 null。续读必须使用返回的 next_byte_offset；每页 base64 分别解码后拼接字节，不能直接拼接带 padding 的编码字符串。

二进制 write 先严格解码再落盘，bytes_written 是本次解码后的字节数，支持 create/overwrite/append，不支持 patch。最终文件上限 8 MiB。无效编码、哈希冲突或写入失败不覆盖原文件；编码后的凭证引用也不能绕过现有文件写入限制。

```javascript
function main(input) {
    const page = hanwo.read({path: input.path, encoding: "base64", byte_offset: 0, byte_limit: 4096});
    return {page, digest: hanwo.sha256({path: input.path}).sha256};
}
```

## 原生压缩

仅 QuickJS Skill 可调用 `hanwo.gunzip/inflate/gzip/deflate`，也可使用 `hanwo.call(command, args)`；需在权限中声明相应命令。run 保持六条只读命令。

- gunzip/gzip 处理 gzip；inflate/deflate 处理 raw deflate，不是 zlib 容器，也不支持 ZIP。
- `path` / `base64` 必须二选一。path 输入最多 128 MiB，内联 base64 最多 40000 字符。
- `output_path` 可选，只能写 /workspace；默认拒绝覆盖，明确 `overwrite: true` 才可替换，不能与输入相同。
- `max_bytes` 是完整输出硬上限，默认 8 MiB、最大 8 MiB；超限失败并清理临时文件，保留原目标。无 output_path 时以 base64 返回，原始输出另限 28000 字节；有预览时进一步降低上限，以便 JSON 转义和预览仍在 40000 字符预算内。
- `preview_chars` 默认 0，最多 2000，仅控制预览。返回 bytes、sha256、output_path 或 base64、可选 preview/has_more。
- C/zlib 循环逐块检查 Skill 剩余期限与线程中断，输出不进入 JS 堆。损坏数据、CRC 失败、截断或尾随内容均报错；第一版拒绝多个 gzip member 串联，不静默丢弃后续数据。

```javascript
function main(input) {
    return hanwo.gunzip({path: input.path, output_path: "/workspace/unpacked.txt", max_bytes: 8388608, preview_chars: 100});
}
```

## ZIP 文件与目录

`hanwo.zip` / `hanwo.unzip`（或 `hanwo.call`）通过 Android 宿主的 [java.util.zip](https://developer.android.com/reference/java/util/zip/package-summary) 流式读写，须在 Skill 中声明 `permissions: [zip, unzip]`，不属于顶层 `run` 命令。普通 Stored/Deflate ZIP 支持中文、emoji、子目录、空目录和二进制；不支持密码、分卷及其他压缩算法。不恢复符号链接或 Unix 权限，打包输入拒绝符号链接。

| 命令 | 参数与返回 |
| --- | --- |
| `zip` | `path` 单个文件/目录，或 `paths` 非空字符串数组（二选一，最多 100 个输入）；必填 `output_path`；可选 `max_bytes`、`overwrite`（默认 false）。返回 `output_path`、压缩包 `bytes`、实际 `input_bytes`、`entries`、`files`、原始压缩包 `sha256` |
| `unzip` | 必填 ZIP `path`、新的 `output_dir`；可选 `max_bytes`、`max_entries`（默认/最大 1000）、`filename_charset`（默认 UTF-8，可选 GBK/GB18030）。返回 `output_dir`、`entries`、`files`、解压后的文件总 `bytes` |

zip 保留每个输入的 basename，例如 `/workspace/docs` 被打包为 `docs/...`，多个输入同名会拒绝；输出不能在输入目录内，也不能是输入文件。输入可来自 `/source`、`/logs` 或 `/workspace`，输出仅限工作区子路径。UTF-8 ZIP 标志优先于 filename_charset；旧 Windows 中文文件名可显式用 GBK/GB18030 解码。

ZIP 输入和打包文件总量最多 128 MiB；`max_bytes` 默认 8 MiB、可提高至 128 MiB，分别限制最终 ZIP 大小或解压后文件总量。路径最多 32 层、每个文件名路径最多 4096 字符；拒绝绝对路径、`..`、反斜杠、重复路径和文件/目录冲突。每个文件校验长度与 CRC，逐块检查 Skill 期限及取消，不把文件正文带进 JS 堆。

压缩覆盖文件必须明确 `overwrite: true`；解压只发布至新的目录，已有目录一律拒绝，不合并、不整目录替换。需要更换已有内容时先解压到新目录检查，再通过现有工作区工具整理。所有内容先写同级临时文件/目录，成功后发布；失败清理临时内容，已有文件和目录保留。

```javascript
function main(input) {
    const packed = hanwo.zip({
        paths: ["/workspace/报告", "/workspace/附件.bin"],
        output_path: "/workspace/归档.zip",
        max_bytes: 33554432
    });
    const unpacked = hanwo.unzip({
        path: packed.output_path,
        output_dir: "/workspace/归档解压",
        max_bytes: 33554432
    });
    return {packed, unpacked};
}
```

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
