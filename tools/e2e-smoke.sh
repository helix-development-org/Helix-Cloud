#!/usr/bin/env bash
# End-to-end smoke test: boots the real Launcher.jar, creates a task over
# REST, lets the auto-scaler start a (fake) server, verifies heartbeats,
# routing and shutdown.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT=18080
TOKEN="smoke-token"
API="http://127.0.0.1:$PORT/api/v1"
AUTH=(-H "Authorization: Bearer $TOKEN")

log() { printf '\033[1;34m[smoke]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[smoke] FAIL:\033[0m %s\n' "$*"; cat "$WORK/node.log" 2>/dev/null | tail -40; exit 1; }

log "building Launcher.jar"
(cd "$ROOT" && ./gradlew -q :helix-node:jar)

log "compiling fake server jar"
javac -d "$WORK/classes" "$ROOT/tools/FakeServer.java"
jar --create --file "$WORK/fake-server.jar" --main-class FakeServer -C "$WORK/classes" .

log "preparing run directory $WORK/run"
mkdir -p "$WORK/run/Helix/config" "$WORK/run/Helix/cache"
cp "$WORK/fake-server.jar" "$WORK/run/Helix/cache/paper-9.9.9.jar"
cat > "$WORK/run/Helix/config/node.toml" <<EOF
[control]
host = "127.0.0.1"
port = $PORT
token = "$TOKEN"
EOF

log "starting node"
cd "$WORK/run"
mkfifo "$WORK/stdin"
(exec 3>"$WORK/stdin"; while kill -0 $$ 2>/dev/null; do sleep 1; done) &
KEEPALIVE_PID=$!
java -jar "$ROOT/helix-node/build/libs/Launcher.jar" < "$WORK/stdin" > "$WORK/node.log" 2>&1 &
NODE_PID=$!
trap 'kill $NODE_PID $KEEPALIVE_PID 2>/dev/null || true' EXIT

wait_for() { # wait_for <tries> <description> <command...>
  local tries=$1 what=$2; shift 2
  for _ in $(seq 1 "$tries"); do
    if "$@" >/dev/null 2>&1; then return 0; fi
    sleep 1
  done
  fail "timeout waiting for: $what"
}

api_contains() { curl -sf "${AUTH[@]}" "$API$1" | grep -q "$2"; }

wait_for 30 "control api up" curl -sf "${AUTH[@]}" "$API/platform/overview"
log "control api is up"

log "rejecting requests without token"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$API/tasks")" = "401" ] || fail "missing auth not rejected"

log "creating task over REST"
curl -sf "${AUTH[@]}" -X PUT "$API/tasks/Smoke" -H 'Content-Type: application/json' -d '{
  "name": "Smoke", "environment": "PAPER", "version": "9.9.9",
  "minServiceCount": 1, "maxServiceCount": 2, "startPort": 31000,
  "memoryMb": 256, "maxPlayers": 20
}' >/dev/null

log "waiting for auto-scaler to start Smoke-1"
wait_for 30 "service started" api_contains "/services" '"id":"Smoke-1"'

log "waiting for heartbeat to mark Smoke-1 RUNNING"
wait_for 30 "service RUNNING" api_contains "/services/Smoke-1" '"state":"RUNNING"'

log "checking routing snapshot"
api_contains "/internal/routing?proxyServiceId=x" '"serviceId":"Smoke-1"' || fail "routing misses backend"

log "checking service logs"
api_contains "/services/Smoke-1/logs?tail=50" "fake-server" || fail "logs missing"

log "checking action console over REST"
curl -sf "${AUTH[@]}" -X POST "$API/actions" -H 'Content-Type: application/json' \
  -d '{"action":"service.list"}' | grep -q "Smoke-1" || fail "action invoke failed"

log "stopping service and expecting auto-restart (min=1)"
FIRST_START=$(curl -sf "${AUTH[@]}" "$API/services/Smoke-1" | grep -o '"startedAtEpochMs":[0-9]*')
curl -sf "${AUTH[@]}" -X POST "$API/services/Smoke-1/stop" >/dev/null
restarted() {
  local current
  current=$(curl -sf "${AUTH[@]}" "$API/services/Smoke-1" 2>/dev/null | grep -o '"startedAtEpochMs":[0-9]*') || return 1
  [ -n "$current" ] && [ "$current" != "$FIRST_START" ]
}
wait_for 40 "service auto-restart" restarted
log "auto-restart confirmed"

log "shutting node down via platform.stop"
curl -sf "${AUTH[@]}" -X POST "$API/actions" -H 'Content-Type: application/json' \
  -d '{"action":"platform.stop"}' >/dev/null
for _ in $(seq 1 30); do
  if ! kill -0 $NODE_PID 2>/dev/null; then break; fi
  sleep 1
done
kill -0 $NODE_PID 2>/dev/null && fail "node did not exit"

grep -q "temp/Smoke-1" <<< "$(ls "$WORK/run/Helix/services/temp" 2>/dev/null)" && fail "temp workspace not cleaned"

log "PASS — full lifecycle verified (boot, auth, task, autostart, heartbeat, routing, logs, restart, shutdown)"
