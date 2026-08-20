#!/usr/bin/env bash
# ============================================================================
#  build-jars.sh — jars the live launch needs, in one shot.
#
#    1. client  MCP-1.8.9.jar
#    2. core    core-1.8.9-all.jar
#    3. board   board-1.8.9.jar
#    4. dwm     dwm-1.8.9.jar  (qml4j GuiScreen + runtime-classpath.txt)
#
#  Usage:  scripts/build-jars.sh
# ============================================================================
set -u
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT" || exit 3

MVNW="./mvnw"
[ -x "$MVNW" ] || MVNW="mvn"

echo "== build-jars: client + core-all + board + dwm =="

echo "--- [1/2] client + core + board ---"
"$MVNW" -q -pl core,client,board -am package -DskipTests || { echo "FAIL: core/client/board"; exit 1; }

echo "--- [2/2] dwm + runtime classpath cache ---"
"$MVNW" -q -pl dwm -am package -DskipTests || { echo "FAIL: dwm"; exit 1; }
"$MVNW" -q -ntp -pl dwm dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile="dwm/target/runtime-classpath.txt" \
  || { echo "FAIL: dwm runtime classpath"; exit 1; }

echo
echo "== built jars =="
for j in client/target/MCP-1.8.9.jar core/target/core-1.8.9-all.jar \
         board/target/board-1.8.9.jar dwm/target/dwm-1.8.9.jar \
         dwm/target/runtime-classpath.txt; do
  if [ -f "$ROOT/$j" ]; then
    echo "  OK  $j"
  else
    echo "  --  $j  (not built)"
  fi
done
echo "Done. Launch: scripts/run-mcp.sh  (Windows: scripts/run-mcp.bat)"
