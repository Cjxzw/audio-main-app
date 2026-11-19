# QuickJS 工具链交接复核与修复方案

日期：2026-11-19。本文保留最初诊断与方案；首版实现及模拟器验收见开发日志 2026-11-19“QuickJS 工具链首版修复与模拟器验证”。ZIP 已在后续一轮实现并通过模拟器验收，见开发日志“ZIP 压缩解压与模拟器互通验收”；下文“ZIP 后续”保留原方案的历史含义。下文“本轮验证”指最初诊断阶段。

## 代码与设备基线

- Gitea `origin/quickjs`，提交 `041cd31`（当日 16:58，`feat: migrate agent execution and skills to QuickJS`）。本机原 `realtime` 与 `origin/realtime` 一致、工作区干净；已新建本地跟踪分支 `quickjs`，未合并旧分支。
- 本次提交引入 QuickJS/JNI，收敛 `run` 为六项只读命令；顶层文件/HTTP 工具与脚本共用 `RuntimeCommandRegistry`，保留聊天审批。排除第三方源码后，该提交涉及 40 个文件，约新增 1814 行、删除 1558 行。
- 手机 NX769J、Android 14，无线 ADB 和 Debug CLI 可用。安装版本标记为 `d83074b-dirty`，不能仅凭标记断言其精确提交。已对照手机 `/source`：`RuntimeCommand.kt`、`QuickJsHost.kt`、`quickjs_bridge.c` 字节哈希与本地一致；`SkillRegistry.kt` 内容一致，`CredentialPolicy.kt`、`AndroidExecutionEnv.kt` 仅行尾格式不同。
- 读取手机 `/workspace/reports/开发交接-工具链问题与解压方案.md` 和 `/workspace/reports/2026-11-19-工具链自检问题汇总.md`。未修改手机报告、Skill、凭证及会话内容；调试 Bridge 唤起服务后可能执行现有的冷会话自动轮换。

## 已定位问题及报告纠正

| 问题 | 源码与证据 | 判断及处理 |
| --- | --- | --- |
| 普通文本被收纳成凭证 | `CredentialPolicy.kt:13` 的 introduction 把英文关键词后的空格视为赋值。提取原始正则在 JVM 复现：“这是节省 token 的设计”匹配 `token `，候选值为“的设计”；“讨论 API key 的用法”同样误命中 | 优先修复。根因在 `SensitiveInputDetector`，不是 `CredentialPolicy.anyReference`；后者只识别已成形的占位符。不能只增加熵门槛，否则合法短密码和中文密码会被漏掉 |
| YAML 权限列表失败或退化 | `SkillRegistry.kt:606,665` 仅解析冒号行，再切分字符串，不支持行内方括号与块列表。块列表 `permissions:` 后的 `- read` 被忽略，最后得到空集；`LocalToolExecutor.kt:478` 又把空集当作全部命令 | 优先修复。报告“无法声明最小权限”过宽：`permissions: read, write` 已有测试且可用。块列表的危险在于可能静默获得全部命令，不能只剥方括号就结束 |
| JS 暴露全量方法 | `QuickJsHost.kt:76` 固定传全量 `COMMANDS`，与 scoped registry 不同 | Java 已能拒绝越权，当前不是已证实的权限绕过。仍应把同一有效权限集传入 JNI，`hanwo.call` 同样检查；`emit` 为基础输出能力，不属于命令权限 |
| 原始错误丢失 | `quickjs_bridge.c:112` 清除 Java 异常后只抛“运行时命令执行失败” | 保留命令名、受控错误代码和已净化原因；必要时加参数键名，不打印参数值和 `ExceptionDescribe`，避免把 HTTP 凭证等写进日志 |
| 顶层结果字段不可见 | `LocalToolExecutor.kt:231–390` 将共享 registry 返回值格式化成中文，脚本直接得到 JSON。手机探针真实使用 `w.bytes`，而实现返回 `bytes_written` | 返回值的数据源相同，表示形式不同。保留面向用户的 `displayText`，模型 `contextText` 使用同一份结构化结果；避免再复制一份完整正文，增加 token。`describe_credentials` 目前本身返回 JSON 字符串，需另行明确其对象结构 |
| 二进制哈希变化 | `AndroidExecutionEnv.kt:169,203` 小文件先有损 UTF-8 解码，再编码求哈希；大文件 `:247` 已按原字节求哈希 | 已从手机读取原 64 字节样本独立复算：原始 SHA-256 为 `d9778bdf…c9d4da`，解码再编码为 `2aa6a66e…c61709`，与交接报告完全一致。新增二进制通道，同时统一小/大文件和写入并发校验的字节哈希 |
| 凭证写入提示互相冲突 | `LocalToolExecutor.kt:58` 表示引用可写入文件；`AndroidExecutionEnv.kt:260` 明确拒绝，现有设备测试也要求拒绝 | 首批统一提示、工具描述与现行行为，QuickJS 从 input 接收引用、由 HTTP 宿主展开。是否恢复“文件中允许引用、禁止落盘真值”属于单独策略变更，不应在编码或解压修复中顺带改变 |
| 内置 HTTP 文档滞后 | `assets/skills/http-tool/SKILL.md` 主推旧 profile 引用和旧设置入口 | 更新为新版引用与当前 UI，保留旧 profile 兼容说明。报告“旧格式必然当普通文本发送”不成立：`AndroidExecutionEnv` 仍展开旧格式 |
| find、运行时能力与限制 | find 为字面子串；脚本无宿主 web_search、记忆、Hub 派发，无浏览器/Node API，无 Promise job 驱动 | 这些是当前设计边界。补清晰参数描述；保留 read/token/内存上限，不引入完整 Shell 或通用后台执行器 |

## 第一批：修复正确性与可诊断性

1. **凭证检测**：英文 `token/key/secret` 等不能仅凭后接空格自动收纳。优先要求明确赋值标记（冒号、等号、“是/为”），再校验候选边界，避免把讨论句当成值；显式密码输入继续支持中文和短值。回归至少覆盖报告中的原句、普通 API 讨论、合法带引号/换行/标点凭证、已有引用与恢复明文豁免。不回写历史会话，不批量删除已有凭证。
2. **权限元数据**：支持文档化的逗号标量、YAML 行内列表和块列表；不合法的列表/类型明确失败。必须区分“字段缺失（兼容全部）”“显式空列表（无命令）”“显式集合”。该区别进入 Skill 元数据、候选哈希/审批展示与持久化；如需变更审批策略版本，一并更新，避免已审批内容被重新解释。运行时与 JNI 共用有效集合，并验证命名调用和 `hanwo.call` 两条路径。未知命令报错列出允许名称。
3. **桥接错误**：Kotlin 命令边界生成 `{code, command, message}`，或 JNI 安全读取 Java 异常后转换成 JS Error；保留已有 HTTP 错误净化。路径、参数、权限、超时分别可辨，未授权方法不可见仍不能取代宿主检查。
4. **结果协议**：顶层工具的模型上下文与脚本返回结构一致；UI 摘要保留。单次执行验证字段可直接照抄，包括 `bytes_written`、`saved_sha256`、`truncated`。批量读取的输出预算与分页提示继续保留，不能为了 JSON 一致性放大输出。

这批完成后即可改善“单步跑通，再固化为 Skill”，无需等待解压功能。

## 第二批：补齐字节通道

- `read` / `write` 增加 `encoding: utf8|base64|hex`，默认 UTF-8；显式传错值报错。UTF-8 保留行号和日志筛选语义，遇非法字节应明确建议切换二进制模式，避免继续输出失真的文本。
- 二进制读取使用 `byte_offset`（从 0 开始）、`byte_limit`，与行参数/日志筛选互斥。返回 `encoding`、`byte_offset`、`bytes_read`、`next_byte_offset`、`total_bytes`、`has_more`，行号字段为 null；SHA-256 始终指整个磁盘文件。
- 40,000 字符预算下，base64 原始块最多 30,000 字节，hex 最多 20,000 字节；结构化封装也应计入总输出预算。交接报告把两者统一设为 30,000 会使 hex 超限。分页按返回的 next offset 前进，不能按请求上限猜。
- 保留手机轻量定位，二进制文件初始硬上限可设为 8 MiB，但这与 QuickJS 默认 8 MiB 堆不是等价概念：编码字符串、JNI 复制和解析都有开销。每次只读取有界字节块，不把整个文件灌进 JS。
- 二进制写入严格校验 base64/hex，支持 create/overwrite/append，拒绝 patch；`bytes_written` 为解码后的本次字节数。原文件并发校验、成功结果哈希均按磁盘字节计算。临时文件写完再发布，失败不损坏旧文件；文本 patch 保持原有行为。
- `sha256` 增加 `path`，与 `value` 二选一。通过虚拟路径解析器流式计算全文件摘要，受输入体积与执行预算限制，不能用 `read.content` 代替原字节。
- 顶层 schema、registry、执行环境、输出格式与模型提示同步更新，参数不能只在脚本一侧生效。

验收：ASCII/中文/emoji 文本兼容；64 字节样本跨 base64/hex 往返一致；100,000 字节分页拼回一致；跨 2 MiB 阈值哈希语义一致；非法编码、参数混用、只读路径、patch 并发冲突与写入失败均返回明确错误。

## 第三批：QJS 原生解压

沿用手机交接中的用户方向：脚本调用 `hanwo.gunzip/inflate/gzip/deflate`，压缩核心使用 NDK C/zlib，不依赖系统 Shell 或纯 JS inflate。

建议调用链：`hanwo.* / hanwo.call` → 同一权限分发 → Kotlin 虚拟路径/文件事务 → JNI zlib 流式处理 → 统一结果。原生的是压缩实现；路径授权仍由现有 `VirtualPathResolver` 负责。这样不用在 C 层再造一套路径规则，也避免只给命名函数加原生实现、却让 `hanwo.call("gunzip", args)` 返回“仅脚本可用”的分叉。

- CMake 链接系统 zlib，gzip 使用 `16 + MAX_WBITS`，raw deflate 使用 `-MAX_WBITS`；明确这两个命令的格式，不把 zlib 容器误叫 raw deflate。
- 输入 `path` / `base64` 二选一。文件输入不先经 read/base64，直接流式处理；以输出文件为主，返回路径、原始字节数、摘要和可选的有界预览。无输出路径的 base64 结果必须另外受 40,000 字符预算限制，不能允许返回 8 MiB 编码字符串。
- 有 output_path 时写同目录临时文件，成功后原子发布；损坏数据、超时、取消、超限时只删本次临时文件，保留原目标。拒绝输入输出同一文件、越界路径和只读挂载；默认拒绝覆盖或显式声明覆盖规则。
- `max_bytes` 是实际压缩/解压输出硬上限，必须在原生循环逐块检查；输入文件也受体积限制。所有原生循环共享 Skill 剩余 deadline/取消信号。QuickJS interrupt handler 不能自动打断长时间 C/zlib 工作，因此仅依赖脚本超时不够。
- 先完成 gunzip/inflate 的真实需求，再补对称压缩命令；路径型解压不依赖 base64 read/write 先实现，两者可分别验收。保留顶层 `run` 六条只读命令的范围。
- `.zip` 是独立容器，zlib 的上述四个接口不能解 ZIP。第一版明确不支持；若要安装下载的 Skill ZIP，后续单独设计 unzip，覆盖路径穿越、符号链接、条目数/总大小/覆盖事务，不能把 gunzip 当成 ZIP 方案。

验收：压缩往返及文件摘要一致；损坏 gzip/CRC 失败明确；小输入膨胀超限及时停止；超时不依赖回到 JS；已有目标文件不受失败影响；有限权限同时约束命名调用和 hanwo.call；大输出仅落盘、小摘要回灌。

## 本轮验证与交付边界

- Debug CLI `status`、`skills status` 成功；只读 `skills run js-native-probe main.js` 成功，确认 ECMAScript 基础对象与 hanwo 命令可用，btoa/atob/TextEncoder/Node API 等未提供。
- 从当前源码提取凭证正则的 JVM 最小复现成功；从手机原始二进制样本独立复算两种哈希成功。未借用 LLM 自诊断当作最终依据。
- Python Debug CLI 单测 10/10 通过。
- SkillRegistry/CredentialPolicy/RuntimeCommandRegistryPolicy 定向 JVM 基线检查 19/19 通过（13+3+3，零失败/错误/跳过），构建耗时 3 分 39 秒；Gradle 首次自动补装 NDK 27.2.12479018。`git diff --check` 通过。这些既有测试尚未覆盖本文全部缺陷，不能视为问题不存在或修复验收通过。本次不安装 APK、不修改运行时代码；尚未执行修复后的功能回归。

建议实施顺序：普通文本误收纳 → 权限与异常/结果协议 → 字节通道与文件摘要 → 原生 gzip/deflate。自动生成脚本草稿、更多摘要算法、CSV 转换与 ZIP 另列后续需求，不阻塞这几批修复。
