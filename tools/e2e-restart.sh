#!/usr/bin/env bash
# End-to-end backend-restart test: boots the real Launcher.jar, starts a
# (fake) service, restarts the node via platform.restart and verifies that
# the service survived headless (same wrapper PID), was re-adopted by the
# successor process and that runtime state (maintenance) was restored.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
PORT=18081
TOKEN="restart-token"
API="http://127.0.0.1:$PORT/api/v1"
AUTH=(-H "Authorization: Bearer $TOKEN")

log() { printf '\033[1;34m[restart]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[restart] FAIL:\033[0m %s\n' "$*"; tail -60 "$WORK/node.log" 2>/dev/null; exit 1; }

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

# the successor node is not our child — find it via its working directory
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
log "control api is up"

log "creating task and waiting for the service"
curl -sf "${AUTH[@]}" -X PUT -H 'Content-Type: application/json' "$API/tasks/Smoke" -d '{
  "name": "Smoke", "environment": "PAPER", "version": "9.9.9",
  "minServiceCount": 1, "maxServiceCount": 1, "memoryMb": 256, "startPort": 30500
}' > /dev/null
wait_for 60 "service RUNNING" api_contains "/services" '"state":"RUNNING"'

REGISTRY="$WORK/run/Helix/services/registry.json"
WRAPPER_PID="$(grep -o '"pid": *[0-9]*' "$REGISTRY" | grep -o '[0-9]*')"
[ -n "$WRAPPER_PID" ] || fail "no wrapper pid in $REGISTRY"
log "service Smoke-1 runs with wrapper pid $WRAPPER_PID"

log "enabling maintenance (state must survive the restart)"
curl -sf "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  "$API/proxy/maintenance" -d '{"enabled": true}' > /dev/null

log "restarting the backend via platform.restart"
curl -sf "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  "$API/actions" -d '{"action": "platform.restart"}' | grep -q '"success":true' \
  || fail "platform.restart rejected"

log "waiting for the old node process to exit"
for _ in $(seq 1 30); do kill -0 "$NODE_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$NODE_PID" 2>/dev/null && fail "old node process still alive"

kill -0 "$WRAPPER_PID" 2>/dev/null || fail "wrapper died with the node — service did not survive headless"
log "wrapper survived the node exit"

wait_for 45 "successor control api up" curl -sf "${AUTH[@]}" "$API/platform/overview"
NEW_PID="$(find_node)" || fail "successor node process not found"
[ "$NEW_PID" != "$NODE_PID" ] || fail "no new node process was spawned"
log "successor node is up (pid $NEW_PID)"

wait_for 30 "service re-adopted as RUNNING" api_contains "/services" '"state":"RUNNING"'
grep -q "\"pid\": *$WRAPPER_PID" "$REGISTRY" || fail "adopted service has a different pid"
kill -0 "$WRAPPER_PID" 2>/dev/null || fail "wrapper no longer alive after adoption"
log "service was re-adopted with the same wrapper pid"

api_contains "/proxy" '"maintenance":true' || fail "maintenance flag did not survive the restart"
log "maintenance flag survived the restart"

log "console command still reaches the adopted service"
curl -sf "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  "$API/services/Smoke-1/command" -d '{"command": "ping"}' > /dev/null

log "shutting the successor down via platform.stop"
curl -sf "${AUTH[@]}" -X POST -H 'Content-Type: application/json' \
  "$API/actions" -d '{"action": "platform.stop"}' > /dev/null || true
for _ in $(seq 1 30); do kill -0 "$NEW_PID" 2>/dev/null || break; sleep 1; done
kill -0 "$NEW_PID" 2>/dev/null && fail "successor did not stop"
kill -0 "$WRAPPER_PID" 2>/dev/null && fail "service still running after platform.stop"

log "PASS — backend restart verified (headless survival, re-adoption, state restore, clean stop)"
