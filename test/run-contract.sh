#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
port=${PORT:-8788}
state=$(mktemp -d)
device=contract-test-device-token
hash=$(printf %s "$device" | sha256sum | cut -d' ' -f1)
setsid npx wrangler dev --port "$port" --persist-to "$state" --var "DEVICE_TOKEN_HASH:$hash" > "$state/wrangler.log" 2>&1 &
pid=$!
trap 'kill -- -$pid 2>/dev/null; rm -rf "$state"' EXIT
for _ in $(seq 60); do curl -sf "http://127.0.0.1:$port/" > /dev/null && break; sleep 1; done
mcp=$(grep '^MCP_TOKEN=' .dev.vars | cut -d= -f2-)
node test/contract.mjs "http://127.0.0.1:$port" "$mcp" "$device"
