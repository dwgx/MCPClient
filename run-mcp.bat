@echo off
REM ============================================================
REM  MCPClient - MC 1.8.9 + MCP Core on JetBrains Runtime 25
REM  Launches the game WITH the神器 attached: the fat agent jar
REM  (core-1.8.9-all.jar) is both -javaagent (startup hook +
REM  Instrumentation) and on -cp (Core + MCP SDK + Byte Buddy).
REM  MCP server listens on 127.0.0.1:25599 (socket transport).
REM  Build first:  mvnw.cmd -q -pl core -am package -DskipTests
REM ============================================================

setlocal

REM --- JetBrains Runtime 25 (with DCEVM). Adjust if you move it. ---
if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "GAME_JAR=%~dp0client\target\MCP-1.8.9.jar"
set "CORE_JAR=%~dp0core\target\core-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-mcp.txt"

REM --- Working dir must be the game dir (assets, saves). ---
cd /d "%~dp0test_run"

"%JAVA%" "@%ARGS%" ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%GAME_JAR%;%CORE_JAR%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
