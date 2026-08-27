#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$PROJECT_ROOT/.env"
BACKEND_DIR="$PROJECT_ROOT/backend-java"
PYTHON_SERVICE_DIR="$PROJECT_ROOT/python service"
BACKEND_PID_FILE="$PROJECT_ROOT/.backend-java-dev.pid"
INSTALL_DEPS=0

for arg in "$@"; do
  case "$arg" in
    --install|--setup)
      INSTALL_DEPS=1
      ;;
    -h|--help)
      echo "Usage: ./run.sh [--install]"
      echo "  ./run.sh           Start backend and frontend without installing dependencies."
      echo "  ./run.sh --install Install/update npm and Python dependencies before starting."
      exit 0
      ;;
    *)
      echo "Unknown option: $arg"
      echo "Usage: ./run.sh [--install]"
      exit 1
      ;;
  esac
done

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ENV_FILE"
  set +a
else
  echo "Missing env file: $ENV_FILE"
  exit 1
fi

FRONTEND_DIR="${FRONTEND_DIR:-../Frontend}"
if [[ "$FRONTEND_DIR" != /* ]]; then
  FRONTEND_DIR="$(cd -- "$PROJECT_ROOT/$FRONTEND_DIR" && pwd)"
fi

export PORT="${PORT:-3000}"
export JWT_SECRET="${JWT_SECRET:-local-desktop-jwt-secret-change-me}"
export LICENSE_DEVICE_ID="${LICENSE_DEVICE_ID:-5A-10-31-F8-B7-E6}"
export LICENSE_FILE_PATH="${LICENSE_FILE_PATH:-license.dat}"
export LICENSE_SIGNING_SECRET="${LICENSE_SIGNING_SECRET:-local-license-signing-secret-change-me}"
export DATABASE_URL="${DATABASE_URL:-mysql://nm_app_user:NmApp1234@127.0.0.1:3306/kpi_tool_nm}"
export DB_USER="${DB_USER:-nm_app_user}"
export DB_PASSWORD="${DB_PASSWORD:-NmApp1234}"
export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL:-jdbc:mysql://127.0.0.1:3306/kpi_tool_nm?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC}"
export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME:-nm_app_user}"
export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD:-NmApp1234}"
export SPRING_JPA_HIBERNATE_DDL_AUTO="${SPRING_JPA_HIBERNATE_DDL_AUTO:-update}"
export CORS_ORIGINS="${CORS_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173}"
export PYTHON_SERVICE_PATH="${PYTHON_SERVICE_PATH:-$PYTHON_SERVICE_DIR}"
export VITE_API_URL="${VITE_API_URL:-http://localhost:$PORT}"

if [[ "$PYTHON_SERVICE_PATH" != /* ]]; then
  export PYTHON_SERVICE_PATH="$(cd -- "$PROJECT_ROOT/$PYTHON_SERVICE_PATH" && pwd)"
fi

if [[ -z "${PROJECT_VENV_PYTHON:-}" && -x "$PYTHON_SERVICE_PATH/.venv/bin/python" ]]; then
  export PROJECT_VENV_PYTHON="$PYTHON_SERVICE_PATH/.venv/bin/python"
fi

if [[ -z "${PROJECT_VENV_PYTHON:-}" && -x "$PYTHON_SERVICE_PATH/.venv/Scripts/python.exe" ]]; then
  export PROJECT_VENV_PYTHON="$PYTHON_SERVICE_PATH/.venv/Scripts/python.exe"
fi

if [[ -z "${PROJECT_VENV_PYTHON:-}" ]]; then
  if command -v python3 >/dev/null 2>&1; then
    PYTHON_CMD="python3"
  elif command -v python >/dev/null 2>&1; then
    PYTHON_CMD="python"
  else
    echo "Python was not found. Install Python 3, then rerun this script."
    exit 1
  fi

  echo "Creating Python virtual environment..."
  "$PYTHON_CMD" -m venv "$PYTHON_SERVICE_PATH/.venv-linux"
  export PROJECT_VENV_PYTHON="$PYTHON_SERVICE_PATH/.venv-linux/bin/python"
fi

if [[ "$INSTALL_DEPS" -eq 1 ]]; then
  echo "Installing Python dependencies..."
  "$PROJECT_VENV_PYTHON" -m pip install -r "$PYTHON_SERVICE_PATH/requirements.txt"
fi

if [[ ! -f "$FRONTEND_DIR/package.json" ]]; then
  echo "Frontend package.json not found in: $FRONTEND_DIR"
  echo "Update FRONTEND_DIR in $ENV_FILE if your frontend lives somewhere else."
  exit 1
fi

echo "Writing frontend API env..."
printf 'VITE_API_URL=%s\n' "$VITE_API_URL" > "$FRONTEND_DIR/.env.local"

if [[ "$INSTALL_DEPS" -eq 1 ]]; then
  echo "Installing frontend dependencies..."
  npm install --prefix "$FRONTEND_DIR"
elif [[ ! -d "$FRONTEND_DIR/node_modules" ]]; then
  echo "Frontend dependencies are missing. Run: ./run.sh --install"
  exit 1
fi

echo "Building Java backend..."
if command -v mvn >/dev/null 2>&1; then
  (cd "$BACKEND_DIR" && mvn -DskipTests package)
else
  echo "Maven was not found. Install Maven, then rerun this script."
  exit 1
fi

cleanup() {
  if [[ -f "$BACKEND_PID_FILE" ]]; then
    BACKEND_PID="$(cat "$BACKEND_PID_FILE")"
    if [[ -n "$BACKEND_PID" ]] && kill -0 "$BACKEND_PID" >/dev/null 2>&1; then
      echo
      echo "Stopping backend..."
      kill "$BACKEND_PID" >/dev/null 2>&1 || true
    fi
    rm -f "$BACKEND_PID_FILE"
  fi
}
trap cleanup EXIT INT TERM

echo "Starting backend on http://localhost:$PORT ..."
(cd "$BACKEND_DIR" && java -jar target/network-monitoring-backend-1.0.0.jar) &
echo "$!" > "$BACKEND_PID_FILE"

echo "Starting frontend on http://127.0.0.1:5173 ..."
echo "Keep this terminal open. Press Ctrl+C to stop the app."
npm run dev --prefix "$FRONTEND_DIR"
