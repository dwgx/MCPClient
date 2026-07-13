@echo off
REM ============================================================================
REM  build-jars.bat - build the jars the live launch needs, in one shot.
REM    1. client     MCP-1.8.9.jar            (the game)
REM    2. core       core-1.8.9-all.jar       (the -javaagent Kernel fat jar)
REM    3. dwm-gl     dwm-gl-1.8.9-all.jar     (pure-Java overlay backend; DEFAULT)
REM    4. dwm-imgui  dwm-imgui-1.8.9-all.jar  (Dear ImGui overlay backend; native DLL)
REM  Both overlay backends build by default. Pass --no-imgui to skip the imgui one.
REM    Usage:  scripts\build-jars.bat [--no-imgui]
REM ============================================================================
setlocal
cd /d "%~dp0.."

echo == build-jars: client + core-all + dwm-gl-all (+ dwm-imgui-all) ==

echo --- [1/3] client + core (fat agent jar) ---
call mvnw.cmd -q -pl core,client -am package -DskipTests
if errorlevel 1 ( echo FAIL: core/client build & exit /b 1 )

echo --- [2/3] dwm-gl fat jar (pure Java; installs dwm+client to .m2 first) ---
call mvnw.cmd -q -pl dwm,client -am install -DskipTests
if errorlevel 1 ( echo FAIL: dwm/client install & exit /b 1 )
call mvnw.cmd -q -pl dwm-gl package -DskipTests
if errorlevel 1 ( echo FAIL: dwm-gl build & exit /b 1 )

if /I "%~1"=="--no-imgui" goto done

echo --- [3/3] dwm-imgui fat jar (imgui-java + native DLL; installs dwm-gl first) ---
call mvnw.cmd -q -pl dwm-gl install -DskipTests
if errorlevel 1 ( echo FAIL: dwm-gl install & exit /b 1 )
call mvnw.cmd -q -pl dwm-imgui package -DskipTests
if errorlevel 1 ( echo FAIL: dwm-imgui build & exit /b 1 )

:done
echo.
echo == built jars ==
if exist "client\target\MCP-1.8.9.jar"              echo   OK  client\target\MCP-1.8.9.jar
if exist "core\target\core-1.8.9-all.jar"           echo   OK  core\target\core-1.8.9-all.jar
if exist "dwm-gl\target\dwm-gl-1.8.9-all.jar"       echo   OK  dwm-gl\target\dwm-gl-1.8.9-all.jar
if exist "dwm-imgui\target\dwm-imgui-1.8.9-all.jar" echo   OK  dwm-imgui\target\dwm-imgui-1.8.9-all.jar
echo Done. Launch: scripts\run-mcp-overlay.bat [gl^|imgui]
endlocal
