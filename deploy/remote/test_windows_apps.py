import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch

import windows_apps as apps


class WindowsProcessSafetyTest(unittest.TestCase):
    def test_identity_failure_cleans_up_unrecorded_child_by_its_handle(self):
        child = Mock(pid=123)
        child.poll.return_value = None
        with tempfile.TemporaryDirectory() as directory, \
                patch.object(apps, "process_identity", side_effect=OSError("identity unavailable")):
            state = {}
            with self.assertRaisesRegex(OSError, "identity unavailable"):
                apps.track_child(Path(directory) / "processes.json", state, "web", child, 3001, "web.log")
        child.terminate.assert_called_once_with()
        child.wait.assert_called_once_with(timeout=5)
        self.assertEqual(state, {})

    def test_reused_pid_does_not_terminate_another_process(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "processes.json"
            state = {"web": {"pid": 123, "identity": {"created": 100, "executable": "node.exe"}}}
            with patch.object(apps, "process_identity", return_value={"created": 200, "executable": "node.exe"}), \
                    patch.object(apps.subprocess, "run") as run, contextlib.redirect_stdout(io.StringIO()):
                apps.stop(path, state)
            run.assert_not_called()
            self.assertEqual(state, {})
            self.assertEqual(json.loads(path.read_text(encoding="utf-8")), {})

    def test_down_without_state_needs_no_configuration_or_toolchain(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(apps, "toolchain") as toolchain:
            apps._manage("down", {}, Path(directory))
            toolchain.assert_not_called()

    def test_stop_failure_preserves_record_for_retry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "processes.json"
            state = {"web": {"pid": 123, "identity": {"created": 100}}}
            apps.save(path, state)
            with patch.object(apps, "owned", return_value=True), patch.object(apps.time, "sleep"), \
                    patch.object(apps.subprocess, "run"):
                with self.assertRaisesRegex(ValueError, "web"):
                    apps.stop(path, state)
            self.assertIn("web", json.loads(path.read_text(encoding="utf-8")))

    @unittest.skipUnless(os.name == "nt", "Windows process APIs")
    def test_native_identity_and_stop_only_target_the_recorded_child(self):
        with tempfile.TemporaryDirectory() as directory:
            child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"],
                                     creationflags=subprocess.CREATE_NO_WINDOW)
            try:
                identity = apps.process_identity(child.pid)
                self.assertIsNotNone(identity)
                record = {"pid": child.pid, "identity": identity}
                self.assertTrue(apps.owned(record))
                with contextlib.redirect_stdout(io.StringIO()):
                    apps.stop(Path(directory) / "processes.json", {"web": record})
                child.wait(timeout=5)
                self.assertIsNone(apps.process_identity(child.pid))
                self.assertIsNotNone(apps.process_identity(os.getpid()))
            finally:
                if child.poll() is None:
                    child.kill()
                child.wait()


if __name__ == "__main__":
    unittest.main()
