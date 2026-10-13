#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
exec tools/run-client.sh --preview --world 'Leaper Predator Wilderness' "$@"
