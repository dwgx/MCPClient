#!/usr/bin/env bash
# ============================================================================
#  smoke-live-gl.sh — "is the live MC client healthy + is its GL context
#  readable" smoke test for MCPClient (the Kernel).
#
#  WHAT IT DOES
#    1. Launches MC 1.8.9 WITH the MCP Core Kernel attached (same invocation as
#       scripts/run-mcp.bat: fat agent jar as -javaagent + on -cp), in the
#       background, cwd = test_run (game dir).
#    2. Waits for the MCP Core HTTP facade to bind 127.0.0.1:1337.
#    3. Calls the dev_probe MCP tool (POST /v1/tools/dev_probe) and reads the
#       returned live GL context section (version / vendor / renderer / profile).
#    4. ASSERTS: facade up  AND  game.up == true  AND  gl.present == true.
#    5. Tears the client down (unless --keep).
#
#  This is the standing "live client health" gate: headless unit tests prove
#  logic; THIS proves the running game actually booted a real GL context on a
#  real GPU and the Kernel can read it over MCP.
#
#  REQUIREMENTS (a REAL machine with a display — this cannot pass headless):
#    - JetBrains Runtime 25 at _tools/jbrsdk-25.0.3-windows-x64-b508.16
#      (or export JBR_HOME=... to override).
#    - Built jars:  client/target/MCP-1.8.9.jar  and  core/target/core-1.8.9-all.jar
#      (build:  ./mvnw -q -pl core -am package -DskipTests   and the client jar).
#    - curl on PATH (git-bash / MSYS ships it on Windows).
#
#  USAGE
#    scripts/smoke-live-gl.sh [--timeout SECONDS] [--keep] [--port PORT]
#      --timeout N   how long to wait for the facade to come up (default 150s)
#      --keep        leave the client running after the probe (default: kill it)
#      --port N      facade port (default 1337; matches HttpFacade.DEFAULT_PORT)
#
#  EXIT CODES
#    0  PASS  — client alive and GL context readable
#    1  FAIL  — facade came up but game not up or GL context absent
#    2  TIMEOUT — facade never bound within --timeout (launch/display failure)
#    3  SETUP  — missing jar / JBR / curl (prerequisite not met)
# ============================================================================
set -u

# --- resolve paths (script lives in scripts/, project root is one up) --------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

JBR_HOME="${JBR_HOME:-$ROOT/_tools/jbrsdk-25.0.3-windows-x64-b508.16}"
JAVA="$JBR_HOME/bin/java.exe"
[ -x "$JAVA" ] || JAVA="$JBR_HOME/bin/java"   # non-Windows fallback

# Paths default to the layout under ROOT, but each can be overridden by env so
# the harness is runnable from a checkout that doesn't hold the built jars.
GAME_JAR="${GAME_JAR:-$ROOT/client/target/MCP-1.8.9.jar}"
CORE_JAR="${CORE_JAR:-$ROOT/core/target/core-1.8.9-all.jar}"
ARGS_FILE="${ARGS_FILE:-$SCRIPT_DIR/jvm-args-mcp.txt}"
GAME_DIR="${GAME_DIR:-$ROOT/test_run}"

# --- args --------------------------------------------------------------------
TIMEOUT=150
KEEP=0
PORT=1337
HOST=127.0.0.1
while [ $# -gt 0 ]; do
  case "$1" in
    --timeout) TIMEOUT="$2"; shift 2;;
    --keep)    KEEP=1; shift;;
    --port)    PORT="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 3;;
  esac
done

echo "== MCPClient live-GL smoke test =="
echo "  root      : $ROOT"
echo "  JBR       : $JBR_HOME"
echo "  facade    : http://$HOST:$PORT"
echo

# --- prerequisites -----------------------------------------------------------
fail_setup() { echo "SETUP FAIL: $1" >&2; exit 3; }
[ -x "$JAVA" ]        || fail_setup "no java at $JAVA (set JBR_HOME)"
[ -f "$GAME_JAR" ]    || fail_setup "missing client jar $GAME_JAR (build the client module)"
[ -f "$CORE_JAR" ]    || fail_setup "missing agent jar $CORE_JAR (./mvnw -q -pl core -am package -DskipTests)"
[ -f "$ARGS_FILE" ]   || fail_setup "missing $ARGS_FILE"
[ -d "$GAME_DIR" ]    || mkdir -p "$GAME_DIR"
command -v curl >/dev/null 2>&1 || fail_setup "curl not on PATH"

# refuse to run if something is already on the port (ambiguous target)
if curl -fsS -m 2 "http://$HOST:$PORT/v1/models" >/dev/null 2>&1; then
  echo "NOTE: something is ALREADY serving $HOST:$PORT — probing the existing instance instead of launching." >&2
  ALREADY_UP=1
else
  ALREADY_UP=0
fi

LAUNCH_LOG="$GAME_DIR/smoke-launch.$$.log"
GAME_PID=""

# On MSYS/Cygwin the `$!` we captured is the shell's child, NOT the native
# java.exe PID — taskkill //PID on it kills nothing and orphans the JVM. So on
# Windows we resolve the ACTUAL listener PID from the port owner (netstat) and
# kill that tree. On POSIX $! is the real PID and plain kill works.
NATIVE_PID=""   # set to the real java.exe pid once the facade is up (Windows)
cleanup() {
  if [ "$KEEP" = "1" ]; then
    echo "  (--keep) leaving client running (facade $HOST:$PORT)"
    return
  fi
  # We only own the process if we launched it.
  [ "$ALREADY_UP" = "0" ] || return
  if command -v taskkill >/dev/null 2>&1; then
    local pid="${NATIVE_PID:-}"
    # Fall back to whatever currently owns the port.
    [ -z "$pid" ] && pid="$(netstat -ano 2>/dev/null | grep -E "127\.0\.0\.1:$PORT .*LISTENING" | awk '{print $NF}' | head -1)"
    if [ -n "$pid" ]; then
      echo "  tearing down client (java.exe pid $pid)..."
      taskkill //PID "$pid" //T //F >/dev/null 2>&1
    else
      echo "  (cleanup) could not resolve java.exe pid on port $PORT; check for orphans." >&2
    fi
  elif [ -n "$GAME_PID" ]; then
    echo "  tearing down client (pid $GAME_PID)..."
    kill "$GAME_PID" >/dev/null 2>&1
  fi
}
trap cleanup EXIT

# --- launch (unless already up) ---------------------------------------------
# On MSYS/Cygwin the JVM is a native Windows binary that cannot read /x/... unix
# paths in -cp / -javaagent / @argfile, so translate those to Windows form. On a
# real POSIX host cygpath is absent and the paths are already native.
winpath() { if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi; }

if [ "$ALREADY_UP" = "0" ]; then
  echo "launching client with Kernel (log -> $LAUNCH_LOG) ..."
  W_GAME_JAR="$(winpath "$GAME_JAR")"
  W_CORE_JAR="$(winpath "$CORE_JAR")"
  W_ARGS="$(winpath "$ARGS_FILE")"
  if command -v cygpath >/dev/null 2>&1; then CPSEP=';'; else CPSEP=':'; fi
  ( cd "$GAME_DIR" && exec "$JAVA" "@$W_ARGS" \
      -Dmcp.core.http=true -Dmcp.core.httpPort="$PORT" -Dmcp.core.httpBind="$HOST" \
      -javaagent:"$W_CORE_JAR" \
      -cp "$W_GAME_JAR$CPSEP$W_CORE_JAR" \
      net.minecraft.client.main.Main \
      --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}" \
  ) >"$LAUNCH_LOG" 2>&1 &
  GAME_PID=$!
  echo "  game pid: $GAME_PID"
fi

# --- wait for the facade -----------------------------------------------------
echo "waiting up to ${TIMEOUT}s for facade on $HOST:$PORT ..."
deadline=$(( $(date +%s) + TIMEOUT ))
up=0
while [ "$(date +%s)" -lt "$deadline" ]; do
  # if we launched and the process died, stop waiting
  if [ "$ALREADY_UP" = "0" ] && [ -n "$GAME_PID" ] && ! kill -0 "$GAME_PID" 2>/dev/null; then
    echo "FAIL: client process exited before facade came up. Tail of launch log:" >&2
    tail -n 30 "$LAUNCH_LOG" >&2
    exit 2
  fi
  if curl -fsS -m 3 "http://$HOST:$PORT/v1/models" >/dev/null 2>&1; then
    up=1; break
  fi
  sleep 2
done

if [ "$up" != "1" ]; then
  echo "TIMEOUT: facade never bound $HOST:$PORT within ${TIMEOUT}s." >&2
  [ -f "$LAUNCH_LOG" ] && { echo "--- launch log tail ---" >&2; tail -n 30 "$LAUNCH_LOG" >&2; }
  exit 2
fi
echo "  facade UP."
# Resolve the real native java.exe PID (the port owner) so cleanup can kill it.
if [ "$ALREADY_UP" = "0" ] && command -v netstat >/dev/null 2>&1; then
  NATIVE_PID="$(netstat -ano 2>/dev/null | grep -E "127\.0\.0\.1:$PORT .*LISTENING" | awk '{print $NF}' | head -1)"
  [ -n "$NATIVE_PID" ] && echo "  (java.exe pid $NATIVE_PID owns $HOST:$PORT)"
fi

# --- call dev_probe ----------------------------------------------------------
echo "calling dev_probe ..."
PROBE="$(curl -fsS -m 15 -X POST "http://$HOST:$PORT/v1/tools/dev_probe" \
          -H 'Content-Type: application/json' -d '{}' 2>/dev/null)"
if [ -z "$PROBE" ]; then
  echo "FAIL: dev_probe returned empty." >&2
  exit 1
fi
echo "--- dev_probe raw response ---"
echo "$PROBE"
echo "------------------------------"

# --- assert (grep-based; no jq dependency) -----------------------------------
# The tool wraps the probe JSON as a STRING inside the outer response, so the
# inner quotes arrive backslash-escaped (\"up\":true). Strip backslashes and
# whitespace first, then match the flat "key":true form.
FLAT="$(printf '%s' "$PROBE" | tr -d '\\ \t\n')"
game_up=0;    printf '%s' "$FLAT" | grep -Eq '"up":true'      && game_up=1
gl_present=0; printf '%s' "$FLAT" | grep -Eq '"present":true' && gl_present=1

echo
echo "== RESULT =="
echo "  game.up     : $([ $game_up = 1 ] && echo YES || echo no)"
echo "  gl.present  : $([ $gl_present = 1 ] && echo YES || echo no)"
# surface the GL identity strings (un-escape the inner quotes first)
printf '%s' "$PROBE" | sed 's/\\"/"/g' \
  | grep -oE '"version":"[^"]*"|"vendor":"[^"]*"|"renderer":"[^"]*"|"note":"[^"]*"' \
  | sed 's/^/  gl./'

if [ "$game_up" = "1" ] && [ "$gl_present" = "1" ]; then
  echo "PASS: live client healthy and GL context readable over MCP."
  exit 0
fi
echo "FAIL: game up=$game_up gl.present=$gl_present (facade responded but health assertion failed)." >&2
exit 1
