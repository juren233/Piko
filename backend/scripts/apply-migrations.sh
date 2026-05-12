#!/usr/bin/env bash
# apply-migrations.sh — 应用 D1 migrations。默认 --local；--remote 推到生产 D1。
set -euo pipefail
cd "$(dirname "$0")/.."

mode="${1:-local}"
case "$mode" in
  local)
    pnpm wrangler d1 migrations apply piko-db --local
    ;;
  remote)
    pnpm wrangler d1 migrations apply piko-db --remote
    ;;
  *)
    echo "Usage: $0 [local|remote]" >&2
    exit 1
    ;;
esac
