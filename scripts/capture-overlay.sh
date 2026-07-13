#!/usr/bin/env bash
# ============================================================================
#  capture-overlay.sh — launch MC with a chosen overlay backend ARMED, wait for
#  the MCP facade, call capture_screen, decode the returned base64 PNG to a file,
#  then tear the client down. Lets the agent VISUALLY verify the overlay itself.
#
#    Usage:  scripts/capture-overlay.sh <backend> [outfile] [--keep] [--warmup S]
#      backend   skiko-ui | gl-ui | imgui-ui | gl | imgui   (or "" = auto)
#      outfile   PNG path (default: scripts/_capture/<backend>.png)
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
[ -z "$OUT" ] && OUT="$SCRIPT_DIR/_capture/${BACKEND:-auto}.png"
mkdir -p "$(dirname "$OUT")"

JBR_HOME="${JBR_HOME:-$ROOT/_tools/jbrsdk-25.0.3-windows-x64-b508.16}"
JAVA="$JBR_HOME/bin/java.exe"; [ -x "$JAVA" ] || JAVA="$JBR_HOME/bin/java"
GAME_JAR="$ROOT/client/target/MCP-1.8.9.jar"
CORE_JAR="$ROOT/core/target/core-1.8.9-all.jar"
GL_JAR="$ROOT/dwm-gl/target/dwm-gl-1.8.9-all.jar"
IMGUI_JAR="$ROOT/dwm-imgui/target/dwm-imgui-1.8.9-all.jar"
SKIKO_JAR="$ROOT/dwm-skiko/target/dwm-skiko-1.8.9-all.jar"
ARGS_FILE="$SCRIPT_DIR/jvm-args-mcp.txt"
GAME_DIR="$ROOT/test_run"
HOST=127.0.0.1; PORT=1337
LAUNCH_LOG="$SCRIPT_DIR/_capture/launch-${BACKEND:-auto}.log"

fail_setup(){ echo "SETUP FAIL: $1" >&2; exit 3; }
[ -x "$JAVA" ]     || fail_setup "no java at $JAVA"
[ -f "$GAME_JAR" ] || fail_setup "missing $GAME_JAR"
[ -f "$CORE_JAR" ] || fail_setup "missing $CORE_JAR"
command -v curl >/dev/null 2>&1 || fail_setup "curl not on PATH"

winpath(){ if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }
if command -v cygpath >/dev/null 2>&1; then CPSEP=';'; else CPSEP=':'; fi

# Classpath: game + kernel + whichever backend jars exist.
CP="$(winpath "$GAME_JAR")$CPSEP$(winpath "$CORE_JAR")"
[ -f "$GL_JAR" ]    && CP="$CP$CPSEP$(winpath "$GL_JAR")"
[ -f "$IMGUI_JAR" ] && CP="$CP$CPSEP$(winpath "$IMGUI_JAR")"
[ -f "$SKIKO_JAR" ] && CP="$CP$CPSEP$(winpath "$SKIKO_JAR")"

# Native lib flags for imgui + skiko (harmless when those backends unused).
NATIVE_OPTS=()
if [ -f "$IMGUI_JAR" ]; then
  IMG_DIR="$ROOT/dwm-imgui/target/imgui-native"
  if [ ! -f "$IMG_DIR/imgui-java64.dll" ]; then
    mkdir -p "$IMG_DIR"; ( cd "$ROOT" && "$JBR_HOME/bin/jar" xf "$IMGUI_JAR" io/imgui/java/native-bin/imgui-java64.dll \
      && mv io/imgui/java/native-bin/imgui-java64.dll "$IMG_DIR/" && rm -rf io ) 2>/dev/null
  fi
  NATIVE_OPTS+=("-Dimgui.library.path=$(winpath "$IMG_DIR")")
fi
[ -f "$SKIKO_JAR" ] && NATIVE_OPTS+=("-Dskiko.renderApi=OPENGL")

BACKEND_OPT=()
[ -n "$BACKEND" ] && BACKEND_OPT+=("-Dmcp.core.overlay.backend=$BACKEND")

GAME_PID=""
cleanup(){ [ "$KEEP" = "1" ] || { [ -n "$GAME_PID" ] && kill "$GAME_PID" 2>/dev/null; }; }
trap cleanup EXIT

echo "== capture-overlay: backend='${BACKEND:-auto}' -> $OUT =="
mkdir -p "$(dirname "$LAUNCH_LOG")"
( cd "$GAME_DIR" && exec "$JAVA" "@$(winpath "$ARGS_FILE")" \
    -Dmcp.core.http=true -Dmcp.core.httpPort="$PORT" -Dmcp.core.httpBind="$HOST" \
    -Dmcp.core.overlay=true "${BACKEND_OPT[@]}" "${NATIVE_OPTS[@]}" \
    -javaagent:"$(winpath "$CORE_JAR")" \
    -cp "$CP" \
    net.minecraft.client.main.Main \
    --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}" \
) >"$LAUNCH_LOG" 2>&1 &
GAME_PID=$!
echo "  game pid: $GAME_PID  (log: $LAUNCH_LOG)"

# Wait for facade.
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

# capture_screen -> base64 PNG in content[].data
echo "  calling capture_screen ..."
# The facade omits base64 from the tool JSON and streams the raw PNG at /v1/screen.
curl -fsS -m 30 "http://$HOST:$PORT/v1/screen" -o "$OUT"
rc=$?
if [ "$rc" = "0" ] && [ -s "$OUT" ]; then
  echo "  wrote $(wc -c < "$OUT") bytes -> $OUT"
else
  echo "FAIL: /v1/screen returned nothing (rc=$rc)" >&2; rc=2
fi
echo "== done (rc=$rc) =="
exit $rc
