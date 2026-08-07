#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEB_SERVER_PORT="${1:-3001}"

cd "${ROOT_DIR}/apps/web"
exec npm run dev -- --host localhost --port "${WEB_SERVER_PORT}"
