#!/usr/bin/env bash
# ============================================================================
#  build-jars.sh — build the jars the live launch needs, in one shot.
#
#    1. client  MCP-1.8.9.jar         (the game)
#    2. core    core-1.8.9-all.jar    (the -javaagent Kernel fat jar)
#    3. dwm-gl  dwm-gl-1.8.9-all.jar  (pure-Java overlay backend; no Kotlin/native)
#
#  run-mcp.bat / smoke-live-gl.sh both need (1)+(2); the overlay also (3).
#
#  Usage:  scripts/build-jars.sh
# ============================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT" || exit 3

MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"

echo "== build-jars: client + core-all + dwm-gl-all =="

# client + core (and their deps) — core-all.jar is the -javaagent fat jar.
echo "--- [1/2] client + core (fat agent jar) ---"
"$MVNW" -q -pl core,client -am package -DskipTests || { echo "FAIL: core/client build"; exit 1; }

# dwm-gl depends on dwm+client as provided → they must be installed first.
echo "--- [2/2] dwm-gl fat jar (pure Java; installs dwm+client to .m2 first) ---"
"$MVNW" -q -pl dwm,client -am install -DskipTests || { echo "FAIL: dwm/client install"; exit 1; }
"$MVNW" -q -pl dwm-gl package -DskipTests || { echo "FAIL: dwm-gl build"; exit 1; }

echo
echo "== built jars =="
for j in client/target/MCP-1.8.9.jar core/target/core-1.8.9-all.jar \
         dwm-gl/target/dwm-gl-1.8.9-all.jar; do
  if [ -f "$ROOT/$j" ]; then
    sz=$(du -h "$ROOT/$j" 2>/dev/null | cut -f1)
    echo "  OK  $j  ($sz)"
  else
    echo "  --  $j  (not built)"
  fi
done
echo "Done. Launch: scripts/run-mcp.bat  (or smoke: scripts/smoke-live-gl.sh)"
