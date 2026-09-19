#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
bash ./build_linux.sh
exec java -cp build/classes autonewsroller.Main --command-center "$@"
