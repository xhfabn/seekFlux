#!/usr/bin/env python3
"""Build and run the standalone Java evaluation CLI; no application or dataset dependency."""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
MODULE = ROOT / "platform/agent-runtime-eval"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true", help="reuse installed build and generated classpath")
    parser.add_argument("command", nargs="?", default="demo", choices=("demo", "run", "compare", "rescore"))
    parser.add_argument("arguments", nargs="*")
    args = parser.parse_args()
    java = shutil.which("java")
    maven = shutil.which("mvn")
    if not java or (not args.no_build and not maven):
        parser.error("JDK 21 and Maven 3.9+ must be available on PATH")
    classpath_file = MODULE / "target/runtime-classpath.txt"
    if not args.no_build:
        subprocess.run([maven, "-B", "-ntp", "-pl", "platform/agent-runtime-eval", "-am",
                        "install", "-DskipTests"], cwd=ROOT, check=True)
        subprocess.run([maven, "-B", "-ntp", "-f", str(MODULE / "pom.xml"),
                        "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath",
                        "-DincludeScope=runtime", "-Dmdep.outputFile=target/runtime-classpath.txt"],
                       cwd=ROOT, check=True)
    if not classpath_file.exists():
        parser.error("build first; runtime classpath is missing")
    arguments = args.arguments or ([str(ROOT / ".local/evals/agent-v1")] if args.command == "demo" else [])
    classpath = str(MODULE / "target/classes") + os.pathsep + classpath_file.read_text(encoding="utf-8").strip()
    result = subprocess.run([java, "-cp", classpath, "io.seekflux.platform.agenteval.EvalCli",
                             args.command, *arguments], cwd=ROOT)
    return result.returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)

