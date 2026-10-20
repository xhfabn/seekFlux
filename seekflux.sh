#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-}" in
  remote-*) exec python3 "${ROOT_DIR}/deploy/remote/remote.py" "${1#remote-}" "${@:2}" ;;
esac
exec "${ROOT_DIR}/deploy/local/stack.sh" "$@"
