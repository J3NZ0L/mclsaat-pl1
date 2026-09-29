#!/usr/bin/env bash
#
# Stops whatever scripts/run-local.sh started. Leaves the databases and the batch-exchange
# directories alone - pass --clean to remove the run directory as well.

set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
PID_DIR="$PWD/.local-run/pids"
CLEAN=false

for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=true ;;
    *) echo "usage: $0 [--clean]" >&2; exit 2 ;;
  esac
done

if [[ ! -d "$PID_DIR" ]]; then
  echo "Nothing to stop: $PID_DIR does not exist."
else
  for pidfile in "$PID_DIR"/*.pid; do
    [[ -e "$pidfile" ]] || continue
    name="$(basename "$pidfile" .pid)"
    pid="$(cat "$pidfile")"
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid"
      # Give it a moment to shut down cleanly; Flowable's job executor in particular wants to
      # finish what it is holding rather than leave locked jobs behind.
      for _ in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
      done
      if kill -0 "$pid" 2>/dev/null; then
        echo "    $name (pid $pid) did not stop; sending SIGKILL"
        kill -9 "$pid" 2>/dev/null || true
      else
        echo "    $name (pid $pid) stopped"
      fi
    else
      echo "    $name was not running"
    fi
    rm -f "$pidfile"
  done
fi

if [[ "$CLEAN" == "true" ]]; then
  rm -rf "$PWD/.local-run"
  echo "Removed .local-run/ (logs and the batch exchange). The databases were not touched."
  echo "To reset those too: drop and re-create them with scripts/create-local-databases.sh."
fi
