---
name: 本地执行
description: 按需开放本地文件、命令、HTTP、代码图谱以及普通 Skill 创建和编辑能力。
---

# 本地执行

本 Skill 仅在当前用户回合生效。隐藏工具调用必须作为唯一正文，格式如下：

```json
{"name":"read","arguments":{"path":"/source/README.md","offset":1,"limit":200}}
```

开放工具及参数：

- `read`: `path`，可选 `offset`、`limit`、`tail_lines`、`log_levels`、`log_tags`、`event_prefixes`、`query`；批量读取可传 `paths`。
- `write`: `path`、`content`，可选 `mode`（`create`、`overwrite`、`append`、`replace`）及替换所需的 `old_text`。
- `workspace_delete`: `path`，仅允许 `/workspace`，内容进入回收站。
- `exec`: `argv` 字符串数组，可选 `cwd`、`timeout_seconds`；禁止 shell、多命令和 `..` 越界。
- `http_request`: `method`、`url`，可选 `body`、`content_type`、`credential_profile`。
- `code_graph_search`: `query`，可选 `limit`。
- `code_graph_explain`: `symbol`。
- `skill_create`: `skill_name`、`description`、`content`。
- `skill_edit`: `skill_name`、`operation`、`resource_name`、`expected_sha256`，按操作补充 `old_text`、`new_text`。

通用文件工具只允许访问 `/source`、`/logs` 和 `/workspace`，不能访问任何 Skill 目录。创建普通 Skill 后仍需 `skill_use` 才能加载。编辑前先用 `skill_use` 取得当前版本或文件 SHA-256；支持 `replace_text`、`create_resource`、`replace_resource`、`delete_resource`、`rename`。`replace_text` 要求 `old_text` 在目标文件中唯一匹配；`rename` 将新名称放入 `new_text`。系统 Skill 不可编辑，`SKILL.md` 不可删除。
