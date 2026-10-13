#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
printf '%s\n' 'Apex is always enabled. Spawn with /mm leaper spawn; hunt with /mm leaper hunt.'
exec tools/run-client.sh --preview "$@"
