@echo off
REM ============================================================
REM  P-SECURE authority (L1 VTL) - standalone decision process
REM  Runs net.marcloud.mcp.core.alpc.AlpcMain in its OWN JVM: a
REM  separate address space the game JVM cannot reach except over
REM  the loopback socket. A compromised in-game hook therefore
REM  cannot forge a grant here.
REM
REM  Start THIS first, then run-mcp-psecure.bat (the game JVM).
REM  Both sides MUST share the SAME -Dmcp.core.psecureToken.
REM
REM  Build first (from project root):
REM    mvnw.cmd -q -pl core -am package -DskipTests
REM
REM  Optional env vars:
REM    PSECURE_TOKEN   shared auth secret (default: dev-psecure-token)
REM    PSECURE_PORT    loopback port      (default: 25601)
REM    PSECURE_HARDENED=true  bite at L4/L5 (deny dangerous verbs)
REM  NOTE: set PSECURE_HARDENED here (the AUTHORITY owns the subject),
REM        NOT on the game side.
REM ============================================================

setlocal

REM --- JetBrains Runtime 25. This script lives in scripts/; %~dp0.. is root. ---
if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "CORE_JAR=%~dp0..\core\target\core-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-psecure.txt"

if "%PSECURE_TOKEN%"=="" set "PSECURE_TOKEN=dev-psecure-token"
if "%PSECURE_PORT%"=="" set "PSECURE_PORT=25601"

set "HARDENED_ARG="
if /I "%PSECURE_HARDENED%"=="true" set "HARDENED_ARG=-Dmcp.core.hardened=true"

echo [run-psecure] starting P-SECURE authority on 127.0.0.1:%PSECURE_PORT% (hardened=%PSECURE_HARDENED%)
echo [run-psecure] share this token with the game JVM: %PSECURE_TOKEN%

"%JAVA%" "@%ARGS%" ^
  -cp "%CORE_JAR%" ^
  -Dmcp.core.psecureToken=%PSECURE_TOKEN% ^
  -Dmcp.core.psecurePort=%PSECURE_PORT% ^
  %HARDENED_ARG% ^
  net.marcloud.mcp.core.alpc.AlpcMain

endlocal
pause
