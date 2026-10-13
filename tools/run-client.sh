#!/usr/bin/env bash
# Portable development client launcher; .command files are macOS conveniences.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

mm_java_home="${JAVA_HOME:-}"
if [[ -z "$mm_java_home" && -x /usr/libexec/java_home ]]; then
  mm_java_home=$(/usr/libexec/java_home -v 21 2>/dev/null || true)
fi
if [[ -z "$mm_java_home" ]]; then
  for mm_jdk in "$HOME"/.local/jdks/*21*/Contents/Home "$HOME"/.local/jdks/*21*; do
    if [[ -x "$mm_jdk/bin/java" ]]; then
      mm_java_home="$mm_jdk"
      break
    fi
  done
fi
mm_java=java
if [[ -n "$mm_java_home" ]]; then
  mm_java="$mm_java_home/bin/java"
fi
mm_java_version=$("$mm_java" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/ {print $3}' || true)
if [[ "$mm_java_version" != 21 ]]; then
  printf '%s\n' 'Java 21 is required. Install a JDK 21 and set JAVA_HOME to its directory.' >&2
  exit 1
fi
if [[ -n "$mm_java_home" ]]; then
  export JAVA_HOME="$mm_java_home"
fi
export RAYON_NUM_THREADS="${RAYON_NUM_THREADS:-4}"

mm_options=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --preview)
      mm_python="${MM_PYTHON:-python3}"
      "$mm_python" tools/prepare_leaper_preview.py --target client
      mm_options+=(-PleaperMode=predator-preview -PleaperProfile=horrific
        -PleaperHighStepCandidate=true -PleaperAgile=true -PleaperLeap=true -PleaperApex=true)
      shift
      ;;
    --world)
      if [[ $# -lt 2 ]]; then
        printf '%s\n' '--world requires a save name.' >&2
        exit 1
      fi
      if [[ -f "minecraft_machines/neoforge/runs/client/saves/$2/level.dat" ]]; then
        mm_options+=("-PquickPlayWorld=$2")
      else
        printf 'Save "%s" is not installed; choose or create a world in Minecraft.\n' "$2"
      fi
      shift 2
      ;;
    *)
      mm_options+=("$1")
      shift
      ;;
  esac
done
if command -v caffeinate >/dev/null 2>&1; then
  caffeinate -d -u -t 90 >/dev/null 2>&1 &
fi
exec ./gradlew :minecraft_machines:neoforge:runClient "${mm_options[@]}"
