@echo off
REM ============================================================
REM  MCPClient - MC 1.8.9 + MCP Core on JetBrains Runtime 25
REM  Launches the game WITH the Kernel attached: the fat agent jar
REM  (core-1.8.9-all.jar) is both -javaagent (startup hook +
REM  Instrumentation) and on -cp (Core + MCP SDK + Byte Buddy).
REM  MCP server listens on 127.0.0.1:25599 (socket transport).
REM  Build first:  scripts\build-jars.bat
REM
REM  dwm (qml4j GuiScreen) is optional. If dwm/target/dwm-1.8.9.jar is
REM  present, it and its runtime deps (qml4j / Skija / asm) go on -cp.
REM  This script does NOT arm the UI (-Dmcp.core.overlay unset). Use
REM  run-mcp-overlay.bat to arm RSHIFT. Absent dwm = game runs normally.
REM ============================================================

setlocal enabledelayedexpansion

if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "ROOT=%~dp0.."
set "GAME_JAR=%ROOT%\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%ROOT%\core\target\core-1.8.9-all.jar"
set "BOARD_JAR=%ROOT%\board\target\board-1.8.9.jar"
set "DWM_JAR=%ROOT%\dwm\target\dwm-1.8.9.jar"
set "DWM_CP_CACHE=%ROOT%\dwm\target\runtime-classpath.txt"
set "ARGS=%~dp0jvm-args-mcp.txt"

set "CP=%GAME_JAR%;%CORE_JAR%"
if exist "%BOARD_JAR%" set "CP=%CP%;%BOARD_JAR%"

if exist "%DWM_JAR%" (
  set "CP=!CP!;%DWM_JAR%"
  if not exist "%DWM_CP_CACHE%" (
    echo [run-mcp] resolving dwm runtime dependencies ^(qml4j / Skija / asm^)...
    pushd "%ROOT%"
    call mvnw.cmd -q -ntp -pl dwm dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="dwm\target\runtime-classpath.txt"
    popd
  )
  findstr /C:"qml4j-core" "%DWM_CP_CACHE%" >nul 2>&1
  if errorlevel 1 (
    echo [run-mcp] dwm dependency cache is missing or unusable -- UI will not load.
    echo [run-mcp]   delete dwm\target\runtime-classpath.txt and re-run.
  ) else (
    set /p DWM_DEPS=<"%DWM_CP_CACHE%"
    set "CP=!CP!;!DWM_DEPS!"
    echo [run-mcp] dwm UI on the classpath ^(overlay NOT armed^).
  )
) else (
  echo [run-mcp] dwm not built -- running without the UI.
)

cd /d "%ROOT%\test_run"

"%JAVA%" "@%ARGS%" ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
