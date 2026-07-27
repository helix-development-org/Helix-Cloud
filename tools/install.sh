#!/usr/bin/env bash
# Helix-Cloud installer for Linux servers.
#
# Installs the Launcher.jar, a matching Java runtime, a hardened config
# with a random admin token and (by default) a systemd unit that is
# compatible with the in-place backend restart: services keep running
# headless across `/helix backend restart` because the unit uses
# KillMode=process and lets systemd respawn the launcher (exit code 10).
#
# Usage (as root):
#   ./tools/install.sh [options]
#   curl -fsSL https://raw.githubusercontent.com/Tytoss/Helix-Cloud/main/tools/install.sh | bash -s -- [options]
#
# Options:
#   --dir <path>      install directory            (default: /opt/helix)
#   --user <name>     system user running the node (default: helix)
#   --host <addr>     control API bind address     (default: 0.0.0.0)
#   --port <port>     control API port             (default: 8080)
#   --jar <path>      use an existing Launcher.jar instead of downloading/building
#   --ref <git-ref>   branch or tag for source builds (default: main)
#   --no-systemd      skip the systemd unit; installs a screen-friendly start.sh
#   --no-start        install everything but do not start the service
set -euo pipefail

REPO="helix-development-org/Helix-Cloud"
JAVA_MAJOR=24
DIR="/opt/helix"
RUN_USER="helix"
HOST="0.0.0.0"
PORT=8080
JAR=""
REF="main"
USE_SYSTEMD=1
START=1

log()  { printf '\033[1;34m[helix-install]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[helix-install]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[helix-install] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dir) DIR="$2"; shift 2 ;;
    --user) RUN_USER="$2"; shift 2 ;;
    --host) HOST="$2"; shift 2 ;;
    --port) PORT="$2"; shift 2 ;;
    --jar) JAR="$2"; shift 2 ;;
    --ref) REF="$2"; shift 2 ;;
    --no-systemd) USE_SYSTEMD=0; shift ;;
    --no-start) START=0; shift ;;
    -h|--help) grep '^#' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
done

# ---------- preconditions -------------------------------------------------
if [ "$USE_SYSTEMD" = 1 ] && [ "$(id -u)" != 0 ]; then
  die "run as root for the systemd install (or use --no-systemd --dir <writable dir>)"
fi
command -v curl >/dev/null || die "curl is required"
command -v tar  >/dev/null || die "tar is required"

case "$(uname -m)" in
  x86_64|amd64)  ARCH="x64" ;;
  aarch64|arm64) ARCH="aarch64" ;;
  *) die "unsupported architecture: $(uname -m)" ;;
esac

# ---------- java ----------------------------------------------------------
java_major() { "$1" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1; }

JAVA_BIN=""
if command -v java >/dev/null && [ "$(java_major java)" -ge "$JAVA_MAJOR" ] 2>/dev/null; then
  JAVA_BIN="$(command -v java)"
  log "using system java $(java_major java) at $JAVA_BIN"
else
  log "downloading Temurin JDK $JAVA_MAJOR ($ARCH)"
  mkdir -p "$DIR"
  JDK_URL="https://api.adoptium.net/v3/binary/latest/$JAVA_MAJOR/ga/linux/$ARCH/jdk/hotspot/normal/eclipse"
  curl -fsSL "$JDK_URL" -o "$DIR/jdk.tar.gz" || die "JDK download failed ($JDK_URL)"
  rm -rf "$DIR/jdk"
  mkdir -p "$DIR/jdk"
  tar -xzf "$DIR/jdk.tar.gz" -C "$DIR/jdk" --strip-components=1
  rm -f "$DIR/jdk.tar.gz"
  JAVA_BIN="$DIR/jdk/bin/java"
  [ -x "$JAVA_BIN" ] || die "extracted JDK has no java binary"
  log "installed JDK $(java_major "$JAVA_BIN") to $DIR/jdk"
fi

# ---------- launcher jar --------------------------------------------------
mkdir -p "$DIR"
acquire_jar() {
  if [ -n "$JAR" ]; then
    [ -f "$JAR" ] || die "no such jar: $JAR"
    cp "$JAR" "$DIR/Launcher.jar"
    log "installed Launcher.jar from $JAR"
    return
  fi
  log "looking for a GitHub release asset"
  local url
  url="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
    | grep -o '"browser_download_url": *"[^"]*Launcher\.jar"' | head -1 | cut -d'"' -f4 || true)"
  if [ -n "$url" ]; then
    curl -fsSL "$url" -o "$DIR/Launcher.jar" || die "release download failed: $url"
    log "downloaded Launcher.jar from $url"
    return
  fi
  warn "no release asset found — building from source ($REF); this needs git and a few minutes"
  command -v git >/dev/null || die "git is required for a source build (or pass --jar <path>)"
  local build
  build="$(mktemp -d)"
  git clone --depth 1 --branch "$REF" "https://github.com/$REPO.git" "$build/src" \
    || die "git clone failed"
  (cd "$build/src" && JAVA_HOME="$(dirname "$(dirname "$JAVA_BIN")")" ./gradlew -q :helix-node:jar) \
    || die "gradle build failed"
  cp "$build/src/helix-node/build/libs/Launcher.jar" "$DIR/Launcher.jar"
  rm -rf "$build"
  log "built Launcher.jar from source ($REF)"
}
acquire_jar

# ---------- config with a random admin token ------------------------------
CONFIG="$DIR/Helix/config/node.toml"
if [ -f "$CONFIG" ]; then
  log "keeping existing config $CONFIG"
else
  TOKEN="$( (openssl rand -hex 24 2>/dev/null) || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  mkdir -p "$(dirname "$CONFIG")"
  cat > "$CONFIG" <<EOF
[control]
host = "$HOST"
port = $PORT
token = "$TOKEN"
EOF
  chmod 600 "$CONFIG"
  log "wrote $CONFIG with a random admin token"
  if [ "$HOST" != "127.0.0.1" ]; then
    warn "the panel binds to $HOST:$PORT over plain HTTP — configure tlsKeystore in node.toml"
    warn "for remote use, or keep it behind a reverse proxy / firewall"
  fi
fi

# ---------- user & permissions ---------------------------------------------
if [ "$(id -u)" = 0 ]; then
  if ! id "$RUN_USER" >/dev/null 2>&1; then
    useradd --system --home-dir "$DIR" --shell /usr/sbin/nologin "$RUN_USER"
    log "created system user $RUN_USER"
  fi
  chown -R "$RUN_USER:$RUN_USER" "$DIR"
else
  RUN_USER="$(id -un)"
fi

# ---------- service --------------------------------------------------------
if [ "$USE_SYSTEMD" = 1 ]; then
  UNIT=/etc/systemd/system/helix.service
  cat > "$UNIT" <<EOF
[Unit]
Description=Helix-Cloud node
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$DIR
ExecStart=$JAVA_BIN -jar $DIR/Launcher.jar
Environment=HELIX_SYSTEMD=1

# Restart integration for /helix backend|launcher restart:
# the node exits with code 10 and systemd starts the successor.
Restart=on-failure
RestartSec=3

# Backend restarts leave the services running headless — never kill the
# whole cgroup, only the node process itself.
KillMode=process
TimeoutStopSec=180
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
  systemctl daemon-reload
  systemctl enable helix.service >/dev/null 2>&1 || true
  log "installed $UNIT"
  if [ "$START" = 1 ]; then
    systemctl restart helix.service
    log "started helix.service — waiting for the control API"
    for _ in $(seq 1 45); do
      if curl -sf "http://127.0.0.1:$PORT/" >/dev/null 2>&1; then break; fi
      sleep 1
    done
  fi
else
  cat > "$DIR/start.sh" <<EOF
#!/usr/bin/env bash
# Starts the Helix-Cloud node in the foreground (screen/tmux friendly):
#   screen -dmS helix $DIR/start.sh
cd "$DIR"
exec "$JAVA_BIN" -jar "$DIR/Launcher.jar"
EOF
  chmod +x "$DIR/start.sh"
  [ "$(id -u)" = 0 ] && chown "$RUN_USER:$RUN_USER" "$DIR/start.sh"
  log "installed $DIR/start.sh (no systemd unit)"
fi

# ---------- summary ---------------------------------------------------------
echo
log "Helix-Cloud installed to $DIR"
log "dashboard:   http://$HOST:$PORT/"
log "admin token: grep token $CONFIG"
if [ "$USE_SYSTEMD" = 1 ]; then
  log "manage:      systemctl status|stop|restart helix"
  log "logs:        journalctl -u helix -f"
else
  log "start:       screen -dmS helix $DIR/start.sh"
fi
log "in-game:     /helix language | /helix backend restart | /helix launcher restart (helix.admin)"
log "update:      replace $DIR/Launcher.jar, then run the launcher.restart action"
if ! command -v docker >/dev/null 2>&1; then
  warn "docker not found — DOCKER-executor tasks need docker installed and usable by $RUN_USER"
fi
