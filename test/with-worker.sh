#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
port=${PORT:-8788}
state=$(mktemp -d)
setsid npx wrangler dev --port "$port" --persist-to "$state" > "$state/wrangler.log" 2>&1 &
pid=$!
trap 'kill -- -$pid 2>/dev/null; rm -rf "$state"' EXIT
for _ in $(seq 60); do curl -sf "http://127.0.0.1:$port/" > /dev/null && break; sleep 1; done
node "$1" "http://127.0.0.1:$port"
