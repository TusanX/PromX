#!/bin/zsh
set -e

ROOT_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT_DIR"

node tools/download-stats/server.mjs &
SERVER_PID=$!
sleep 0.8
open 'http://127.0.0.1:8788'
wait "$SERVER_PID"
