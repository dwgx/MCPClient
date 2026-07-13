@echo off
REM ============================================================================
REM  smoke-live-gl.bat - Windows wrapper for scripts/smoke-live-gl.sh
REM
REM  Launches the live MC 1.8.9 client with the MCP Core Kernel, waits for the
REM  HTTP facade (127.0.0.1:1337), calls dev_probe, and asserts the game is up
REM  and its OpenGL context is readable. This is the standing "is the live
REM  client healthy" smoke test.
REM
REM  Runs the bash harness through Git Bash (the same shell mvnw uses). Pass any
REM  smoke-live-gl.sh flags straight through, e.g.:
REM      scripts\smoke-live-gl.bat --keep
REM      scripts\smoke-live-gl.bat --timeout 200
REM
REM  Prereqs: Git for Windows (bash.exe on PATH), JBR at
REM  _tools\jbrsdk-25.0.3-windows-x64-b508.16, and the built client + core jars.
REM ============================================================================
setlocal
set "HERE=%~dp0"

REM --- find bash.exe (Git for Windows) ---
set "BASH="
for %%B in (bash.exe) do set "BASH=%%~$PATH:B"
if "%BASH%"=="" if exist "C:\Program Files\Git\bin\bash.exe" set "BASH=C:\Program Files\Git\bin\bash.exe"
if "%BASH%"=="" if exist "C:\Program Files\Git\usr\bin\bash.exe" set "BASH=C:\Program Files\Git\usr\bin\bash.exe"
if "%BASH%"=="" (
  echo ERROR: bash.exe not found. Install Git for Windows or run scripts/smoke-live-gl.sh from Git Bash.
  exit /b 3
)

"%BASH%" "%HERE%smoke-live-gl.sh" %*
exit /b %ERRORLEVEL%
