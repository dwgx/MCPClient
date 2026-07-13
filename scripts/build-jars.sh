#!/usr/bin/env bash
# ============================================================================
#  build-jars.sh — build the three jars the live launch needs, in one shot.
#
#    1. client  MCP-1.8.9.jar          (the game)
#    2. core    core-1.8.9-all.jar     (the -javaagent Kernel fat jar)
#    3. dwm-compose  dwm-compose-1.8.9-all.jar  (OPTIONAL Compose overlay backend;
#       Kotlin+Compose+Skiko fat jar added to -cp by run-mcp.bat if present)
#
#  run-mcp.bat / smoke-live-gl.sh both need (1)+(2); the Compose overlay also (3).
#  Pass --no-compose to skip the heavy dwm-compose build.
#
#  Usage:  scripts/build-jars.sh [--no-compose]
# ============================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT" || exit 3

MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"
COMPOSE=1
[ "${1:-}" = "--no-compose" ] && COMPOSE=0

echo "== build-jars: client + core-all$([ $COMPOSE = 1 ] && echo ' + dwm-compose-all') =="

# client + core (and their deps) — core-all.jar is the -javaagent fat jar.
echo "--- [1/2] client + core (fat agent jar) ---"
"$MVNW" -q -pl core,client -am package -DskipTests || { echo "FAIL: core/client build"; exit 1; }

if [ "$COMPOSE" = "1" ]; then
  # dwm-compose depends on dwm+client as provided → they must be installed first.
  echo "--- [2/2] dwm-compose fat jar (installs dwm+client to .m2 first) ---"
  "$MVNW" -q -pl dwm,client -am install -DskipTests || { echo "FAIL: dwm/client install"; exit 1; }
  "$MVNW" -q -pl dwm-compose package -DskipTests || { echo "FAIL: dwm-compose build"; exit 1; }
fi

echo
echo "== built jars =="
for j in client/target/MCP-1.8.9.jar core/target/core-1.8.9-all.jar \
         dwm-compose/target/dwm-compose-1.8.9-all.jar; do
  if [ -f "$ROOT/$j" ]; then
    sz=$(du -h "$ROOT/$j" 2>/dev/null | cut -f1)
    echo "  OK  $j  ($sz)"
  else
    echo "  --  $j  (not built)"
  fi
done
echo "Done. Launch: scripts/run-mcp.bat  (or smoke: scripts/smoke-live-gl.sh)"
