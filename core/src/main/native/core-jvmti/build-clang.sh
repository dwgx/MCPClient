#!/usr/bin/env bash
# ============================================================================
#  build-clang.sh — compile core-jvmti.dll with LLVM/clang (NO MSVC needed).
#
#  This is the MSVC-free build path. LLVM ships its own Windows runtime + import
#  libraries, so `clang -shared` links a working JVMTI agent DLL without a
#  Visual Studio / Windows SDK install.
#
#  Install clang once (Windows):   winget install --id LLVM.LLVM -e
#
#  Output: build/core-jvmti.dll  AND a copy at ./core-jvmti.dll
#  Launch: -agentpath:<abs>/core-jvmti.dll  -Dmcp.core.jvmtiLib=<abs>/core-jvmti.dll
# ============================================================================
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
JBRINC="$HERE/../../../../../_tools/jbrsdk-25.0.3-windows-x64-b508.16/include"

CLANG="${CLANG:-/c/Program Files/LLVM/bin/clang.exe}"
[ -x "$CLANG" ] || CLANG="$(command -v clang || true)"
if [ -z "${CLANG:-}" ] || [ ! -x "$CLANG" ]; then
  echo "[build] ERROR: clang not found. Install with: winget install --id LLVM.LLVM -e" >&2
  exit 1
fi
if [ ! -f "$JBRINC/jvmti.h" ]; then
  echo "[build] ERROR: jvmti.h not found under $JBRINC" >&2
  exit 1
fi

mkdir -p "$HERE/build"
echo "[build] compiling core-jvmti.dll with $("$CLANG" --version | head -1) ..."
# NOTE: clang's GNU driver resolves the JBR headers fine with -I"path"; the
# clang-cl (MSVC) driver does NOT link here because no Windows SDK is installed.
"$CLANG" -shared -O2 \
  -I"$JBRINC" -I"$JBRINC/win32" \
  "$HERE/core-jvmti.c" \
  -o "$HERE/build/core-jvmti.dll"

cp "$HERE/build/core-jvmti.dll" "$HERE/core-jvmti.dll"
echo "[build] OK -> $HERE/build/core-jvmti.dll (copied to $HERE/core-jvmti.dll)"
echo "[build] launch:  -agentpath:$HERE/core-jvmti.dll  -Dmcp.core.jvmtiLib=$HERE/core-jvmti.dll"
