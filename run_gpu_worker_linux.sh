#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash ./build_linux.sh
CONTROLLER="${1:-http://127.0.0.1:8787}"
WORKER_ID="${2:-$(hostname)}"
exec java -cp build/classes autonewsroller.Main --worker --controller-url "$CONTROLLER" --worker-id "$WORKER_ID"
