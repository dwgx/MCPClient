@echo off
REM ============================================================================
REM  build-jars.bat - jars the live Windows launch needs, in one shot.
REM    1. client  MCP-1.8.9.jar
REM    2. core    core-1.8.9-all.jar   (-javaagent Kernel fat jar)
REM    3. board   board-1.8.9.jar      (Backplane; compile-provided)
REM    4. dwm     dwm-1.8.9.jar        (qml4j GuiScreen; plus runtime-classpath.txt)
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
call mvnw.cmd -q -pl dwm -am package -DskipTests
if errorlevel 1 ( echo FAIL: dwm build & exit /b 1 )
call mvnw.cmd -q -ntp -pl dwm dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile="dwm\target\runtime-classpath.txt"
if errorlevel 1 ( echo FAIL: dwm runtime classpath & exit /b 1 )

echo.
echo == built jars ==
if exist "client\target\MCP-1.8.9.jar"     echo   OK  client\target\MCP-1.8.9.jar
if exist "core\target\core-1.8.9-all.jar"  echo   OK  core\target\core-1.8.9-all.jar
if exist "board\target\board-1.8.9.jar"    echo   OK  board\target\board-1.8.9.jar
if exist "dwm\target\dwm-1.8.9.jar"        echo   OK  dwm\target\dwm-1.8.9.jar
if exist "dwm\target\runtime-classpath.txt" echo   OK  dwm\target\runtime-classpath.txt
echo Done. Launch: scripts\run-mcp.bat  or  scripts\run-mcp-overlay.bat
endlocal
