#!/usr/bin/env python3
"""Run local SeekFlux applications against middleware on a remote host."""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import re
import shlex
import socket
import struct
import subprocess
import sys
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SERVICES = {"POSTGRES": 5432, "REDIS": 6379, "KAFKA": 9092,
            "ELASTICSEARCH": 9200, "MINIO": 9000}
INFRA = ["postgres", "redis", "kafka", "elasticsearch", "minio"]


def on_windows():
    return os.name == "nt"


def read_env(path):
    result = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if line.startswith("export "):
            line = line[7:]
        key, separator, value = line.partition("=")
        if not separator or not re.fullmatch(r"[A-Z][A-Z0-9_]*", key):
            raise ValueError(f"{path.name}:{number}: 无效的环境变量赋值")
        try:
            tokens = shlex.split(value, comments=True)
        except ValueError:
            raise ValueError(f"{path.name}:{number}: 引号未闭合") from None
        if len(tokens) > 1:
            raise ValueError(f"{path.name}:{number}: 包含空格的值需要引号")
        result[key] = tokens[0] if tokens else ""
    return result


def write_env(path, values):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with open(temporary, "w", encoding="utf-8", newline="\n",
              opener=lambda name, flags: os.open(name, flags, 0o600)) as handle:
        os.chmod(temporary, 0o600)
        for key, value in values.items():
            if "\n" in value or "\r" in value:
                raise ValueError(f"{key} 不支持多行值")
            handle.write(f"{key}={shlex.quote(value)}\n")
    temporary.replace(path)


def configuration(root=ROOT):
    path = root / ".env.remote"
    if not path.is_file():
        raise ValueError("请先执行 ./seekflux.sh remote-init 并填写 .env.remote")
    values = read_env(root / ".env.example")
    values.update(read_env(root / ".env.remote.example"))
    values.update(read_env(path))
    host = values.get("SEEKFLUX_REMOTE_HOST", "").strip()
    if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9.-]*", host):
        raise ValueError("SEEKFLUX_REMOTE_HOST 应为服务器 IPv4 地址或域名，不包含协议和端口")
    values["SEEKFLUX_REMOTE_HOST"] = host
    ports = {}
    for name, default in SERVICES.items():
        port = int(values.get(f"SEEKFLUX_REMOTE_{name}_PORT", str(default)))
        if not 1 <= port <= 65535:
            raise ValueError(f"{name} 端口超出范围")
        ports[name] = port
    values.update({
        "SEEKFLUX_ENV": "remote",
        "POSTGRES_JDBC_URL": f"jdbc:postgresql://{host}:{ports['POSTGRES']}/{urllib.parse.quote(values['POSTGRES_DB'], safe='')}",
        "REDIS_HOST": host, "REDIS_PORT": str(ports["REDIS"]),
        "KAFKA_BOOTSTRAP_SERVERS": f"{host}:{ports['KAFKA']}",
        "ELASTICSEARCH_URIS": f"http://{host}:{ports['ELASTICSEARCH']}",
        "S3_ENDPOINT": f"http://{host}:{ports['MINIO']}",
        "MINIO_PUBLIC_BASE": values.get("MINIO_PUBLIC_BASE") or f"http://{host}:{ports['MINIO']}",
    })
    for service in ("CONTENT", "ONLINE", "AGENT"):
        key = f"SEEKFLUX_{service}_API_BASE"
        values[key] = values.get(key) or f"http://127.0.0.1:{values[service + '_SERVER_PORT']}"
    return values, host, ports


def ssh_command(values, command):
    user = values.get("SEEKFLUX_REMOTE_USER", "root")
    if not re.fullmatch(r"[a-zA-Z_][a-zA-Z0-9_-]*", user):
        raise ValueError("无效的 SSH 用户名")
    port = int(values.get("SEEKFLUX_REMOTE_SSH_PORT", "22"))
    if not 1 <= port <= 65535:
        raise ValueError("无效的 SSH 端口")
    return ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", "-p", str(port),
            f"{user}@{values['SEEKFLUX_REMOTE_HOST']}", command]


def initialize(from_server=False):
    path = ROOT / ".env.remote"
    if not path.exists():
        write_env(path, read_env(ROOT / ".env.remote.example"))
    os.chmod(path, 0o600)
    if from_server:
        values, _, _ = configuration()
        project = values.get("SEEKFLUX_REMOTE_COMPOSE_PROJECT", "seekflux")
        if not re.fullmatch(r"[a-zA-Z0-9_-]+", project):
            raise ValueError("无效的 Compose 项目名")
        # SSH is used only for this optional setup operation, never for application traffic.
        script = '''import json, subprocess, sys
project = sys.argv[1]
result = {}
for service, keys, container_port in [
    ("postgres", ["POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD"], "5432/tcp"),
    ("minio", ["MINIO_ROOT_USER", "MINIO_ROOT_PASSWORD"], "9000/tcp"),
    ("redis", [], "6379/tcp"), ("kafka", [], "9092/tcp"),
    ("elasticsearch", [], "9200/tcp")]:
    info = json.loads(subprocess.check_output(["docker", "inspect", project+"-"+service+"-1"]))[0]
    env = dict(item.split("=", 1) for item in info["Config"]["Env"] if "=" in item)
    for key in keys:
        result[key] = env[key]
    bindings = info["NetworkSettings"]["Ports"].get(container_port)
    if not bindings:
        raise RuntimeError(service+" has no published port")
    result["SEEKFLUX_REMOTE_"+service.upper()+"_PORT"] = bindings[0]["HostPort"]
print(json.dumps(result))
'''
        command = "python3 - " + shlex.quote(project)
        completed = subprocess.run(ssh_command(values, command), input=script, text=True,
                                   capture_output=True, check=True)
        private = read_env(path)
        private.update(json.loads(completed.stdout))
        write_env(path, private)
    print(f"私有配置已准备：{path}（权限 600，Git 已忽略）")
    if not from_server:
        print("填写 POSTGRES_PASSWORD 和 MINIO_ROOT_PASSWORD 后执行 remote-check / remote-up。")


def receive(connection, size):
    data = b""
    while len(data) < size:
        block = connection.recv(size - len(data))
        if not block:
            raise ValueError("连接提前关闭")
        data += block
    return data


def kafka_brokers(host, port):
    # Metadata v0 with no requested topics is a read-only request; it creates no topics.
    body = struct.pack(">hhih", 3, 0, 71, 8) + b"seekflux" + struct.pack(">i", 0)
    with socket.create_connection((host, port), timeout=5) as connection:
        connection.sendall(struct.pack(">i", len(body)) + body)
        size = struct.unpack(">i", receive(connection, 4))[0]
        if not 8 <= size <= 16 * 1024 * 1024:
            raise ValueError("无效的 Kafka 元数据响应")
        response = receive(connection, size)
    correlation, count = struct.unpack_from(">ii", response)
    if correlation != 71 or not 1 <= count <= 1000:
        raise ValueError("Kafka 未返回可用 broker")
    offset, brokers = 8, []
    for _ in range(count):
        _, length = struct.unpack_from(">ih", response, offset)
        offset += 6
        if length < 1:
            raise ValueError("无效的 broker 地址")
        broker = response[offset:offset + length].decode()
        offset += length
        broker_port = struct.unpack_from(">i", response, offset)[0]
        offset += 4
        brokers.append((broker, broker_port))
    return brokers


def probe(name, host, port):
    if name == "POSTGRES":
        with socket.create_connection((host, port), timeout=5) as connection:
            connection.sendall(struct.pack(">ii", 8, 80877103))
            if receive(connection, 1) not in (b"S", b"N"):
                raise ValueError("端口未返回 PostgreSQL 协议")
    elif name == "REDIS":
        with socket.create_connection((host, port), timeout=5) as connection:
            connection.sendall(b"*1\r\n$4\r\nPING\r\n")
            if not connection.recv(256).startswith(b"+PONG"):
                raise ValueError("Redis PING 失败，检查服务鉴权配置")
    elif name == "KAFKA":
        for broker, broker_port in kafka_brokers(host, port):
            if broker in ("localhost", "127.0.0.1", "::1", "kafka"):
                raise ValueError("broker 仍公布本机/容器地址，请配置 KAFKA_ADVERTISED_HOST 后重建 Kafka")
            with socket.create_connection((broker, broker_port), timeout=5):
                pass
    else:
        path = "/_cluster/health" if name == "ELASTICSEARCH" else "/minio/health/live"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(f"http://{host}:{port}{path}", timeout=5) as response:
            if response.status != 200:
                raise ValueError("健康检查失败")
            if name == "ELASTICSEARCH" and json.load(response).get("status") not in ("green", "yellow"):
                raise ValueError("Elasticsearch 集群不可用")
    return f"{name}: OK {host}:{port}"


def check(host, ports):
    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        tasks = {name: pool.submit(probe, name, host, port) for name, port in ports.items()}
        for name, task in tasks.items():
            try:
                print(task.result(), flush=True)
            except (OSError, ValueError, struct.error) as error:
                failures.append(name)
                print(f"{name}: FAILED {host}:{ports[name]} ({error})", file=sys.stderr, flush=True)
    if failures:
        raise ValueError("中间件检查未通过：" + ", ".join(failures))


def applications(action, values, no_build=False):
    if on_windows():
        from windows_apps import manage
        return manage(action, values, ROOT, no_build=no_build)
    if action == "up":
        for key in ("POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD"):
            if not values.get(key):
                raise ValueError(f"请在 .env.remote 填写 {key}")
    path = ROOT / ".local/remote/runtime.env"
    write_env(path, values)
    environment = dict(os.environ, SEEKFLUX_ENV_FILE=str(path))
    # Existing launchd/nohup wrappers receive the file path explicitly so that
    # macOS service processes do not fall back to the local .env file.
    subprocess.run([str(ROOT / "deploy/local/stack.sh"), "apps-" + action],
                   env=environment, check=True)


def infra_up(values):
    project = values.get("SEEKFLUX_REMOTE_PROJECT", "/opt/seekFlux")
    command = "sh -s -- " + shlex.quote(project)
    script = '''set -eu
cd "$1"
docker compose --env-file .env -f deploy/compose/compose.yml config --quiet
docker compose --env-file .env -f deploy/compose/compose.yml up -d --wait postgres redis kafka elasticsearch minio
docker compose --env-file .env -f deploy/compose/compose.yml up -d kafka-init minio-init
'''
    subprocess.run(ssh_command(values, command), input=script, text=True, check=True)


def main():
    parser = argparse.ArgumentParser(description="本地业务代码直连服务器中间件；无需 SSH 隧道或本地 Docker")
    parser.add_argument("action", choices=("init", "up", "down", "restart", "status", "logs", "check", "infra-up"))
    parser.add_argument("--no-build", action="store_true", help="Windows: 使用已有 JAR，跳过构建")
    parser.add_argument("--from-server", action="store_true", help="初始化时通过 SSH 获取现有容器凭据")
    arguments = parser.parse_args()
    if arguments.from_server and arguments.action != "init":
        parser.error("--from-server 仅用于 remote-init")
    if arguments.action == "init":
        initialize(arguments.from_server)
        return
    if on_windows() and arguments.action in ("down", "status", "logs"):
        applications(arguments.action, {}, no_build=arguments.no_build)
        return
    if arguments.action in ("restart", "status", "logs") and not on_windows():
        parser.error("restart/status/logs 当前为 Windows 启动器命令")
    values, host, ports = configuration()
    if arguments.action == "infra-up":
        infra_up(values)
    elif arguments.action == "down":
        applications("down", values)
    else:
        check(host, ports)
        if arguments.action in ("up", "restart"):
            applications(arguments.action, values, no_build=arguments.no_build)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError) as error:
        print(f"[SeekFlux remote] {error}", file=sys.stderr)
        sys.exit(1)
    except subprocess.CalledProcessError as error:
        # Never echo captured stdout: optional setup responses contain passwords.
        print(f"[SeekFlux remote] 子命令失败，退出码 {error.returncode}；检查 SSH 权限或应用日志。", file=sys.stderr)
        sys.exit(1)
