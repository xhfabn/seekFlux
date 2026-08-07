#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEB_SERVER_PORT="${1:-3001}"
NODE_BIN_DIR="${2:-}"
NPM_BIN_DIR="${3:-}"

if [[ -n "${NODE_BIN_DIR}" || -n "${NPM_BIN_DIR}" ]]; then
  export PATH="${NODE_BIN_DIR}:${NPM_BIN_DIR}:${PATH}"
fi

cd "${ROOT_DIR}/apps/web"
command -v node >/dev/null 2>&1 || { printf '未找到 node，请检查 Node.js 安装。\n' >&2; exit 127; }
command -v npm >/dev/null 2>&1 || { printf '未找到 npm，请检查 Node.js 安装。\n' >&2; exit 127; }
exec npm run dev -- --host localhost --port "${WEB_SERVER_PORT}"
