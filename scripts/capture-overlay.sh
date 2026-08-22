#!/usr/bin/env bash
# ============================================================================
#  capture-overlay.sh — launch MC with the qml4j DWM overlay ARMED, wait for
#  the MCP facade, GET /v1/screen, write the PNG, then tear the client down.
#
#  There is one substrate: qml4j as a real GuiScreen (dwm/README.md).
#  The gl / imgui / skiko jars are gone. Passing those names is a setup error.
#
#    Usage:  scripts/capture-overlay.sh [qml4j] [outfile] [--keep] [--warmup S]
#      outfile   PNG path (default: scripts/_capture/qml4j.png)
#      --keep    leave the client running
#      --warmup  seconds to let the menu settle before capture (default 8)
#
#  Exit: 0 captured PNG written; 2 facade timeout; 3 setup.
# ============================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BACKEND=""
OUT=""
KEEP=0
WARMUP=8
POS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --keep) KEEP=1;;
    --warmup) shift; WARMUP="${1:-8}";;
    *) if [ "$POS" = "0" ]; then BACKEND="$1"; POS=1; else OUT="$1"; fi;;
  esac
  shift
done

case "${BACKEND}" in
  ""|qml4j) BACKEND="qml4j" ;;
  skiko-ui|gl-ui|imgui-ui|gl|imgui|skiko)
    echo "SETUP FAIL: overlay backend '$BACKEND' was demolished. qml4j is the substrate (dwm/README.md)." >&2
    exit 3
    ;;
  *)
    echo "SETUP FAIL: unknown overlay '$BACKEND' (only qml4j)." >&2
    exit 3
    ;;
esac

[ -z "$OUT" ] && OUT="$SCRIPT_DIR/_capture/qml4j.png"
mkdir -p "$(dirname "$OUT")"

JBR_HOME="${JBR_HOME:-$ROOT/_tools/jbrsdk-25.0.3-windows-x64-b508.16}"
JAVA="$JBR_HOME/bin/java.exe"; [ -x "$JAVA" ] || JAVA="$JBR_HOME/bin/java"
GAME_JAR="$ROOT/client/target/MCP-1.8.9.jar"
CORE_JAR="$ROOT/core/target/core-1.8.9-all.jar"
BOARD_JAR="$ROOT/board/target/board-1.8.9.jar"
DWM_JAR="$ROOT/dwm/target/dwm-1.8.9.jar"
DWM_CP_CACHE="$ROOT/dwm/target/runtime-classpath.txt"
ARGS_FILE="$SCRIPT_DIR/jvm-args-mcp.txt"
GAME_DIR="$ROOT/test_run"
HOST=127.0.0.1; PORT=1337
LAUNCH_LOG="$SCRIPT_DIR/_capture/launch-qml4j.log"

fail_setup(){ echo "SETUP FAIL: $1" >&2; exit 3; }
[ -x "$JAVA" ]     || fail_setup "no java at $JAVA"
[ -f "$GAME_JAR" ] || fail_setup "missing $GAME_JAR"
[ -f "$CORE_JAR" ] || fail_setup "missing $CORE_JAR"
[ -f "$DWM_JAR" ]  || fail_setup "missing $DWM_JAR — ./mvnw -q -pl dwm -am package -DskipTests"
command -v curl >/dev/null 2>&1 || fail_setup "curl not on PATH"

winpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
if command -v cygpath >/dev/null 2>&1; then CPSEP=';'; else CPSEP=':'; fi

if [ ! -f "$DWM_CP_CACHE" ] || [ "$ROOT/dwm/pom.xml" -nt "$DWM_CP_CACHE" ]; then
  echo "== resolving dwm runtime dependencies (qml4j / Skija / asm) =="
  ( cd "$ROOT" && ./mvnw -q -ntp -pl dwm dependency:build-classpath \
      -DincludeScope=runtime -Dmdep.outputFile="$DWM_CP_CACHE" ) \
    || fail_setup "could not resolve dwm dependencies"
fi
grep -q "qml4j-core" "$DWM_CP_CACHE" || fail_setup "dwm classpath cache missing qml4j-core"

CP="$(winpath "$GAME_JAR")$CPSEP$(winpath "$CORE_JAR")"
[ -f "$BOARD_JAR" ] && CP="$CP$CPSEP$(winpath "$BOARD_JAR")"
CP="$CP$CPSEP$(winpath "$DWM_JAR")"
# The Maven cache is already in host path form.
CP="$CP$CPSEP$(tr -d '\r\n' < "$DWM_CP_CACHE")"

GAME_PID=""
cleanup(){ [ "$KEEP" = "1" ] || { [ -n "$GAME_PID" ] && kill "$GAME_PID" 2>/dev/null; }; }
trap cleanup EXIT

echo "== capture-overlay: qml4j -> $OUT =="
mkdir -p "$(dirname "$LAUNCH_LOG")"
( cd "$GAME_DIR" && exec "$JAVA" "@$(winpath "$ARGS_FILE")" \
    -Dmcp.core.http=true -Dmcp.core.httpPort="$PORT" -Dmcp.core.httpBind="$HOST" \
    -Dmcp.core.overlay=true \
    -javaagent:"$(winpath "$CORE_JAR")" \
    -cp "$CP" \
    net.minecraft.client.main.Main \
    --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}" \
) >"$LAUNCH_LOG" 2>&1 &
GAME_PID=$!
echo "  game pid: $GAME_PID  (log: $LAUNCH_LOG)"

deadline=$(( $(date +%s) + 180 )); up=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  if [ -n "$GAME_PID" ] && ! kill -0 "$GAME_PID" 2>/dev/null; then
    echo "FAIL: client exited before facade. Tail:" >&2; tail -n 30 "$LAUNCH_LOG" >&2; exit 2
  fi
  if curl -fsS -m 3 "http://$HOST:$PORT/v1/models" >/dev/null 2>&1; then up=1; break; fi
  sleep 2
done
[ "$up" = "1" ] || { echo "TIMEOUT: facade never bound" >&2; tail -n 30 "$LAUNCH_LOG" >&2; exit 2; }
echo "  facade up. warming up ${WARMUP}s for the menu + overlay to settle ..."
sleep "$WARMUP"

echo "  calling /v1/screen ..."
curl -fsS -m 30 "http://$HOST:$PORT/v1/screen" -o "$OUT"
rc=$?
if [ "$rc" = "0" ] && [ -s "$OUT" ]; then
  echo "  wrote $(wc -c < "$OUT") bytes -> $OUT"
else
  echo "FAIL: /v1/screen returned nothing (rc=$rc)" >&2; rc=2
fi
echo "== done (rc=$rc) =="
exit $rc
