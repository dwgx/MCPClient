@echo off
REM ============================================================
REM  MC 1.8.9 + MCP Core, deferring EVERY decision to P-SECURE.
REM  Sibling of run-mcp.bat: reuses jvm-args-mcp.txt verbatim and
REM  appends -Dmcp.core.psecure=true + the SHARED psecure token, so
REM  the game JVM's reference monitor is SeRemoteMonitor (fail-closed):
REM  it never states a subject; the separate authority decides.
REM
REM  Start run-psecure.bat FIRST (the authority must be listening),
REM  then this. Both sides MUST share the SAME -Dmcp.core.psecureToken.
REM
REM  IMPORTANT: the game side is a client. Do NOT set -Dmcp.core.hardened
REM  here - the AUTHORITY (run-psecure.bat, PSECURE_HARDENED=true) owns the
REM  subject. Setting it here has no effect on the decisions the wall makes.
REM
REM  Build first (from project root):
REM    mvnw.cmd -q -pl core -am package -DskipTests
REM
REM  Optional env vars (must match run-psecure.bat):
REM    PSECURE_TOKEN   shared auth secret (default: dev-psecure-token)
REM    PSECURE_PORT    loopback port      (default: 25601)
REM    PSECURE_HOST    authority host     (default: 127.0.0.1)
REM ============================================================

setlocal

REM --- JetBrains Runtime 25 (with DCEVM). This script lives in scripts/. ---
if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "GAME_JAR=%~dp0..\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%~dp0..\core\target\core-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-mcp.txt"

if "%PSECURE_TOKEN%"=="" set "PSECURE_TOKEN=dev-psecure-token"
if "%PSECURE_PORT%"=="" set "PSECURE_PORT=25601"
if "%PSECURE_HOST%"=="" set "PSECURE_HOST=127.0.0.1"

REM --- Working dir must be the game dir (assets, saves). ---
cd /d "%~dp0..\test_run"

echo [run-mcp-psecure] game JVM will defer to P-SECURE at %PSECURE_HOST%:%PSECURE_PORT% (fail-closed)

"%JAVA%" "@%ARGS%" ^
  -Dmcp.core.psecure=true ^
  -Dmcp.core.psecureToken=%PSECURE_TOKEN% ^
  -Dmcp.core.psecureHost=%PSECURE_HOST% ^
  -Dmcp.core.psecurePort=%PSECURE_PORT% ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%GAME_JAR%;%CORE_JAR%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
