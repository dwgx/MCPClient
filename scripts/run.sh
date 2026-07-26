#!/usr/bin/env bash
# ============================================================
#  MCPClient - Minecraft 1.8.9 on LWJGL3 + JDK 25 (Linux/macOS)
#  Requires: JDK 25, and a built target/MCP-1.8.9.jar
#            (build with: ./mvnw -q clean package -DskipTests)
# ============================================================
set -e
# This script now lives in scripts/; ROOT is the project root.
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# Locate a JDK 25. The argfile passes flags (--sun-misc-unsafe-memory-access,
# --enable-native-access) that older JVMs reject outright, so falling back to
# whatever `java` is on PATH fails with an unhelpful "Unrecognized option".
pick_java() {
  for cand in \
      "${JAVA_HOME:+$JAVA_HOME/bin/java}" \
      "$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java" \
      "$HOME"/.jdks/jdk-25*/Contents/Home/bin/java \
      "$HOME"/.jdks/jdk-25*/bin/java; do
    [ -x "$cand" ] || continue
    case "$("$cand" -version 2>&1 | head -1)" in
      *\"25*) echo "$cand"; return 0 ;;
    esac
  done
  command -v java
}
JAVA="$(pick_java)"
case "$("$JAVA" -version 2>&1 | head -1)" in
  *\"25*) ;;
  *) echo "warning: $JAVA is not JDK 25 — the argfile in jvm-args-jdk25.txt will be rejected" >&2 ;;
esac

JAR="$ROOT/client/target/MCP-1.8.9.jar"
ARGS="$HERE/jvm-args-jdk25.txt"

# macOS: GLFW needs the main thread; add -XstartOnFirstThread
EXTRA=""
if [ "$(uname)" = "Darwin" ]; then
  # GLFW must own the main thread.
  EXTRA="-XstartOnFirstThread"
  # ...and once GLFW owns it, AWT must never start AppKit on it: GuiScreen's
  # vanilla clipboard helpers go through java.awt.Toolkit, and after AppKit comes
  # up alongside GLFW the JVM can no longer exit (verified: copy succeeds, then
  # quit wedges). Headless makes Toolkit throw instead, which those helpers
  # already swallow. Cost: no clipboard in vanilla text fields on macOS — see
  # docs/macos/known-issues.md. ImageIO/BufferedImage (screenshots, textures)
  # are headless-safe, so nothing else regresses.
  EXTRA="$EXTRA -Djava.awt.headless=true"
fi

cd "$ROOT/test_run"
exec "$JAVA" @"$ARGS" $EXTRA -cp "$JAR" net.minecraft.client.main.Main \
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties '{}'
