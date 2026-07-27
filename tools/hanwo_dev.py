#!/usr/bin/env python3
"""ADB-backed CLI for Hanwo's debug-only agent bridge."""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass


PACKAGE = "com.agent.voiceassistant"
MAIN_ACTIVITY = f"{PACKAGE}/.MainActivity"
RECEIVER = f"{PACKAGE}/.debug.DebugBridgeReceiver"
ACTION = f"{PACKAGE}.debug.COMMAND"
BRIDGE_ROOT = "files/debug-bridge"


class CliError(RuntimeError):
    pass


@dataclass(frozen=True)
class Device:
    serial: str
    state: str
    description: str


def run_process(
    command: list[str],
    *,
    input_bytes: bytes | None = None,
    check: bool = True,
) -> subprocess.CompletedProcess[bytes]:
    completed = subprocess.run(
        command,
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if check and completed.returncode != 0:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise CliError(detail or f"命令失败：{' '.join(command[:3])}")
    return completed


def parse_adb_devices(output: str) -> list[Device]:
    devices: list[Device] = []
    for raw_line in output.splitlines()[1:]:
        line = raw_line.strip()
        if not line:
            continue
        match = re.match(
            r"^(?P<serial>.+?)\s+(?P<state>device|offline|unauthorized|recovery|sideload|bootloader|no permissions)(?:\s+(?P<description>.*))?$",
            line,
        )
        if match is None:
            continue
        devices.append(
            Device(
                match.group("serial"),
                match.group("state"),
                match.group("description") or "",
            ),
        )
    return devices


class Adb:
    def __init__(self, serial: str | None = None) -> None:
        self.serial = serial or self._select_device()

    @staticmethod
    def available_devices() -> list[Device]:
        output = run_process(["adb", "devices", "-l"]).stdout.decode("utf-8", errors="replace")
        return parse_adb_devices(output)

    def _select_device(self) -> str:
        devices = [device for device in self.available_devices() if device.state == "device"]
        if not devices:
            raise CliError("没有可用的 ADB 设备")
        if len(devices) == 1:
            return devices[0].serial

        physical: dict[str, list[Device]] = {}
        for device in devices:
            completed = run_process(
                ["adb", "-s", device.serial, "shell", "getprop", "ro.serialno"],
                check=False,
            )
            physical_id = completed.stdout.decode("utf-8", errors="replace").strip() or device.serial
            physical.setdefault(physical_id, []).append(device)
        if len(physical) == 1:
            aliases = next(iter(physical.values()))
            return sorted(aliases, key=lambda item: ("(2)" in item.serial, len(item.serial)))[0].serial
        summary = ", ".join(sorted(physical))
        raise CliError(f"检测到多台物理设备（{summary}），请使用 --serial 指定")

    def command(
        self,
        *arguments: str,
        input_bytes: bytes | None = None,
        check: bool = True,
    ) -> subprocess.CompletedProcess[bytes]:
        return run_process(
            ["adb", "-s", self.serial, *arguments],
            input_bytes=input_bytes,
            check=check,
        )

    def shell(self, *arguments: str, check: bool = True) -> str:
        result = self.command("shell", *arguments, check=check)
        return result.stdout.decode("utf-8", errors="replace")

    def ensure_app_started(self) -> None:
        path = self.shell("pm", "path", PACKAGE, check=False).strip()
        if not path.startswith("package:"):
            raise CliError("手机上尚未安装 Hanwo debug APK")
        if self.shell("pidof", PACKAGE, check=False).strip():
            return
        self.command("shell", "am", "start", "-n", MAIN_ACTIVITY, check=True)

    def run_as_app(
        self,
        script: str,
        *,
        input_bytes: bytes | None = None,
        check: bool = True,
    ) -> subprocess.CompletedProcess[bytes]:
        remote = f"run-as {shlex.quote(PACKAGE)} sh -c {shlex.quote(script)}"
        return self.command("shell", remote, input_bytes=input_bytes, check=check)


class DebugBridgeClient:
    def __init__(self, adb: Adb, timeout_seconds: float = 15.0) -> None:
        self.adb = adb
        self.timeout_seconds = timeout_seconds

    def request(
        self,
        command: str,
        arguments: dict[str, object] | None = None,
        *,
        timeout_seconds: float | None = None,
    ) -> dict[str, object]:
        request_id = uuid.uuid4().hex
        request = {
            "version": 1,
            "request_id": request_id,
            "command": command,
            "arguments": arguments or {},
        }
        payload = json.dumps(request, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        inbox = f"{BRIDGE_ROOT}/inbox/{request_id}.json"
        outbox = f"{BRIDGE_ROOT}/outbox/{request_id}.json"
        self.adb.ensure_app_started()
        self.adb.run_as_app(
            f"mkdir -p {BRIDGE_ROOT}/inbox {BRIDGE_ROOT}/outbox && cat > {inbox}",
            input_bytes=payload,
        )
        broadcast = self.adb.command(
            "shell",
            "am",
            "broadcast",
            "-a",
            ACTION,
            "-n",
            RECEIVER,
            "--es",
            "request_id",
            request_id,
            check=False,
        )
        if broadcast.returncode != 0:
            self._remove(inbox)
            detail = broadcast.stderr.decode("utf-8", errors="replace").strip()
            raise CliError(detail or "无法唤醒 Hanwo Debug Bridge；请确认安装的是 debug APK")

        timeout = timeout_seconds if timeout_seconds is not None else self.timeout_seconds
        deadline = time.monotonic() + timeout
        response_text = ""
        while time.monotonic() < deadline:
            completed = self.adb.run_as_app(
                f"if [ -f {outbox} ]; then cat {outbox}; fi",
                check=False,
            )
            response_text = completed.stdout.decode("utf-8", errors="replace").strip()
            if response_text:
                break
            time.sleep(0.15)
        if not response_text:
            self._remove(inbox)
            raise CliError(f"等待 Debug Bridge 响应超时（{timeout:.1f}s）")
        self._remove(outbox)
        try:
            response = json.loads(response_text)
        except json.JSONDecodeError as error:
            raise CliError(f"Debug Bridge 返回了无效 JSON：{error}") from error
        if not isinstance(response, dict):
            raise CliError("Debug Bridge 返回值不是 JSON 对象")
        return response

    def _remove(self, path: str) -> None:
        self.adb.run_as_app(f"rm -f {path}", check=False)


def read_secret_from_stdin(label: str) -> str:
    if sys.stdin.isatty():
        raise CliError(f"{label} 必须通过 stdin 传入，避免出现在命令历史中")
    value = sys.stdin.read().strip()
    if not value:
        raise CliError(f"{label} 不能为空")
    return value


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="hanwo-dev", description="Hanwo debug-only agent CLI")
    parser.add_argument("--serial", help="ADB 设备序列号；默认自动识别物理设备")
    parser.add_argument("--compact", action="store_true", help="输出紧凑 JSON")
    parser.add_argument("--timeout", type=float, default=15.0, help="普通命令超时秒数")
    commands = parser.add_subparsers(dest="group", required=True)

    commands.add_parser("status", help="读取 App、模型和当前会话状态")

    device = commands.add_parser("device", help="ADB 设备信息")
    device.add_subparsers(dest="action", required=True).add_parser("list", help="列出在线设备")

    config = commands.add_parser("config", help="配置状态")
    config.add_subparsers(dest="action", required=True).add_parser("show", help="读取脱敏配置")

    key = commands.add_parser("key", help="MiMo Key")
    key_actions = key.add_subparsers(dest="action", required=True)
    key_actions.add_parser("set", help="从 stdin 写入 MiMo Key")
    key_clear = key_actions.add_parser("clear", help="清除 MiMo Key")
    key_clear.add_argument("--confirm", action="store_true", required=True)

    provider = commands.add_parser("provider", help="LLM 供应商")
    provider_actions = provider.add_subparsers(dest="action", required=True)
    provider_actions.add_parser("list", help="列出供应商")
    provider_set = provider_actions.add_parser("set", help="创建或更新供应商")
    provider_set.add_argument("--id")
    provider_set.add_argument("--name")
    provider_set.add_argument("--base-url")
    provider_set.add_argument("--model")
    provider_set.add_argument("--mode", choices=("mimo", "openai-compatible"), default="mimo")
    provider_set.add_argument("--supports-images", action="store_true")
    provider_set.add_argument("--no-activate", action="store_true")
    provider_set.add_argument("--api-key-stdin", action="store_true")
    provider_activate = provider_actions.add_parser("activate", help="切换当前供应商")
    provider_activate.add_argument("id")
    provider_delete = provider_actions.add_parser("delete", help="删除自定义供应商")
    provider_delete.add_argument("id")
    provider_delete.add_argument("--confirm", action="store_true", required=True)

    conversation = commands.add_parser("conversation", help="会话管理")
    conversation_actions = conversation.add_subparsers(dest="action", required=True)
    conversation_actions.add_parser("list", help="列出会话")
    conversation_actions.add_parser("new", help="创建空白会话")
    conversation_clear = conversation_actions.add_parser("clear", help="清空全部会话")
    conversation_clear.add_argument("--confirm", action="store_true", required=True)

    agent = commands.add_parser("agent", help="Agent 生命周期")
    agent_actions = agent.add_subparsers(dest="action", required=True)
    agent_actions.add_parser("wake", help="请求唤醒")
    agent_actions.add_parser("sleep", help="请求休眠")

    turn = commands.add_parser("turn", help="运行真实 Agent 回合")
    turn_actions = turn.add_subparsers(dest="action", required=True)
    turn_run = turn_actions.add_parser("run", help="注入文本并等待最终回复")
    turn_run.add_argument("text")
    turn_run.add_argument("--turn-timeout", type=float, default=120.0, help="Agent 回合超时秒数")
    return parser


def bridge_command(args: argparse.Namespace) -> tuple[str, dict[str, object], float | None]:
    if args.group == "status":
        return "status", {}, None
    if args.group == "config":
        return "config.show", {}, None
    if args.group == "key":
        if args.action == "set":
            return "key.set", {"api_key": read_secret_from_stdin("MiMo Key")}, None
        return "key.clear", {"confirm": args.confirm}, None
    if args.group == "provider":
        if args.action == "list":
            return "provider.list", {}, None
        if args.action == "activate":
            return "provider.activate", {"id": args.id}, None
        if args.action == "delete":
            return "provider.delete", {"id": args.id, "confirm": args.confirm}, None
        arguments: dict[str, object] = {
            key: value
            for key, value in {
                "id": args.id,
                "name": args.name,
                "base_url": args.base_url,
                "model": args.model,
                "mode": args.mode,
                "supports_images": args.supports_images,
                "activate": not args.no_activate,
            }.items()
            if value is not None
        }
        if args.api_key_stdin:
            arguments["api_key"] = read_secret_from_stdin("供应商 API Key")
        return "provider.set", arguments, None
    if args.group == "conversation":
        if args.action == "list":
            return "conversation.list", {}, None
        if args.action == "new":
            return "conversation.new", {}, None
        return "conversation.clear", {"confirm": args.confirm}, None
    if args.group == "agent":
        return f"agent.{args.action}", {}, None
    if args.group == "turn":
        timeout_seconds = max(1.0, min(args.turn_timeout, 300.0))
        return (
            "turn.run",
            {"text": args.text, "timeout_ms": int(timeout_seconds * 1000)},
            timeout_seconds + 10.0,
        )
    raise CliError("无法识别命令")


def device_list_payload() -> dict[str, object]:
    return {
        "ok": True,
        "code": "devices",
        "data": {
            "devices": [device.__dict__ for device in Adb.available_devices()],
        },
    }


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.group == "device":
            result = device_list_payload()
        else:
            command, arguments, timeout = bridge_command(args)
            adb = Adb(args.serial)
            result = DebugBridgeClient(adb, args.timeout).request(
                command,
                arguments,
                timeout_seconds=timeout,
            )
        print(
            json.dumps(
                result,
                ensure_ascii=False,
                separators=(",", ":") if args.compact else None,
                indent=None if args.compact else 2,
            ),
        )
        return 0 if result.get("ok") is True else 2
    except (CliError, OSError) as error:
        print(json.dumps({"ok": False, "code": "cli_error", "message": str(error)}, ensure_ascii=False), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
