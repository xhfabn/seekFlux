import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("seekflux_remote", Path(__file__).with_name("remote.py"))
remote = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(remote)


class RemoteDevelopmentTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        shutil.copy(remote.ROOT / ".env.example", self.root)
        shutil.copy(remote.ROOT / ".env.remote.example", self.root)
        self.values = remote.read_env(self.root / ".env.remote.example")
        self.values.update(POSTGRES_PASSWORD="private 'db $password#1",
                           MINIO_ROOT_PASSWORD="private-minio-secret")
        remote.write_env(self.root / ".env.remote", self.values)

    def test_all_connections_follow_one_host_and_custom_ports(self):
        self.values.update(SEEKFLUX_REMOTE_HOST="development.example.com",
                           SEEKFLUX_REMOTE_POSTGRES_PORT="15432", CONTENT_SERVER_PORT="8181")
        remote.write_env(self.root / ".env.remote", self.values)
        values, host, ports = remote.configuration(self.root)
        self.assertEqual(host, "development.example.com")
        self.assertEqual(ports["POSTGRES"], 15432)
        self.assertEqual(values["POSTGRES_JDBC_URL"], "jdbc:postgresql://development.example.com:15432/seekflux")
        for key in ("REDIS_HOST", "KAFKA_BOOTSTRAP_SERVERS", "ELASTICSEARCH_URIS", "S3_ENDPOINT", "MINIO_PUBLIC_BASE"):
            self.assertIn(host, values[key])
        self.assertEqual(values["POSTGRES_PASSWORD"], self.values["POSTGRES_PASSWORD"])
        self.assertEqual(values["SEEKFLUX_CONTENT_API_BASE"], "http://127.0.0.1:8181")
        self.assertEqual(values["SEEKFLUX_AGENT_RUNTIME_ENABLED"], "false")

    def test_app_up_uses_private_remote_file_without_ssh_or_local_infra(self):
        values, _, _ = remote.configuration(self.root)
        (self.root / ".env").write_text("POSTGRES_PASSWORD=wrong-local-password\n")
        with patch.object(remote, "ROOT", self.root), patch.object(remote.subprocess, "run") as run:
            remote.applications("up", values)
        args, kwargs = run.call_args
        self.assertEqual(args[0], [str(self.root / "deploy/local/stack.sh"), "apps-up"])
        runtime = Path(kwargs["env"]["SEEKFLUX_ENV_FILE"])
        self.assertEqual(remote.read_env(runtime)["POSTGRES_PASSWORD"], self.values["POSTGRES_PASSWORD"])
        self.assertEqual(runtime.stat().st_mode & 0o777, 0o600)

    def test_down_stops_only_local_apps(self):
        values, _, _ = remote.configuration(self.root)
        with patch.object(remote, "ROOT", self.root), patch.object(remote.subprocess, "run") as run:
            remote.applications("down", values)
        self.assertEqual(run.call_args.args[0][-1], "apps-down")

    def test_initialization_fetches_credentials_without_printing_them(self):
        private = {"POSTGRES_PASSWORD": "downloaded-private-password", "MINIO_ROOT_PASSWORD": "downloaded-minio-secret"}
        completed = subprocess.CompletedProcess([], 0, stdout=json.dumps(private))
        output = io.StringIO()
        with patch.object(remote, "ROOT", self.root), patch.object(remote, "configuration", return_value=(self.values, "36.140.162.30", {})), patch.object(remote.subprocess, "run", return_value=completed), contextlib.redirect_stdout(output):
            remote.initialize(from_server=True)
        self.assertEqual(remote.read_env(self.root / ".env.remote")["POSTGRES_PASSWORD"], private["POSTGRES_PASSWORD"])
        self.assertNotIn(private["POSTGRES_PASSWORD"], output.getvalue())

    def test_kafka_check_rejects_a_reachable_broker_advertising_localhost(self):
        with patch.object(remote, "kafka_brokers", return_value=[("localhost", 9092)]):
            with self.assertRaisesRegex(ValueError, "KAFKA_ADVERTISED_HOST"):
                remote.probe("KAFKA", "36.140.162.30", 9092)

    def test_kafka_reads_fragmented_metadata_response(self):
        host = b"36.140.162.30"
        payload = struct.pack(">iiiH", 71, 1, 1, len(host)) + host + struct.pack(">i", 9092)
        wire = bytearray(struct.pack(">i", len(payload)) + payload)

        class Connection:
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def sendall(self, value): pass
            def recv(self, size):
                result = bytes(wire[:min(size, 2)])
                del wire[:len(result)]
                return result

        with patch.object(remote.socket, "create_connection", return_value=Connection()):
            self.assertEqual(remote.kafka_brokers("36.140.162.30", 9092), [(host.decode(), 9092)])

    def test_launch_wrappers_source_the_selected_remote_file(self):
        config = self.root / "selected.env"
        remote.write_env(config, {"POSTGRES_PASSWORD": self.values["POSTGRES_PASSWORD"],
                                  "SEEKFLUX_CONTENT_API_BASE": "http://127.0.0.1:8181"})
        scripts = self.root / "deploy/local"
        scripts.mkdir(parents=True)
        for name in ("run-java-app.sh", "run-web-app.sh"):
            shutil.copy(remote.ROOT / "deploy/local" / name, scripts)
        binary = self.root / "fake/bin"
        binary.mkdir(parents=True)
        for name in ("java", "node", "npm"):
            path = binary / name
            path.write_text("#!/bin/sh\nprintf '%s\\n' \"$POSTGRES_PASSWORD\"\n")
            path.chmod(0o755)
        # Avoid java_home on the actual macOS host: return Linux for this wrapper test.
        (binary / "uname").write_text("#!/bin/sh\nprintf 'Linux\\n'\n")
        (binary / "uname").chmod(0o755)
        webbin = self.root / "apps/web/node_modules/.bin/vinext"
        webbin.parent.mkdir(parents=True)
        webbin.write_text("#!/bin/sh\nprintf '%s\\n' \"$SEEKFLUX_CONTENT_API_BASE\"\n")
        webbin.chmod(0o755)
        env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                   JAVA_HOME=str(self.root / "fake"), POSTGRES_PASSWORD="wrong-local-value")
        java = subprocess.check_output(["bash", str(scripts / "run-java-app.sh"), "dummy.jar", str(config)], env=env, text=True)
        self.assertEqual(java.strip(), self.values["POSTGRES_PASSWORD"])
        web = subprocess.check_output(["bash", str(scripts / "run-web-app.sh"), "3001", "", "", "localhost", str(config)], env=env, text=True)
        self.assertEqual(web.strip(), "http://127.0.0.1:8181")


if __name__ == "__main__":
    unittest.main()
