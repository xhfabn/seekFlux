"""Windows process lifecycle for local applications using remote middleware."""
import ctypes
from ctypes import wintypes
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import urllib.error
import urllib.request

APPS = ("content-server", "online-server", "agent-server", "worker-runner", "web")
PORT_KEYS = {"content-server": "CONTENT_SERVER_PORT", "online-server": "ONLINE_SERVER_PORT",
             "agent-server": "AGENT_SERVER_PORT", "web": "WEB_SERVER_PORT"}


def process_identity(pid):
    """Creation time prevents stale PID records from targeting a reused PID."""
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(wintypes.FILETIME)] * 4
    kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
    kernel.QueryFullProcessImageNameW.argtypes = [wintypes.HANDLE, wintypes.DWORD,
                                               wintypes.LPWSTR, ctypes.POINTER(wintypes.DWORD)]
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    handle = kernel.OpenProcess(0x1000, False, int(pid))
    if not handle:
        if ctypes.get_last_error() == 87:  # process no longer exists
            return None
        raise OSError("无法检查进程 " + str(pid))
    try:
        exit_code = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(exit_code)):
            raise ctypes.WinError(ctypes.get_last_error())
        if exit_code.value != 259:
            return None
        created, exited, system, user = (wintypes.FILETIME() for _ in range(4))
        if not kernel.GetProcessTimes(handle, ctypes.byref(created), ctypes.byref(exited),
                                      ctypes.byref(system), ctypes.byref(user)):
            raise ctypes.WinError(ctypes.get_last_error())
        size = wintypes.DWORD(32768)
        name = ctypes.create_unicode_buffer(size.value)
        if not kernel.QueryFullProcessImageNameW(handle, 0, name, ctypes.byref(size)):
            raise ctypes.WinError(ctypes.get_last_error())
        return {"created": (created.dwHighDateTime << 32) | created.dwLowDateTime,
                "executable": name.value}
    finally:
        kernel.CloseHandle(handle)


def owned(record):
    return process_identity(record["pid"]) == record["identity"]


def save(path, state):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2), encoding="utf-8")
    temporary.replace(path)


def stop(path, state):
    failures = []
    for name in reversed(APPS):
        record = state.get(name)
        if not record:
            continue
        if owned(record):
            subprocess.run(["taskkill", "/PID", str(record["pid"]), "/T", "/F"],
                           capture_output=True, check=False)
            for _ in range(50):
                if not owned(record):
                    break
                time.sleep(0.1)
            if owned(record):
                failures.append(name)
                continue
            print(name + ": stopped", flush=True)
        else:
            print(name + ": stale record cleared", flush=True)
        state.pop(name)
        save(path, state)
    if failures:
        raise ValueError("停止失败，保留进程记录：" + ", ".join(failures))


def wait_ready(name, record, child):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 90
    # Worker has no HTTP endpoint: require its actual Spring startup log marker.
    while time.monotonic() < deadline:
        if child.poll() is not None:
            raise ValueError(name + " 已退出，请检查 " + record["log"])
        if name == "worker-runner":
            if "Started WorkerRunnerApplication" in Path(record["log"]).read_text(encoding="utf-8", errors="replace"):
                return
        else:
            suffix = "/" if name == "web" else "/actuator/health"
            try:
                with opener.open(f"http://127.0.0.1:{record['port']}{suffix}", timeout=3) as response:
                    if response.status == 200:
                        return
            except (OSError, urllib.error.URLError):
                pass
        time.sleep(1)
    raise ValueError(name + " 启动超时，请检查 " + record["log"])


def track_child(path, state, name, child, port, log):
    """Keep a newly spawned child reachable even when identity capture fails."""
    try:
        identity = process_identity(child.pid)
        if identity is None:
            raise ValueError(name + " 启动即退出，请检查 " + str(log))
        state[name] = {"pid": child.pid, "identity": identity, "port": port, "log": str(log)}
        save(path, state)
    except BaseException:
        # Popen owns the original process handle, so PID reuse cannot redirect this.
        if name not in state and child.poll() is None:
            child.terminate()
            child.wait(timeout=5)
        raise


def toolchain(root):
    java = shutil.which("java")
    maven = shutil.which("mvn")
    java_home = os.environ.get("JAVA_HOME")
    if java_home and (Path(java_home) / "bin/java.exe").is_file():
        candidate = Path(java_home) / "bin/java.exe"
        version = subprocess.check_output([str(candidate), "-version"], stderr=subprocess.STDOUT, text=True)
        if 'version "21' in version:
            java = str(candidate)
    if not java:
        raise ValueError("需要安装 JDK 21 并将 java 加入 PATH")
    import re
    version = subprocess.check_output([java, "-version"], stderr=subprocess.STDOUT, text=True)
    match = re.search(r'version "(\d+)', version)
    if not match or int(match[1]) < 21:
        raise ValueError("需要 JDK 21+")
    nodes = sorted((root / ".runtime").glob("node-v*-win-x64/node.exe"), reverse=True)
    nodes += [Path(shutil.which("node"))] if shutil.which("node") else []
    for node in nodes:
        version = subprocess.check_output([str(node), "--version"], text=True).strip().lstrip("v")
        if tuple(map(int, version.split("."))) >= (22, 13, 0):
            return java, maven, node
    raise ValueError("需要 Node.js 22.13+；也可放在 .runtime/node-v版本-win-x64/")


def manage(action, values, root, no_build=False):
    import msvcrt
    directory = root / ".local/remote/windows"
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "command.lock").open("a+b") as lock:
        lock.seek(0)
        if not lock.read(1):
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            raise ValueError("另一个项目启动/停止命令正在执行，请稍后重试") from None
        try:
            return _manage(action, values, root, no_build)
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


def _manage(action, values, root, no_build=False):
    directory = root / ".local/remote/windows"
    state_file = directory / "processes.json"
    state = json.loads(state_file.read_text(encoding="utf-8")) if state_file.exists() else {}
    if action == "logs":
        for name in APPS:
            log = directory / (name + ".log")
            if log.exists():
                print("\n" + name + ": " + str(log))
                print("\n".join(log.read_text(encoding="utf-8", errors="replace").splitlines()[-25:]))
        return
    if action == "status":
        for name in APPS:
            record = state.get(name)
            alive = bool(record and owned(record))
            print(name + ": " + ("RUNNING pid=" + str(record["pid"]) if alive else "STOPPED"))
        return
    if action in ("down", "restart"):
        stop(state_file, state)
        if action == "down":
            return
    for key in ("POSTGRES_PASSWORD", "MINIO_ROOT_PASSWORD"):
        if not values.get(key):
            raise ValueError("请在 .env.remote 填写 " + key)
    running = [name for name in APPS if name in state and owned(state[name])]
    if running:
        if len(running) == len(APPS):
            print("所有项目进程已启动；重建请执行 restart。")
            return
        raise ValueError("已有部分项目进程运行，请先 down 再 up")
    ports = {name: int(values[key]) for name, key in PORT_KEYS.items()}
    if len(set(ports.values())) != len(ports) or any(not 1 <= port <= 65535 for port in ports.values()):
        raise ValueError("本地服务端口必须在 1–65535 内且不能重复")
    for name, port in ports.items():
        with socket.socket() as connection:
            if connection.connect_ex(("127.0.0.1", port)) == 0:
                raise ValueError(f"{name} 端口 {port} 已被占用，请修改 .env.remote；不会停止已有进程")
    java, maven, node = toolchain(root)
    environment = dict(os.environ, **values)
    environment.update(JAVA_HOME=str(Path(java).parent.parent), SEEKFLUX_WEB_RUNTIME="node")
    environment["PATH"] = str(node.parent) + os.pathsep + str(Path(java).parent) + os.pathsep + environment["PATH"]
    if not no_build:
        if not maven:
            raise ValueError("需要 Maven 3.9+ 并将 mvn 加入 PATH")
        subprocess.run([maven, "-pl", ",".join("apps/" + name for name in APPS if name != "web"),
                        "-am", "package", "-DskipTests"], cwd=root, env=environment, check=True)
    web_cli = root / "apps/web/node_modules/vinext/dist/cli.js"
    if not web_cli.is_file():
        npm = node.parent / "node_modules/npm/bin/npm-cli.js"
        if not npm.is_file():
            raise ValueError("Node 安装中缺少 npm，请在 apps/web 执行 npm ci")
        subprocess.run([str(node), str(npm), "ci", "--no-audit", "--no-fund"],
                       cwd=root / "apps/web", env=environment, check=True)
    commands = {}
    for name in APPS[:-1]:
        jars = [jar for jar in (root / "apps" / name / "target").glob("*.jar")
                if not jar.name.endswith(("-sources.jar", "-javadoc.jar"))]
        if len(jars) != 1:
            raise ValueError(name + " 的运行 JAR 缺失或不唯一，请先构建")
        commands[name] = [java, "-jar", str(jars[0])]
    web_host = values.get("WEB_SERVER_HOST", "localhost")
    if web_host == "localhost":
        web_host = "127.0.0.1"
    commands["web"] = [str(node), str(web_cli), "dev", "--host", web_host,
                       "--port", str(ports["web"])]
    directory.mkdir(parents=True, exist_ok=True)
    state = {}
    children = {}
    try:
        for name, command in commands.items():
            log = directory / (name + ".log")
            with log.open("wb") as output:
                child = subprocess.Popen(command, cwd=root / "apps/web" if name == "web" else root,
                                         env=environment, stdout=output, stderr=subprocess.STDOUT,
                                         creationflags=subprocess.CREATE_NO_WINDOW)
            children[name] = child
            track_child(state_file, state, name, child, ports.get(name), log)
            print(name + ": starting pid=" + str(child.pid), flush=True)
        for name in APPS:
            wait_ready(name, state[name], children[name])
            print(name + ": ready", flush=True)
    except BaseException:
        stop(state_file, state)
        raise
    print("前端地址：http://localhost:" + str(ports["web"]))
