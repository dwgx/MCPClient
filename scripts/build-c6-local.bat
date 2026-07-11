@echo off
REM ============================================================================
REM  build-c6-local.bat — Windows-only: build the C6 native JVMTI DLL and print
REM  how to launch the game with it. C6 native CANNOT be built in the ubuntu CI
REM  (the DLL is windows-x64, needs the Windows JBR headers + Windows clang), so
REM  this local script is the C6 build path. See CLAUDE.md / STATUS.md.
REM
REM  Prereq (once):  winget install --id LLVM.LLVM -e   (gives clang; NO MSVC needed)
REM  Run from anywhere:  scripts\build-c6-local.bat
REM ============================================================================
setlocal
set "HERE=%~dp0"
set "ROOT=%HERE%.."
set "NATIVE=%ROOT%\core\src\main\native\core-jvmti"

echo [c6] Step 1/2: compiling core-jvmti.dll via build-clang.sh ...
REM build-clang.sh is a bash script; run it through Git Bash if present.
where bash >nul 2>nul
if errorlevel 1 (
  echo [c6] ERROR: bash not found. Install Git for Windows, or run build-clang.sh manually.
  exit /b 1
)
bash "%NATIVE%/build-clang.sh"
if errorlevel 1 (
  echo [c6] ERROR: DLL build failed. See output above.
  exit /b 1
)

set "DLL=%NATIVE%\core-jvmti.dll"
if not exist "%DLL%" (
  echo [c6] ERROR: expected DLL not found at %DLL%
  exit /b 1
)

echo.
echo [c6] Step 2/2: DLL ready at %DLL%
echo [c6] To launch the game WITH the native debugger, add these to run-mcp.bat's java line:
echo        -agentpath:"%DLL%" -Dmcp.core.jvmtiLib="%DLL%"
echo.
echo [c6] Or run the game and verify debug_* tools go from isError to real JVMTI:
echo        (see scripts\run-mcp.bat; C6 tools: debug_set_breakpoint / debug_suspend_thread / ...)
endlocal
