@echo off
REM ============================================================================
REM  build-jars.bat - build the jars the live launch needs, in one shot.
REM    1. client     MCP-1.8.9.jar            (the game)
REM    2. core       core-1.8.9-all.jar       (the -javaagent Kernel fat jar)
REM    3. dwm-gl     dwm-gl-1.8.9-all.jar     (pure-Java overlay; gl + gl-ui)
REM    4. dwm-imgui  dwm-imgui-1.8.9-all.jar  (Dear ImGui overlay; imgui + imgui-ui; DLL)
REM    5. dwm-skiko  dwm-skiko-1.8.9-all.jar  (Skia/Skiko MD3 overlay; skiko-ui; native)
REM  All overlay backends build by default. Pass --no-native to skip imgui + skiko
REM  (the two heavy native backends), leaving only the pure-Java dwm-gl.
REM    Usage:  scripts\build-jars.bat [--no-native]
REM ============================================================================
setlocal
cd /d "%~dp0.."

echo == build-jars: client + core-all + dwm-gl (+ dwm-imgui + dwm-skiko) ==

echo --- [1/4] client + core (fat agent jar) ---
call mvnw.cmd -q -pl core,client -am package -DskipTests
if errorlevel 1 ( echo FAIL: core/client build & exit /b 1 )

echo --- [2/4] dwm + dwm-gl (installs dwm+client to .m2 first) ---
call mvnw.cmd -q -pl dwm,client -am install -DskipTests
if errorlevel 1 ( echo FAIL: dwm/client install & exit /b 1 )
call mvnw.cmd -q -pl dwm-gl install -DskipTests
if errorlevel 1 ( echo FAIL: dwm-gl build & exit /b 1 )

if /I "%~1"=="--no-native" goto done

echo --- [3/4] dwm-imgui fat jar (imgui-java + native DLL) ---
call mvnw.cmd -q -pl dwm-imgui package -DskipTests
if errorlevel 1 ( echo FAIL: dwm-imgui build & exit /b 1 )

echo --- [4/4] dwm-skiko fat jar (Skia/Skiko + native DLL + icu data) ---
call mvnw.cmd -q -pl dwm-skiko package -DskipTests
if errorlevel 1 ( echo FAIL: dwm-skiko build & exit /b 1 )

:done
echo.
echo == built jars ==
if exist "client\target\MCP-1.8.9.jar"              echo   OK  client\target\MCP-1.8.9.jar
if exist "core\target\core-1.8.9-all.jar"           echo   OK  core\target\core-1.8.9-all.jar
if exist "dwm-gl\target\dwm-gl-1.8.9-all.jar"       echo   OK  dwm-gl\target\dwm-gl-1.8.9-all.jar
if exist "dwm-imgui\target\dwm-imgui-1.8.9-all.jar" echo   OK  dwm-imgui\target\dwm-imgui-1.8.9-all.jar
if exist "dwm-skiko\target\dwm-skiko-1.8.9-all.jar" echo   OK  dwm-skiko\target\dwm-skiko-1.8.9-all.jar
echo Done. Launch: scripts\run-mcp-overlay.bat [skiko-ui^|gl-ui^|imgui-ui]
endlocal
