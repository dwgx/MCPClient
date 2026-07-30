#!/usr/bin/env bash
# ============================================================
#  MCPClient - MC 1.8.9 + MCP Core (Linux/macOS)
#  The .sh counterpart of run-mcp.bat: launches the game WITH the Kernel
#  attached. The fat agent jar is both -javaagent (startup hook +
#  Instrumentation) and on -cp (Core + MCP SDK + Byte Buddy).
#  MCP server listens on 127.0.0.1:25599.
#
#  Build first (from the project root):
#      ./mvnw -q -pl core -am package -DskipTests
#      ./mvnw -q -pl dwm -am package -DskipTests     # optional, for the UI
# ============================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"

# --- JDK 25. The argfile passes flags older JVMs reject outright, so falling back to
#     whatever `java` is on PATH fails with an unhelpful "Unrecognized option". Mirrors
#     run.sh's picker rather than reinventing it. ---
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
  *) echo "warning: $JAVA is not JDK 25 — the argfile will likely be rejected" >&2 ;;
esac

GAME_JAR="$ROOT/client/target/MCP-1.8.9.jar"
CORE_JAR="$ROOT/core/target/core-1.8.9-all.jar"
BOARD_JAR="$ROOT/board/target/board-1.8.9.jar"
DWM_JAR="$ROOT/dwm/target/dwm-1.8.9.jar"

for required in "$GAME_JAR" "$CORE_JAR"; do
  if [ ! -f "$required" ]; then
    echo "missing $required — build first:" >&2
    echo "  ./mvnw -q -pl core -am package -DskipTests" >&2
    exit 1
  fi
done

# --- Platform split. On macOS GLFW must own the process main thread, and once it does
#     AWT must never start AppKit beside it: vanilla GuiScreen's clipboard helpers go
#     through java.awt.Toolkit, and after AppKit comes up alongside GLFW the JVM can no
#     longer exit (verified: the copy succeeds, then quitting wedges). Headless makes
#     Toolkit throw instead, which those helpers already swallow. Cost: no clipboard in
#     vanilla text fields on macOS — see docs/macos/known-issues.md MK-1.
#
#     Both flags stay on the command line, never in the argfile: a Windows JVM rejects
#     the -X form outright, so an argfile carrying it would break the other platform. ---
ARGS="$HERE/jvm-args-mcp.txt"
EXTRA=()
if [ "$(uname)" = "Darwin" ]; then
  # A stock JDK refuses to boot on the JBR-only DCEVM flag the shared argfile carries,
  # so macOS gets its own copy without it. See the header of that file.
  ARGS="$HERE/jvm-args-mcp-macos.txt"
  EXTRA+=(-XstartOnFirstThread -Djava.awt.headless=true)
fi

# --- Classpath. board and dwm are compile-`provided` and therefore in no fat jar, so
#     they go on -cp when built; absent, the kernel simply finds no UI and the game runs
#     normally (the detachable-auxiliary contract).
#
#     dwm additionally needs its own RUNTIME dependencies — qml4j, Skija and Skija's
#     natives, plus antlr/rhino/asm — which live in ~/.m2 and are in nobody's fat jar.
#     Omitting them gets you NoClassDefFoundError: org/objectweb/asm/Type at the moment
#     the UI is first opened, which looks like a dwm bug and is not. They are resolved
#     from the pom rather than listed here, so a version bump in dwm/pom.xml needs no
#     edit to this script. ---
CP="$GAME_JAR:$CORE_JAR"
[ -f "$BOARD_JAR" ] && CP="$CP:$BOARD_JAR"

if [ -f "$DWM_JAR" ]; then
  CP="$CP:$DWM_JAR"
  DWM_CP_CACHE="$ROOT/dwm/target/runtime-classpath.txt"
  if [ ! -f "$DWM_CP_CACHE" ] || [ "$ROOT/dwm/pom.xml" -nt "$DWM_CP_CACHE" ]; then
    echo "[run-mcp] resolving dwm runtime dependencies (qml4j / Skija / asm)..."
    ( cd "$ROOT" && ./mvnw -q -ntp -pl dwm dependency:build-classpath \
        -DincludeScope=runtime -Dmdep.outputFile="$DWM_CP_CACHE" ) \
      || { echo "[run-mcp] could not resolve dwm dependencies; UI will not load" >&2; }
  fi
  # Sanity-check the CONTENT, not just the file's existence and age. A truncated or corrupted
  # cache that happens to be newer than the pom would otherwise be pasted straight onto the
  # classpath, and the failure surfaces much later as NoClassDefFoundError the moment the UI is
  # first opened — which reads like a dwm bug and is not one. qml4j is the marker because it is
  # the dependency the UI cannot start without.
  if [ -f "$DWM_CP_CACHE" ] && grep -q "qml4j-core" "$DWM_CP_CACHE"; then
    CP="$CP:$(cat "$DWM_CP_CACHE")"
    echo "[run-mcp] dwm UI on the classpath."
  else
    echo "[run-mcp] dwm dependency cache is missing or unusable — the UI will not load." >&2
    echo "[run-mcp]   delete $DWM_CP_CACHE and re-run to rebuild it." >&2
  fi
else
  echo "[run-mcp] dwm not built — running without the UI."
fi

# --- The UI needs a key to open it. KI-11 injects the hotkey hook into
#     Minecraft.dispatchKeypresses, but the hook is inert unless a scancode is bound, so
#     the flag below is what actually arms it (RSHIFT by default; override with
#     -Dmcp.dwm.hotkey=<scancode>). KI-11 now ships genuinely signed and DOES arm, so
#     RSHIFT opens the screen. See docs/dwm/entry-point.md. ---
EXTRA+=(-Dmcp.core.overlay=true)

# --- C6 JVMTI native debugger, opt-in with MCP_JVMTI=1. ---
#     The onload-only capabilities (breakpoints, single-step, local variable access) can
#     ONLY be acquired via -agentpath at startup; a dynamic attach cannot gain them. Both
#     flags must name the SAME file, because -agentpath loads the module and
#     DebuggerBridge's System.load then binds the JNI natives onto that already-loaded
#     module. Build it first with core/src/main/native/core-jvmti/build-clang.sh.
#
#     Opt-in rather than always-on for two reasons: a bad -agentpath aborts JVM boot
#     outright (not a warning), and the agent is a native build artefact that is not
#     checked in, so a default-on flag would break every clone that has not built it.
if [ "${MCP_JVMTI:-0}" != "0" ]; then
  case "$(uname -s)" in
    Darwin) JVMTI_EXT=dylib ;;
    Linux)  JVMTI_EXT=so ;;
    *)      JVMTI_EXT=dll ;;
  esac
  JVMTI_LIB="$ROOT/core/src/main/native/core-jvmti/build/core-jvmti.$JVMTI_EXT"
  if [ -f "$JVMTI_LIB" ]; then
    EXTRA+=(-agentpath:"$JVMTI_LIB" -Dmcp.core.jvmtiLib="$JVMTI_LIB")
    echo "[run-mcp] JVMTI debugger: $JVMTI_LIB"
  else
    echo "[run-mcp] MCP_JVMTI=1 but $JVMTI_LIB is missing — build it with" >&2
    echo "[run-mcp]   core/src/main/native/core-jvmti/build-clang.sh" >&2
    echo "[run-mcp] continuing WITHOUT the native debugger." >&2
  fi
fi

# --- Working dir must be the game dir (assets, saves). ---
cd "$ROOT/test_run"

exec "$JAVA" "@$ARGS" "${EXTRA[@]}" \
  -javaagent:"$CORE_JAR" \
  -cp "$CP" \
  net.minecraft.client.main.Main \
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties '{}'
