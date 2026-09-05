#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEB_SERVER_PORT="${1:-3001}"
NODE_BIN_DIR="${2:-}"
NPM_BIN_DIR="${3:-}"
WEB_SERVER_HOST="${4:-localhost}"

if [[ -n "${NODE_BIN_DIR}" || -n "${NPM_BIN_DIR}" ]]; then
  export PATH="${NODE_BIN_DIR}:${NPM_BIN_DIR}:${PATH}"
fi

cd "${ROOT_DIR}/apps/web"
command -v node >/dev/null 2>&1 || { printf '未找到 node，请检查 Node.js 安装。\n' >&2; exit 127; }
command -v npm >/dev/null 2>&1 || { printf '未找到 npm，请检查 Node.js 安装。\n' >&2; exit 127; }
export SEEKFLUX_WEB_RUNTIME="${SEEKFLUX_WEB_RUNTIME:-node}"
export WRANGLER_LOG_PATH="${WRANGLER_LOG_PATH:-.wrangler/wrangler.log}"
VINEXT_BIN="${ROOT_DIR}/apps/web/node_modules/.bin/vinext"
[[ -x "${VINEXT_BIN}" ]] || { printf '未找到 vinext，请先安装 Web 依赖。\n' >&2; exit 127; }
# Execute the actual server binary so the PID file tracks the long-lived
# process. `npm run` leaves Vinext orphaned when only the npm parent is stopped.
exec "${VINEXT_BIN}" dev --host "${WEB_SERVER_HOST}" --port "${WEB_SERVER_PORT}"
