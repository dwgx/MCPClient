#!/usr/bin/env bash
# ============================================================
#  MCPClient - Minecraft 1.8.9 on LWJGL3 + JDK 25 (Linux/macOS)
#  Requires: JDK 25, and a built target/MCP-1.8.9.jar
#            (build with: ./mvnw -q clean package -DskipTests)
# ============================================================
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"

# Point JAVA_HOME at JDK 25 if not already set
JAVA="${JAVA_HOME:+$JAVA_HOME/bin/}java"

JAR="$HERE/client/target/MCP-1.8.9.jar"
ARGS="$HERE/jvm-args-jdk25.txt"

# macOS: GLFW needs the main thread; add -XstartOnFirstThread
EXTRA=""
if [ "$(uname)" = "Darwin" ]; then EXTRA="-XstartOnFirstThread"; fi

cd "$HERE/test_run"
exec "$JAVA" @"$ARGS" $EXTRA -cp "$JAR" net.minecraft.client.main.Main \
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties '{}'
