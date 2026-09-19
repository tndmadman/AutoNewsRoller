#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command -v javac >/dev/null 2>&1 || { echo "ERROR: JDK 21+ javac is required."; exit 1; }
mkdir -p build/classes
mapfile -d '' SOURCES < <(find src -type f -name '*.java' -print0 | sort -z)
if [ "${#SOURCES[@]}" -eq 0 ]; then echo "ERROR: no Java sources found."; exit 1; fi
javac -encoding UTF-8 -d build/classes "${SOURCES[@]}"
echo "Java compile passed."
