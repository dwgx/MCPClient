@echo off
REM ============================================================================
REM  run-mcp-overlay.bat - run-mcp.bat WITH the qml4j DWM UI ARMED
REM  (-Dmcp.core.overlay=true). KI-11 binds RSHIFT (override -Dmcp.dwm.hotkey).
REM
REM  The UI is opt-in on Windows because it is a live GL-touching feature.
REM  If it misbehaves, use plain run-mcp.bat (overlay off).
REM
REM  There is one backend: qml4j rendered by Skija into MC's framebuffer as a
REM  real GuiScreen. The old gl / imgui / skiko-ui jars are gone.
REM    Usage:  scripts\run-mcp-overlay.bat
REM  Build first:  scripts\build-jars.bat
REM ============================================================================

setlocal

if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "ROOT=%~dp0.."
set "GAME_JAR=%ROOT%\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%ROOT%\core\target\core-1.8.9-all.jar"
set "BOARD_JAR=%ROOT%\board\target\board-1.8.9.jar"
set "DWM_JAR=%ROOT%\dwm\target\dwm-1.8.9.jar"
set "DWM_CP_CACHE=%ROOT%\dwm\target\runtime-classpath.txt"
set "ARGS=%~dp0jvm-args-mcp.txt"

if not exist "%GAME_JAR%" (
  echo ERROR: missing %GAME_JAR% -- run scripts\build-jars.bat
  exit /b 3
)
if not exist "%CORE_JAR%" (
  echo ERROR: missing %CORE_JAR% -- run scripts\build-jars.bat
  exit /b 3
)
if not exist "%DWM_JAR%" (
  echo ERROR: missing %DWM_JAR% -- run scripts\build-jars.bat
  exit /b 3
)

set "CP=%GAME_JAR%;%CORE_JAR%"
if exist "%BOARD_JAR%" (
  set "CP=%CP%;%BOARD_JAR%"
) else (
  echo [run-mcp-overlay] WARNING: board jar missing -- chips roster will be empty.
)
set "CP=%CP%;%DWM_JAR%"

if not exist "%DWM_CP_CACHE%" (
  echo [run-mcp-overlay] resolving dwm runtime dependencies ^(qml4j / Skija / asm^)...
  pushd "%ROOT%"
  call mvnw.cmd -q -ntp -pl dwm dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="%ROOT%\dwm\target\runtime-classpath.txt"
  popd
)
findstr /C:"qml4j-core" "%DWM_CP_CACHE%" >nul 2>&1
if errorlevel 1 (
  echo ERROR: dwm dependency cache missing or unusable.
  echo   delete dwm\target\runtime-classpath.txt and re-run, or scripts\build-jars.bat
  exit /b 3
)
set /p DWM_DEPS=<"%DWM_CP_CACHE%"
set "CP=%CP%;%DWM_DEPS%"

echo [run-mcp-overlay] overlay ARMED ^(-Dmcp.core.overlay=true^) qml4j/Skija.

cd /d "%ROOT%\test_run"

"%JAVA%" "@%ARGS%" ^
  -Dmcp.core.overlay=true ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
