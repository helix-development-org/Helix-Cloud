#!/usr/bin/env bash
# End-to-end launcher-restart test: boots the real Launcher.jar, starts a
# (fake) service, restarts via launcher.restart and verifies that the old
# service was stopped, a fresh launcher took over and started a replacement.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT=18082
TOKEN="launcher-token"
API="http://127.0.0.1:$PORT/api/v1"
AUTH=(-H "Authorization: Bearer $TOKEN")

log() { printf '\033[1;34m[launcher]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[launcher] FAIL:\033[0m %s\n' "$*"; tail -60 "$WORK/node.log" 2>/dev/null; exit 1; }

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

find_node() {
  for pid in $(pgrep -f "Launcher.jar" 2>/dev/null); do
    if [ "$(readlink "/proc/$pid/cwd" 2>/dev/null)" = "$WORK/run" ]; then
      echo "$pid"
      return 0
    fi
  done
  return 1
}
cleanup() {
  kill "$NODE_PID" "$KEEPALIVE_PID" 2>/dev/null || true
  local survivor
  survivor="$(find_node || true)"
  [ -n "$survivor" ] && kill "$survivor" 2>/dev/null || true
  pkill -f "fake-server.jar" 2>/dev/null || true
}
trap cleanup EXIT

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

log "creating task and waiting for the service"
curl -sf "${AUTH[@]}" -X PUT -H 'Content-Type: application/json' "$API/tasks/Smoke" -d '{
  "name": "Smoke", "environment": "PAPER", "version": "9.9.9",
  "minServiceCount": 1, "maxServiceCount": 1, "memoryMb": 256, "startPort": 30600
}' > /dev/null
wait_for 60 "service RUNNING" api_contains "/services" '"state":"RUNNING"'

REGISTRY="$WORK/run/Helix/services/registry.json"
OLD_WRAPPER_PID="$(grep -o '"pid": *[0-9]*' "$REGISTRY" | grep -o '[0-9]*')"
log "service Smoke-1 runs with wrapper pid $OLD_WRAPPER_PID"

log "restarting via launcher.restart"
curl -sf "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  "$API/actions" -d '{"action": "launcher.restart"}' | grep -q '"success":true' \
  || fail "launcher.restart rejected"

log "waiting for the old node process to exit"
for _ in $(seq 1 45); do kill -0 "$NODE_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$NODE_PID" 2>/dev/null && fail "old node process still alive"

kill -0 "$OLD_WRAPPER_PID" 2>/dev/null && fail "old service survived a launcher restart (must stop)"
log "old service was stopped with the launcher"

wait_for 45 "successor control api up" curl -sf "${AUTH[@]}" "$API/platform/overview"
NEW_PID="$(find_node)" || fail "successor launcher process not found"
[ "$NEW_PID" != "$NODE_PID" ] || fail "no new launcher process was spawned"
log "fresh launcher is up (pid $NEW_PID)"

log "waiting for the replacement service"
wait_for 60 "replacement service RUNNING" api_contains "/services" '"state":"RUNNING"'

log "shutting down via platform.stop"
curl -sf "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  "$API/actions" -d '{"action": "platform.stop"}' > /dev/null || true
for _ in $(seq 1 30); do kill -0 "$NEW_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$NEW_PID" 2>/dev/null && fail "successor did not stop"

log "PASS — launcher restart verified (clean service stop, fresh launcher, replacement service)"
