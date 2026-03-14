#!/usr/bin/env bash
set -euo pipefail

# R8 emulator launcher (LAUNCH ONLY)
# - Never builds
# - No jlink
# - Runs a prebuilt fat/shaded jar
#
# Env:
#   JAVA        Java executable (default: java)
#   JAVA_OPTS   Extra JVM args (space-separated)
#   R8_JAR      Explicit jar path (overrides auto-detect)
#   R8_MAIN     Main class override (default: io.github.robincores.r8.Main)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET_DIR="$REPO_DIR/target"

JAVA_BIN="${JAVA:-java}"
JAVA_OPTS="${JAVA_OPTS:-}"
MAIN_CLASS="${R8_MAIN:-io.github.robincores.r8.Main}"

usage() {
  cat <<'EOF'
Usage:
  r8.sh [--bg] [--log FILE] [program.asm|program.bin] [Main args...]

Options:
  --bg          Run in background (detached) and write logs to --log (default: target/r8.log)
  --log FILE    Log file path for --bg (default: target/r8.log)

Environment:
  R8_JAR   explicit jar to run (skips auto-detect)
  JAVA, JAVA_OPTS, R8_MAIN

Notes:
  - This script DOES NOT build. Build yourself with:
      mvn -DskipTests package
  - On macOS it adds -XstartOnFirstThread automatically.
EOF
}

# ---- flags ----
BG=0
LOG_FILE="$TARGET_DIR/r8.log"

while [[ "${1:-}" == --* ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --bg) BG=1; shift ;;
    --log)
      shift
      [[ -n "${1:-}" ]] || { echo "Error: --log requires a file path" >&2; exit 2; }
      LOG_FILE="$1"
      shift
      ;;
    --) shift; break ;;
    *)
      echo "Error: unknown option: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# macOS JavaFX requirement (non-negotiable)
if [[ "$(uname -s)" == "Darwin" ]]; then
  JAVA_OPTS="-XstartOnFirstThread ${JAVA_OPTS}"
fi

detect_platform_tag() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os" in
    Darwin)
      case "$arch" in
        arm64)  echo "mac-aarch64" ;;
        x86_64) echo "mac" ;;
        *)      echo "mac" ;;
      esac
      ;;
    Linux)
      case "$arch" in
        aarch64|arm64) echo "linux-aarch64" ;;
        x86_64|amd64)  echo "linux" ;;
        *)             echo "linux" ;;
      esac
      ;;
    MINGW*|MSYS*|CYGWIN*|Windows_NT)
      echo "win"
      ;;
    *)
      echo ""
      ;;
  esac
}

pick_jar() {
  # 1) explicit override
  if [[ -n "${R8_JAR:-}" ]]; then
    echo "$R8_JAR"
    return
  fi

  [[ -d "$TARGET_DIR" ]] || return 1

  local tag jar
  tag="$(detect_platform_tag)"
  jar=""

  if [[ -n "$tag" ]]; then
    jar="$(ls -1t "$TARGET_DIR"/*.jar 2>/dev/null | grep -E "(${tag})" | head -n 1 || true)"
  fi

  if [[ -z "$jar" ]]; then
    jar="$(ls -1t "$TARGET_DIR"/*-shaded.jar 2>/dev/null | head -n 1 || true)"
  fi

  if [[ -z "$jar" && -f "$TARGET_DIR/robincores-mac-aarch64.jar" ]]; then
    jar="$TARGET_DIR/robincores-mac-aarch64.jar"
  fi

  if [[ -z "$jar" ]]; then
    jar="$(ls -1t "$TARGET_DIR"/*.jar 2>/dev/null | head -n 1 || true)"
  fi

  [[ -n "$jar" ]] && echo "$jar"
}

has_main_manifest() {
  # Returns 0 if jar has "Main-Class:" in META-INF/MANIFEST.MF, else 1.
  if command -v unzip >/dev/null 2>&1; then
    unzip -p "$JAR_FILE" META-INF/MANIFEST.MF 2>/dev/null \
      | tr -d '\r' \
      | grep -qi '^Main-Class:'
    return $?
  fi

  if command -v jar >/dev/null 2>&1; then
    jar tf "$JAR_FILE" 2>/dev/null | grep -q '^META-INF/MANIFEST.MF$' || return 1
    local tmp
    tmp="$(mktemp -d)"
    (cd "$tmp" && jar xf "$JAR_FILE" META-INF/MANIFEST.MF) >/dev/null 2>&1 || true
    if [[ -f "$tmp/META-INF/MANIFEST.MF" ]]; then
      tr -d '\r' < "$tmp/META-INF/MANIFEST.MF" | grep -qi '^Main-Class:'
      local rc=$?
      rm -rf "$tmp"
      return $rc
    fi
    rm -rf "$tmp"
  fi

  return 1
}

JAR_FILE="$(pick_jar || true)"

if [[ -z "${JAR_FILE:-}" || ! -f "$JAR_FILE" ]]; then
  echo "Error: no runnable jar found in: $TARGET_DIR" >&2
  echo "Build one first (example): mvn -DskipTests package" >&2
  echo "Or set R8_JAR=/path/to/your.jar" >&2
  exit 1
fi

echo "Using jar: $JAR_FILE" >&2

# Split JAVA_OPTS into an array (intentional word-splitting)
JAVA_ARGS=()
if [[ -n "$JAVA_OPTS" ]]; then
  # shellcheck disable=SC2206
  JAVA_ARGS+=( $JAVA_OPTS )
fi

# Choose launch mode BEFORE starting
LAUNCH_ARGS=()
if has_main_manifest; then
  LAUNCH_ARGS=( -jar "$JAR_FILE" )
  echo "Launch mode: -jar (Main-Class from manifest)" >&2
else
  LAUNCH_ARGS=( -cp "$JAR_FILE" "$MAIN_CLASS" )
  echo "Launch mode: -cp + $MAIN_CLASS (no Main-Class in manifest)" >&2
fi

# Foreground vs background
if [[ "$BG" -eq 1 ]]; then
  mkdir -p "$(dirname "$LOG_FILE")"
  echo "Starting in background. Logs: $LOG_FILE" >&2

  # nohup + disown: detach from terminal
  nohup "$JAVA_BIN" "${JAVA_ARGS[@]}" "${LAUNCH_ARGS[@]}" "$@" >"$LOG_FILE" 2>&1 &
  PID=$!
  disown "$PID" || true
  echo "PID: $PID" >&2
  exit 0
else
  exec "$JAVA_BIN" "${JAVA_ARGS[@]}" "${LAUNCH_ARGS[@]}" "$@"
fi