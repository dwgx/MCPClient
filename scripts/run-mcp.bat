@echo off
REM ============================================================
REM  MCPClient - MC 1.8.9 + MCP Core on JetBrains Runtime 25
REM  Launches the game WITH the Kernel attached: the fat agent jar
REM  (core-1.8.9-all.jar) is both -javaagent (startup hook +
REM  Instrumentation) and on -cp (Core + MCP SDK + Byte Buddy).
REM  MCP server listens on 127.0.0.1:25599 (socket transport).
REM  Build first (from project root):  mvnw.cmd -q -pl core -am package -DskipTests
REM ============================================================

setlocal

REM --- JetBrains Runtime 25 (with DCEVM). Adjust if you move it. ---
REM --- This script now lives in scripts/; %~dp0.. is the project root. ---
if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "GAME_JAR=%~dp0..\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%~dp0..\core\target\core-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-mcp.txt"

REM --- DWM GL backend fat jar (pure Java, no native/Kotlin), OPTIONAL. If built,
REM     it is added to -cp so the game JVM can load the overlay backend (core
REM     discovers it reflectively; absent = no overlay, game runs normally — the
REM     detachable-auxiliary contract). This plain run does NOT arm the overlay
REM     (-Dmcp.core.overlay is unset); use run-mcp-overlay.bat to arm it. Build via
REM     scripts\build-jars.bat. ---
set "DWM_JAR=%~dp0..\dwm-gl\target\dwm-gl-1.8.9-all.jar"
REM board carries the Backplane the kernel-state overlay publishes/reads through; it is
REM compile-`provided` (in no fat jar), so add it to -cp when present (see run-mcp-overlay.bat).
set "BOARD_JAR=%~dp0..\board\target\board-1.8.9.jar"
set "CP=%GAME_JAR%;%CORE_JAR%"
if exist "%BOARD_JAR%" set "CP=%CP%;%BOARD_JAR%"
if exist "%DWM_JAR%" set "CP=%CP%;%DWM_JAR%"
if exist "%DWM_JAR%" echo [run-mcp] DWM GL backend present, added to classpath ^(overlay NOT armed^).

REM --- Working dir must be the game dir (assets, saves). ---
cd /d "%~dp0..\test_run"

"%JAVA%" "@%ARGS%" ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
