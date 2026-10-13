#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
printf '%s\n' 'Spawn: /mm leaper spawn. Survival players can alert it naturally.'
printf '%s\n' '/mm leaper hunt starts a hunt; /mm leaper stop pauses behaviour.'
exec tools/run-client.sh --preview "$@"
