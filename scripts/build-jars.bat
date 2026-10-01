@echo off
REM ============================================================================
REM  build-jars.bat - jars the live Windows launch needs, in one shot.
REM    1. client + core + board   (-javaagent Kernel fat jar, Backplane)
REM    2. dwm                     (qml4j GuiScreen; plus runtime-classpath.txt)
REM
REM    2 INSTALLS the reactor set rather than only packaging it, because
REM    dependency:build-classpath resolves dwm's client/board dependencies from the
REM    local repository instead of the reactor -- on a machine where they have never
REM    been installed it fails with DependencyResolutionException. Verified: it fails
REM    with `package` alone and succeeds after this install.
REM  Usage:  scripts\build-jars.bat
REM  Launch: scripts\run-mcp.bat  or  scripts\run-mcp-overlay.bat
REM ============================================================================
setlocal
cd /d "%~dp0.."

echo == build-jars: client + core-all + board + dwm ==

echo --- [1/2] client + core + board ---
call mvnw.cmd -q -pl core,client,board -am package -DskipTests
if errorlevel 1 ( echo FAIL: core/client/board build & exit /b 1 )

echo --- [2/2] dwm + runtime classpath cache ---
REM install, not package: the classpath goal below needs these coordinates in the
REM local repository. It also produces dwm's jar, so nothing else has to.
call mvnw.cmd -q -ntp -pl client,board,core,lwjgl2-shim,dwm -am install -DskipTests
if errorlevel 1 ( echo FAIL: dwm build + install & exit /b 1 )
call mvnw.cmd -q -ntp -pl dwm dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="%CD%\dwm\target\runtime-classpath.txt"
if errorlevel 1 (
  echo FAIL: dwm runtime classpath.
  echo   dependency:build-classpath resolves the dwm client/board dependencies from the
  echo   local repository, so it needs the install step above to have succeeded. If that
  echo   passed, check that the maven local repository is writable and that these
  echo   coordinates are installed: client, board, core, lwjgl2-shim, dwm.
  exit /b 1
)

echo.
echo == built jars ==
if exist "client\target\MCP-1.8.9.jar"     echo   OK  client\target\MCP-1.8.9.jar
if exist "core\target\core-1.8.9-all.jar"  echo   OK  core\target\core-1.8.9-all.jar
if exist "board\target\board-1.8.9.jar"    echo   OK  board\target\board-1.8.9.jar
if exist "dwm\target\dwm-1.8.9.jar"        echo   OK  dwm\target\dwm-1.8.9.jar
if exist "dwm\target\runtime-classpath.txt" echo   OK  dwm\target\runtime-classpath.txt
echo Done. Launch: scripts\run-mcp.bat  or  scripts\run-mcp-overlay.bat
endlocal
