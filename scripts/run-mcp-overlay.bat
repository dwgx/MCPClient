@echo off
REM ============================================================
REM  run-mcp-overlay.bat - run-mcp.bat WITH the experimental DWM Compose
REM  overlay ARMED (-Dmcp.core.overlay=true). Everything else identical.
REM
REM  The overlay is opt-in (off in the normal run-mcp.bat) because it is a
REM  live, GL-touching experimental feature: it installs the render-frame
REM  seam (EntityRenderer.updateCameraAndRender exit) and drives a Compose
REM  M3 surface per frame through GlStateGuard. If it misbehaves, use
REM  plain run-mcp.bat (overlay off) — the game runs normally without it.
REM
REM  Build all three jars first:  scripts\build-jars.bat
REM  (needs client MCP-1.8.9.jar + core-1.8.9-all.jar + dwm-compose-1.8.9-all.jar)
REM ============================================================

setlocal

if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "GAME_JAR=%~dp0..\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%~dp0..\core\target\core-1.8.9-all.jar"
set "DWM_JAR=%~dp0..\dwm-compose\target\dwm-compose-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-mcp.txt"

if not exist "%DWM_JAR%" (
  echo ERROR: dwm-compose fat jar not found: %DWM_JAR%
  echo Build it first:  scripts\build-jars.bat
  exit /b 3
)
set "CP=%GAME_JAR%;%CORE_JAR%;%DWM_JAR%"
echo [run-mcp-overlay] Compose overlay ARMED (-Dmcp.core.overlay=true).

cd /d "%~dp0..\test_run"

"%JAVA%" "@%ARGS%" ^
  -Dskiko.renderApi=OPENGL ^
  -Dmcp.core.overlay=true ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
