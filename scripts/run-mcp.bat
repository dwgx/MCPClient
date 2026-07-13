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

REM --- DWM Compose backend fat jar (Kotlin+Compose+Skiko), OPTIONAL. If built,
REM     it is added to -cp so the game JVM can load the Compose overlay backend
REM     (core discovers it reflectively; absent = no overlay, game runs normally —
REM     the detachable-auxiliary contract). Build via scripts\build-jars.bat. ---
set "DWM_JAR=%~dp0..\dwm-compose\target\dwm-compose-1.8.9-all.jar"
set "CP=%GAME_JAR%;%CORE_JAR%"
if exist "%DWM_JAR%" (
  set "CP=%GAME_JAR%;%CORE_JAR%;%DWM_JAR%"
  echo [run-mcp] DWM Compose backend present — added to classpath.
)

REM --- Working dir must be the game dir (assets, saves). ---
cd /d "%~dp0..\test_run"

"%JAVA%" "@%ARGS%" ^
  -Dskiko.renderApi=OPENGL ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
