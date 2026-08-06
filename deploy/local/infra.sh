#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STACK_SCRIPT="${ROOT_DIR}/deploy/local/stack.sh"

case "${1:-start}" in
  start) exec "${STACK_SCRIPT}" infra-up ;;
  download|install) exec "${STACK_SCRIPT}" install ;;
  status) exec "${STACK_SCRIPT}" status ;;
  stop) exec "${STACK_SCRIPT}" infra-down ;;
  restart)
    "${STACK_SCRIPT}" infra-down
    exec "${STACK_SCRIPT}" infra-up
    ;;
  *) exec "${STACK_SCRIPT}" "$@" ;;
esac
