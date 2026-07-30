#!/usr/bin/env bash
# ============================================================================
#  build-clang.sh — compile the core-jvmti agent with clang (no MSVC needed).
#
#  Cross-platform by detecting the host, because the agent is the SAME C source
#  everywhere; only the shared-library extension, the JNI md-header subdirectory
#  and the position-independent-code flag differ:
#
#    Windows  core-jvmti.dll     headers under <inc>/win32
#    macOS    core-jvmti.dylib   headers under <inc>/darwin   (-fPIC)
#    Linux    core-jvmti.so      headers under <inc>/linux    (-fPIC)
#
#  Install clang once:
#    Windows   winget install --id LLVM.LLVM -e
#    macOS     already present with the Xcode command line tools
#    Linux     apt install clang   (or the distro equivalent)
#
#  Headers: JBRINC defaults to the bundled Windows JBR SDK on Windows, so that
#  build stays byte-identical to what it was. Everywhere else it defaults to the
#  running JDK's own include/ via JAVA_HOME, which is what CI already overrides
#  it to. Pass JBRINC=... to force a specific SDK.
#
#  Output:  build/core-jvmti.<ext>  and a copy beside this script.
#  Launch:  -agentpath:<abs>/core-jvmti.<ext>
#           -Dmcp.core.jvmtiLib=<abs>/core-jvmti.<ext>
#  Both flags must point at the SAME file: -agentpath loads the module for its
#  onload-only capabilities, and System.load binds the JNI natives onto it.
# ============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

case "$(uname -s)" in
  Darwin)            HOST=macos;   LIBEXT=dylib; MDDIR=darwin; PICFLAG=-fPIC ;;
  Linux)             HOST=linux;   LIBEXT=so;    MDDIR=linux;  PICFLAG=-fPIC ;;
  MINGW*|MSYS*|CYGWIN*|Windows_NT)
                     HOST=windows; LIBEXT=dll;   MDDIR=win32;  PICFLAG= ;;
  *) echo "[build] ERROR: unsupported host $(uname -s)" >&2; exit 1 ;;
esac

# The bundled JBR SDK is Windows-x64 only, so it is the default only there.
DEFAULT_JBRINC="$HERE/../../../../../_tools/jbrsdk-25.0.3-windows-x64-b508.16/include"
if [ "$HOST" != "windows" ]; then
  DEFAULT_JBRINC="${JAVA_HOME:-}/include"
fi
JBRINC="${JBRINC:-$DEFAULT_JBRINC}"

CLANG="${CLANG:-}"
if [ -z "$CLANG" ] && [ "$HOST" = "windows" ]; then
  CLANG="/c/Program Files/LLVM/bin/clang.exe"
fi
[ -n "$CLANG" ] && [ -x "$CLANG" ] || CLANG="$(command -v clang || true)"
if [ -z "${CLANG:-}" ] || [ ! -x "$CLANG" ]; then
  echo "[build] ERROR: clang not found." >&2
  case "$HOST" in
    windows) echo "[build]   winget install --id LLVM.LLVM -e" >&2 ;;
    macos)   echo "[build]   xcode-select --install" >&2 ;;
    linux)   echo "[build]   apt install clang" >&2 ;;
  esac
  exit 1
fi
if [ ! -f "$JBRINC/jvmti.h" ]; then
  echo "[build] ERROR: jvmti.h not found under $JBRINC" >&2
  echo "[build]   set JAVA_HOME to a JDK 25, or pass JBRINC=<dir containing jvmti.h>" >&2
  exit 1
fi
if [ ! -f "$JBRINC/$MDDIR/jni_md.h" ]; then
  echo "[build] ERROR: $JBRINC/$MDDIR/jni_md.h not found (wrong SDK for $HOST?)" >&2
  exit 1
fi

OUT="$HERE/build/core-jvmti.$LIBEXT"
mkdir -p "$HERE/build"
echo "[build] host=$HOST  clang=$("$CLANG" --version | head -1)"
echo "[build] headers=$JBRINC"
# The GNU driver resolves these headers with -I; clang-cl does not link here on
# Windows because no Windows SDK is installed. No jvm library to link against --
# the JVM loads the agent, the agent does not link the JVM.
"$CLANG" -shared -O2 ${PICFLAG:+$PICFLAG} \
  -I"$JBRINC" -I"$JBRINC/$MDDIR" \
  "$HERE/core-jvmti.c" \
  -o "$OUT"

cp "$OUT" "$HERE/core-jvmti.$LIBEXT"
echo "[build] OK -> $OUT (copied beside this script)"
echo "[build] launch flags:"
echo "  -agentpath:$HERE/core-jvmti.$LIBEXT"
echo "  -Dmcp.core.jvmtiLib=$HERE/core-jvmti.$LIBEXT"
