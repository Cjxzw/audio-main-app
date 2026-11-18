# Skill 脚本与聊天确认

Skill 是用户自行判断可信的资源包；支持 `SKILL.md`、文本附件和 `.sh`。脚本以 App UID 执行，可嵌套 shell、调用设备现有命令、访问该 UID 可访问的数据。系统不扫描脚本行为，也不对脚本结果做凭证脱敏。

## 编写与提交

LLM 在 `/workspace` 准备文件。`skill_register` 提交新包，`skill_edit` 提交现有 Skill 的改名、资源增加/删除、局部修改或完整候选替换。工具只提交提案，不能直接发布。

全局最多一个未决事项，初次确认和“再议”均占用槽位。存在待办时，新注册或变更被直接拒绝，不覆盖原提案。

聊天显示请求并弹出“批准 / 拒绝 / 再议”：批准才发布；拒绝不生效；再议允许继续聊天或检查工作区。仅再议后显示发送按钮上方的信封及角标 1。待办、原会话和用户决定均落盘。最终决定回传发起会话；该会话正在执行时在下一次模型请求注入，当前会话空闲时由系统发起结果通知回合，其他会话的结果留待原会话后续请求处理。

系统把候选复制到 `/workspace/.skill-proposals/<request-id>/`，批准绑定整个目录的哈希和原 Skill 版本。用户或 LLM 修改候选后，需拒绝旧请求，再提交新版本；不能静默批准另一版。拒绝后候选保留便于修正。完整包更新不再要求用户到设置删除旧 Skill。

## 读取和运行

```json
{"skill_name":"检查服务器","mode":"load","resource_name":"scripts/check.sh"}
```

以上仅阅读脚本。真正执行需要：

```json
{"skill_name":"检查服务器","mode":"run","resource_name":"scripts/check.sh","input":"{\"server\":\"example.test\"}","timeout_seconds":30}
```

`run` 只能指定已启用 Skill 中的 `.sh`，入口不接受任意工作区或手机物理路径。普通 `exec` 的 shell 禁令保留。脚本内部可调用其他脚本。运行前复制 Skill 包到 App cache 的独立目录并检查版本，工作目录为包根目录；附属脚本可用 `sh ./scripts/child.sh`。原 Skill 之后发生更新，不改变这次运行的副本。

输入经 stdin 传入；stdout/stderr 合并返回，保留退出码、超时和截断状态。默认 120 秒，最大 120 秒，返回输出最多约 40 KB；没有后台 daemon 的生命周期保证。失败停止后续业务步骤由脚本自身的显式检查实现，执行器不会替脚本推断 HTTP 业务状态。

## 虚拟路径

脚本直接使用 `/workspace`、`/source`、`/logs`；变量拼接、shell 重定向和继承环境的子脚本同样使用这些路径。例如：

```sh
set -eu
root=/workspace
mkdir -p "$root/results"
printf '%s\n' 'hello' > "$root/results/output.txt"
sh ./scripts/child.sh
cat /workspace/results/output.txt
```

实现是 APK 内置 `libhanwo_paths.so`，通过 `LD_PRELOAD` 给 shell 和动态链接的子程序映射文件系统调用路径。**不替换脚本中的路径字符串**，所以 JSON、URL、注释、输出中的 `/workspace` 不会被改写。

它是路径别名支持，不是隔离容器。静态链接程序、直接调用 Linux syscall 或主动清除 `LD_PRELOAD` 的程序不在该映射覆盖范围；它们可用 `HANWO_WORKSPACE`、`HANWO_SOURCE`、`HANWO_LOGS` 物理根环境变量。`HANWO_SKILL_ROOT` 是本次包快照。`pwd` 或 `realpath` 可能返回物理路径。

运行时是设备的 `/system/bin/sh`，不附带完整 Linux、Python、Node、curl 或 jq；需要先确认脚本依赖在设备上存在。

## 凭证引用

工作区文件与 Skill 允许保存引用，不应保存真值。`.sh` 的 shell 参数/变量和 stdin 支持新版 `{{cred:UID.FIELD}}`、旧版 `{{credential.profile.key}}`。

```sh
token='{{cred:credential-id.field-id}}'
# 后续可以把 "$token" 交给设备上可用的 HTTP 客户端。
```

脚本启动前检查引用是否存在和到期，把值放入子进程环境，再按 shell 引号上下文展开，避免值里的引号或 `$()` 改变程序。JSON stdin 按字符串节点替换并重新编码，普通文本 stdin 按文本替换。建议将引用写在普通 shell 参数/赋值中；不把引用放在 here-document、注释或动态生成的代码里。引用出现在注释中不展开。

可信脚本可获得真实值；此模式不检查 HTTP URL 白名单，不保证结果、日志或子进程隐藏真值。现有 `http_request` 的 URL 白名单与脱敏行为保持自身逻辑。

## 调试入口

```powershell
python tools/hanwo_dev.py skills status
python tools/hanwo_dev.py skills propose register --payload-file registration.json
python tools/hanwo_dev.py skills propose edit --payload-file change.json
python tools/hanwo_dev.py --timeout 50 skills run 检查服务器 scripts/check.sh --timeout-seconds 30
```

CLI 复用正式 `LocalToolExecutor`，提交提案仍需聊天 UI 批准，没有 CLI 批准命令。设备测试：

```powershell
adb shell am instrument -w -e class com.agent.voiceassistant.tools.SkillScriptInstrumentedTest com.agent.voiceassistant.test/androidx.test.runner.AndroidJUnitRunner
```

该测试使用临时 Skill 包与测试凭证，覆盖路径、变量拼接、嵌套 shell、引用、非零退出和超时；不调用真实业务服务器。
