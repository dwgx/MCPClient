@echo off
REM ============================================================================
REM  build-jars.bat - build the three jars the live launch needs, in one shot.
REM    1. client        MCP-1.8.9.jar             (the game)
REM    2. core          core-1.8.9-all.jar        (the -javaagent Kernel fat jar)
REM    3. dwm-compose   dwm-compose-1.8.9-all.jar (OPTIONAL Compose overlay backend)
REM  Pass --no-compose to skip the heavy dwm-compose build.
REM    Usage:  scripts\build-jars.bat [--no-compose]
REM ============================================================================
setlocal
cd /d "%~dp0.."

echo == build-jars: client + core-all (+ dwm-compose-all) ==

echo --- [1/2] client + core (fat agent jar) ---
call mvnw.cmd -q -pl core,client -am package -DskipTests
if errorlevel 1 ( echo FAIL: core/client build & exit /b 1 )

if /I "%~1"=="--no-compose" goto done

echo --- [2/2] dwm-compose fat jar (installs dwm+client to .m2 first) ---
call mvnw.cmd -q -pl dwm,client -am install -DskipTests
if errorlevel 1 ( echo FAIL: dwm/client install & exit /b 1 )
call mvnw.cmd -q -pl dwm-compose package -DskipTests
if errorlevel 1 ( echo FAIL: dwm-compose build & exit /b 1 )

:done
echo.
echo == built jars ==
if exist "client\target\MCP-1.8.9.jar"                echo   OK  client\target\MCP-1.8.9.jar
if exist "core\target\core-1.8.9-all.jar"             echo   OK  core\target\core-1.8.9-all.jar
if exist "dwm-compose\target\dwm-compose-1.8.9-all.jar" echo   OK  dwm-compose\target\dwm-compose-1.8.9-all.jar
echo Done. Launch: scripts\run-mcp.bat
endlocal
