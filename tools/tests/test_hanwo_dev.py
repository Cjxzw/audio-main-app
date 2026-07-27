import importlib.util
import io
import pathlib
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from unittest import mock


MODULE_PATH = pathlib.Path(__file__).parents[1] / "hanwo_dev.py"
SPEC = importlib.util.spec_from_file_location("hanwo_dev", MODULE_PATH)
hanwo_dev = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = hanwo_dev
SPEC.loader.exec_module(hanwo_dev)


class HanwoDevTest(unittest.TestCase):
    def test_parses_adb_devices_with_mdns_aliases(self):
        devices = hanwo_dev.parse_adb_devices(
            """List of devices attached
adb-phone._adb-tls-connect._tcp device product:p model:m device:d transport_id:1
adb-phone (2)._adb-tls-connect._tcp device product:p model:m device:d transport_id:2
offline-device offline
""",
        )

        self.assertEqual(3, len(devices))
        self.assertEqual("adb-phone._adb-tls-connect._tcp", devices[0].serial)
        self.assertEqual("adb-phone (2)._adb-tls-connect._tcp", devices[1].serial)
        self.assertEqual("device", devices[1].state)
        self.assertEqual("offline", devices[2].state)

    def test_auto_selects_one_alias_for_same_physical_device(self):
        device_output = b"""List of devices attached
alias-one device product:p model:m device:d
alias-two device product:p model:m device:d
"""

        def fake_run(command, **_kwargs):
            if command == ["adb", "devices", "-l"]:
                return mock.Mock(returncode=0, stdout=device_output, stderr=b"")
            if "ro.serialno" in command:
                return mock.Mock(returncode=0, stdout=b"PHYSICAL-1\n", stderr=b"")
            raise AssertionError(command)

        with mock.patch.object(hanwo_dev, "run_process", side_effect=fake_run):
            adb = hanwo_dev.Adb()

        self.assertEqual("alias-one", adb.serial)

    def test_provider_set_uses_structured_arguments(self):
        parser = hanwo_dev.build_parser()
        args = parser.parse_args(
            [
                "provider",
                "set",
                "--name",
                "MiMo Pro",
                "--base-url",
                "https://token-plan-cn.xiaomimimo.com/v1",
                "--model",
                "mimo-v2.5-pro",
            ],
        )

        command, arguments, timeout = hanwo_dev.bridge_command(args)

        self.assertEqual("provider.set", command)
        self.assertEqual("mimo-v2.5-pro", arguments["model"])
        self.assertEqual("mimo", arguments["mode"])
        self.assertIsNone(timeout)

    def test_secret_requires_noninteractive_stdin(self):
        with mock.patch.object(hanwo_dev.sys, "stdin", io.StringIO("tp-secret\n")):
            self.assertEqual("tp-secret", hanwo_dev.read_secret_from_stdin("Key"))

    def test_provider_delete_requires_confirmation_flag(self):
        parser = hanwo_dev.build_parser()
        with redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            parser.parse_args(["provider", "delete", "custom-id"])

        args = parser.parse_args(["provider", "delete", "custom-id", "--confirm"])
        command, arguments, _ = hanwo_dev.bridge_command(args)
        self.assertEqual("provider.delete", command)
        self.assertEqual({"id": "custom-id", "confirm": True}, arguments)

    def test_main_reads_secret_before_starting_adb(self):
        events = []
        fake_adb = object()
        fake_client = mock.Mock()
        fake_client.request.return_value = {"ok": True}

        def make_adb(_serial):
            events.append("adb")
            return fake_adb

        def make_client(adb, _timeout):
            self.assertIs(fake_adb, adb)
            return fake_client

        original_bridge_command = hanwo_dev.bridge_command

        def record_bridge_command(args):
            result = original_bridge_command(args)
            events.append("bridge")
            return result

        with (
            mock.patch.object(hanwo_dev.sys, "stdin", io.StringIO("tp-secret")),
            mock.patch.object(hanwo_dev, "Adb", side_effect=make_adb),
            mock.patch.object(hanwo_dev, "DebugBridgeClient", side_effect=make_client),
            mock.patch.object(hanwo_dev, "bridge_command", side_effect=record_bridge_command),
            redirect_stdout(io.StringIO()),
        ):
            exit_code = hanwo_dev.main(["key", "set"])

        self.assertEqual(0, exit_code)
        self.assertEqual(["bridge", "adb"], events)
        fake_client.request.assert_called_once_with(
            "key.set",
            {"api_key": "tp-secret"},
            timeout_seconds=None,
        )


if __name__ == "__main__":
    unittest.main()
