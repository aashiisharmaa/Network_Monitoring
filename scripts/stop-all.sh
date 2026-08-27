#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
BACKEND_PID_FILE="$PROJECT_ROOT/.backend-java-dev.pid"

if [[ -f "$BACKEND_PID_FILE" ]]; then
  BACKEND_PID="$(cat "$BACKEND_PID_FILE")"
  if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
    kill "$BACKEND_PID"
    echo "Stopped backend process $BACKEND_PID."
  else
    echo "Backend process was not running."
  fi
  rm -f "$BACKEND_PID_FILE"
else
  echo "No backend pid file found."
fi
