---
name: 本地执行
description: 按需开放本地文件读写、命令、HTTP、代码图谱以及普通 Skill 创建和编辑能力。
---

# 本地执行

本skill可获得更多本地执行的工具，来实现更多本地操作。本 Skill 仅在当前用户回合生效，需要使用到本skill的工具请在本回合内完成。首次加载本 Skill 后，才能执行下面列出的动作。

## 严格输出规则

需要执行动作时，助手消息必须只包含一个 JSON 对象，不能有前后说明、Markdown、代码围栏或 `<DETAILS>`。

严格按照下面的顶层格式输出：

```json
{"name":"动作名称","arguments":{}}
```

绝对不要把这个 JSON 嵌套在 `skill_use`、`resource_name`、其他 JSON、XML、Markdown 或普通文字中。不要修改顶层字段名：必须是 `name` 和 `arguments`。动作执行完毕后，Harness 会返回结果；收到结果后，再输出下一个动作或最终自然语言回复。

## read

用途：读取 `/source`、`/logs` 或 `/workspace` 下的文本文件，也可以列出目录。

参数：

- `path`：单个绝对虚拟路径；读取目录时返回目录清单。
- `paths`：多个绝对虚拟路径，与 `path` 二选一，最多 10 个。
- `offset`：从 1 开始的字符或行偏移，按读取器支持的语义使用。
- `limit`：读取上限。
- `tail_lines`：优先读取文件末尾的行数，日志应优先使用此参数。
- `log_levels`、`log_tags`、`event_prefixes`、`query`：日志筛选条件，仅在读取日志时使用。

传参要求：必须使用 `/source`、`/logs` 或 `/workspace` 绝对路径；不要读取 `/skills`，Skill 文件必须用 `skill_use`。

案例：

```json
{"name":"read","arguments":{"path":"/source/README.md","offset":1,"limit":200}}
```

## write

用途：在 `/workspace` 创建、覆盖、追加或按唯一文本匹配替换 UTF-8 文本文件。

参数：

- `path`：`/workspace` 下的绝对文件路径。
- `content`：要写入的文本。
- `mode`：`create`、`overwrite`、`append` 或 `replace`。
- `old_text`：`replace` 模式下要替换的旧文本，必须唯一匹配。
- `new_text`：`replace` 模式下替换后的文本。

传参要求：只能写 `/workspace`；不得写 `/source`、`/logs`、`/skills` 或系统目录。删除文件必须使用 `workspace_delete`。

案例：

```json
{"name":"write","arguments":{"path":"/workspace/note.txt","content":"第一行\n","mode":"create"}}
```

## workspace_delete

用途：删除 `/workspace` 下的文件或目录，但内容会先移入回收站。

参数：

- `path`：`/workspace` 下的绝对路径。

传参要求：只能操作 `/workspace`，不能删除 `/source`、`/logs`、`/skills` 或其外部路径。

案例：

```json
{"name":"workspace_delete","arguments":{"path":"/workspace/old-note.txt"}}
```

## exec

用途：在 App 自身沙箱中执行一次性命令，适合需要程序处理文件或计算的任务。

参数：

- `argv`：字符串数组；第一个元素是程序，其余元素是参数。
- `cwd`：可选绝对虚拟工作目录，默认为 `/workspace`。
- `timeout_seconds`：可选超时秒数。

传参要求：禁止 shell、管道、重定向、环境变量、多命令和 `..` 路径越界。文件路径仍必须位于 `/source`、`/logs`、`/workspace` 或 `/skills` 的允许范围内。

案例：

```json
{"name":"exec","arguments":{"argv":["wc","-l","/source/README.md"],"cwd":"/workspace","timeout_seconds":30}}
```

## http_request

用途：发送一次 HTTP 或 HTTPS 请求，用于 API 调试和 Skill 工作流。

参数：

- `method`：HTTP 方法，例如 `GET`、`POST`。
- `url`：绝对 HTTP 或 HTTPS URL。
- `body`：可选请求正文。
- `content_type`：可选正文类型，例如 `application/json`。
- `credential_profile`：可选凭据 profile 名称。

传参要求：认证信息只能引用 `credential_profile`，不得把 API Key、Token 或密码写入参数、正文或回复。

案例：

```json
{"name":"http_request","arguments":{"method":"GET","url":"https://example.com/api/status"}}
```

## code_graph_search

用途：在源码代码图谱中搜索类、函数、调用关系和相关符号。

参数：

- `query`：要搜索的符号或自然语言问题。
- `limit`：可选返回条数上限。

传参要求：这是只读动作；查询应具体，优先使用类名、函数名或短问题。

案例：

```json
{"name":"code_graph_search","arguments":{"query":"VoiceAgentService 的工具执行入口","limit":10}}
```

## code_graph_explain

用途：解释代码图谱中的一个类、函数或其他符号。

参数：

- `symbol`：要解释的完整或明确符号名。

传参要求：一次只解释一个符号；不确定时先使用 `code_graph_search`。

案例：

```json
{"name":"code_graph_explain","arguments":{"symbol":"com.agent.voiceassistant.service.VoiceAgentService"}}
```

## skill_create

用途：直接创建并启用一个普通 Skill。创建成功后，该 Skill 会以 `CONVERSATION` 驻留方式保存；需要使用时再通过 `skill_use` 加载。

参数：

- `skill_name`：Skill 名称。
- `description`：用途和触发场景。
- `content`：Skill 的 Markdown 正文。

传参要求：只能创建知识或流程说明型 Skill。`skill_create` 成功即表示 Skill 已创建并启用；但在当前或后续会话中使用其内容前，仍需调用 `skill_use` 加载。

案例：

```json
{"name":"skill_create","arguments":{"skill_name":"会议纪要","description":"整理会议记录并提炼行动项","content":"# 会议纪要\n\n说明整理步骤和输出格式。"}}
```

## skill_edit

用途：编辑已加载的普通 Skill 文件或元数据。

参数：

- `skill_name`：目标 Skill 名称。
- `operation`：`replace_text`、`create_resource`、`replace_resource`、`delete_resource` 或 `rename`。
- `resource_name`：Skill 内部相对文件名。
- `expected_sha256`：编辑前通过 `skill_use` 获取的版本或文件 SHA-256。
- `old_text`、`new_text`：按操作要求提供；`replace_text` 的 `old_text` 必须唯一匹配。

传参要求：编辑前必须先 `skill_use` 获取当前内容或哈希；不得编辑系统 Skill，不得删除 `SKILL.md`，不得使用 `/source`、`/logs` 或 `/workspace` 路径替代 `resource_name`。

案例：

```json
{"name":"skill_edit","arguments":{"skill_name":"会议纪要","operation":"replace_text","resource_name":"SKILL.md","expected_sha256":"当前文件SHA256","old_text":"旧步骤","new_text":"新步骤"}}
```

## 再次强调

每次执行动作都必须严格输出一个独立的 `{"name":...,"arguments":...}` JSON 对象。切勿把动作嵌套在 `skill_use`、`resource_name`、`payload`、其他 JSON、XML、代码围栏或普通文字中。没有动作要执行时，才输出普通最终回答。
