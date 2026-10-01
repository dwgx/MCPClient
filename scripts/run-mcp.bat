@echo off
REM ============================================================
REM  MCPClient - MC 1.8.9 + MCP Core
REM  Launches the game WITH the Kernel attached: the fat agent jar
REM  (core-1.8.9-all.jar) is both -javaagent (startup hook +
REM  Instrumentation) and on -cp (Core + MCP SDK + Byte Buddy).
REM  MCP server listens on 127.0.0.1:25599 (socket transport).
REM  Build first:  scripts\build-jars.bat
REM
REM  Runtime: JBR_HOME if set (a JetBrains Runtime, which gives DCEVM's
REM  enhanced redefine), else an unpacked _tools JBR, else JAVA_HOME, else
REM  java.exe on PATH. The arg file is then chosen by jvm-args-select.bat,
REM  which probes the runtime for -XX:+AllowEnhancedClassRedefinition and
REM  says on the console which file it picked and why: a stock JDK refuses
REM  that flag at boot, so it gets jvm-args-mcp-stock.txt instead.
REM
REM  dwm (qml4j GuiScreen) is optional. If dwm/target/dwm-1.8.9.jar is
REM  present, it and its runtime deps (qml4j / Skija / asm) go on -cp.
REM  This script does NOT arm the UI (-Dmcp.core.overlay unset). Use
REM  run-mcp-overlay.bat to arm RSHIFT. Absent dwm = game runs normally.
REM ============================================================

setlocal enabledelayedexpansion

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

REM The jars and the game directory, checked by name before anything uses them. The overlay
REM launcher tested all three jars from the start; this one tested none, so a missing jar surfaced
REM as a NoClassDefFoundError from the JVM pages later, naming no file, and still ended at the
REM pause below with a status of 0. test_run is where assets\ and saves\ live -- the JVM's working
REM directory, without which the launch fails somewhere inside the asset index.
if not exist "%GAME_JAR%" (
  echo ERROR: missing %GAME_JAR% -- run scripts\build-jars.bat
  exit /b 3
)
if not exist "%CORE_JAR%" (
  echo ERROR: missing %CORE_JAR% -- run scripts\build-jars.bat
  exit /b 3
)
if not exist "%ROOT%\test_run" (
  echo ERROR: missing game directory %ROOT%\test_run -- assets\ and saves\ live there
  exit /b 3
)

set "CP=%GAME_JAR%;%CORE_JAR%"
if exist "%BOARD_JAR%" set "CP=%CP%;%BOARD_JAR%"

if exist "%DWM_JAR%" (
  set "CP=!CP!;%DWM_JAR%"
  if not exist "%DWM_CP_CACHE%" (
    echo [run-mcp] resolving dwm runtime dependencies ^(qml4j / Skija / asm^)...
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
    echo [run-mcp] dwm dependency cache is missing or unusable -- UI will not load.
    echo [run-mcp]   run scripts\build-jars.bat, which installs the artifacts that goal needs.
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
set "RC=%ERRORLEVEL%"

REM The JVM's status is this script's status. pause is here so a double-clicked window does not
REM vanish before the output is read -- but it must not be the LAST word, because it reports 0 for
REM a client that crashed, and a caller has no other way to tell whether the game exited cleanly or
REM died at boot. Captured before pause, since pause is itself a command and may set its own.
pause
endlocal & exit /b %RC%
