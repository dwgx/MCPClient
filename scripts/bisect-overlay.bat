@echo off
REM  THROWAWAY bisect copy: identical to run-mcp-overlay.bat except it forwards
REM  MCP_EXTRA_OPTS onto the JVM line, so one build can be run with and without a flag.
REM ============================================================================
REM  run-mcp-overlay.bat - run-mcp.bat WITH the qml4j DWM UI ARMED
REM  (-Dmcp.core.overlay=true). KI-11 binds RSHIFT (override -Dmcp.dwm.hotkey).
REM
REM  The UI is opt-in on Windows because it is a live GL-touching feature.
REM  If it misbehaves, use plain run-mcp.bat (overlay off).
REM
REM  There is one backend: qml4j rendered by Skija into MC's framebuffer as a
REM  real GuiScreen. The old gl / imgui / skiko-ui jars are gone.
REM
REM  Runtime: JBR_HOME if set (a JetBrains Runtime, which gives DCEVM's enhanced
REM  redefine), else an unpacked _tools JBR, else JAVA_HOME, else java.exe on
REM  PATH. The arg file is then chosen by jvm-args-select.bat, which probes the
REM  runtime and prints which file it picked and why: a stock JDK refuses
REM  -XX:+AllowEnhancedClassRedefinition at boot, so it gets the stock arg file.
REM    Usage:  scripts\run-mcp-overlay.bat
REM  Build first:  scripts\build-jars.bat
REM ============================================================================

setlocal

set "ROOT=%~dp0.."

REM ---- runtime ---------------------------------------------------------------
REM The old default pointed at _tools/jbrsdk-... unconditionally, which is
REM gitignored, so a fresh clone died before java was even reached.
set "JAVA="
if not "%JBR_HOME%"=="" if exist "%JBR_HOME%\bin\java.exe" set "JAVA=%JBR_HOME%\bin\java.exe"
if not defined JAVA for /d %%d in ("%ROOT%\_tools\jbrsdk*") do if not defined JAVA if exist "%%~fd\bin\java.exe" set "JAVA=%%~fd\bin\java.exe"
if not defined JAVA if not "%JAVA_HOME%"=="" if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for /f "delims=" %%j in ('where java.exe 2^>nul') do if not defined JAVA set "JAVA=%%j"
if not defined JAVA (
  echo ERROR: no Java runtime found.
  echo   Set JBR_HOME ^(JetBrains Runtime, for DCEVM^) or JAVA_HOME, or put java.exe on PATH.
  exit /b 3
)

REM ---- arg file: whatever this runtime can actually boot -----------------------
set "ARGS="
for /f "usebackq delims=" %%a in (`call "%~dp0jvm-args-select.bat" "%JAVA%"`) do if not defined ARGS set "ARGS=%%a"
if not defined ARGS (
  echo ERROR: could not choose a JVM arg file for "%JAVA%" -- see the reason above.
  exit /b 3
)

set "GAME_JAR=%ROOT%\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%ROOT%\core\target\core-1.8.9-all.jar"
set "BOARD_JAR=%ROOT%\board\target\board-1.8.9.jar"
set "DWM_JAR=%ROOT%\dwm\target\dwm-1.8.9.jar"
set "DWM_CP_CACHE=%ROOT%\dwm\target\runtime-classpath.txt"

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
if not exist "%ROOT%\test_run" (
  echo ERROR: missing game directory %ROOT%\test_run -- assets\ and saves\ live there
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
  REM dependency:build-classpath resolves the dwm client/board dependencies from the
  REM local repository, not the reactor: without the install it fails with
  REM DependencyResolutionException on a machine that has never installed them.
  call mvnw.cmd -q -ntp -pl client,board,core,lwjgl2-shim,dwm -am install -DskipTests
  call mvnw.cmd -q -ntp -pl dwm dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="%ROOT%\dwm\target\runtime-classpath.txt"
  popd
)
findstr /C:"qml4j-core" "%DWM_CP_CACHE%" >nul 2>&1
if errorlevel 1 (
  echo ERROR: dwm dependency cache missing or unusable.
  echo   run scripts\build-jars.bat, which installs the artifacts that goal needs
  exit /b 3
)
set /p DWM_DEPS=<"%DWM_CP_CACHE%"
set "CP=%CP%;%DWM_DEPS%"

echo [run-mcp-overlay] overlay ARMED ^(-Dmcp.core.overlay=true^) qml4j/Skija.

cd /d "%ROOT%\test_run"

"%JAVA%" "@%ARGS%" ^
  -Dmcp.core.overlay=true %MCP_EXTRA_OPTS% ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"
set "RC=%ERRORLEVEL%"

REM Same as run-mcp.bat: pause keeps a double-clicked window readable, and the JVM's own status is
REM what this script returns -- captured first, because pause is a command of its own.
pause
endlocal & exit /b %RC%
