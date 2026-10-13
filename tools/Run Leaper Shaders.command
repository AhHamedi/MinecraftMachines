#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
printf '%s\n' 'Install Iris, Sodium, and a shader pack in this development profile to enable shaders.'
printf '%s\n' 'Spawn with /mm leaper spawn; start a hunt with /mm leaper hunt.'
export RAYON_NUM_THREADS="${RAYON_NUM_THREADS:-3}"
exec tools/run-client.sh --preview --world 'Leaper Field Test 20260908' "$@"
