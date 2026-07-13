@echo off
REM ============================================================================
REM  run-mcp-overlay.bat - run-mcp.bat WITH the experimental DWM overlay ARMED
REM  (-Dmcp.core.overlay=true). Everything else identical.
REM
REM  The overlay is opt-in (off in the normal run-mcp.bat) because it is a live,
REM  GL-touching experimental feature: it installs the render-frame seam
REM  (EntityRenderer.updateCameraAndRender exit) and drives an overlay per frame
REM  through GlStateGuard. If it misbehaves, use plain run-mcp.bat (overlay off)
REM  and the game runs normally without it.
REM
REM  BACKEND SELECTION (-Dmcp.core.overlay.backend):
REM    gl     pure-Java handwritten immediate-mode GL panel  (DEFAULT; no native/Kotlin)
REM    imgui  Dear ImGui via imgui-java                       (needs dwm-imgui-all.jar + DLL)
REM  When unset, core tries whichever backend jars are on -cp in preference order
REM  (gl, then imgui). Pass the id as the first script argument.
REM    Usage:  scripts\run-mcp-overlay.bat [gl^|imgui]
REM
REM  Build the jars first:  scripts\build-jars.bat   (client + core + dwm-gl + dwm-imgui)
REM ============================================================================

setlocal enabledelayedexpansion

if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "GAME_JAR=%~dp0..\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%~dp0..\core\target\core-1.8.9-all.jar"
set "GL_JAR=%~dp0..\dwm-gl\target\dwm-gl-1.8.9-all.jar"
set "IMGUI_JAR=%~dp0..\dwm-imgui\target\dwm-imgui-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-mcp.txt"

set "BACKEND=%~1"

REM Base classpath: game + kernel. Overlay backend jars appended when present.
set "CP=%GAME_JAR%;%CORE_JAR%"
set "BACKEND_OPT="
if not "%BACKEND%"=="" set "BACKEND_OPT=-Dmcp.core.overlay.backend=%BACKEND%"

REM The pure-Java dwm-gl backend is the default: append it if built.
if exist "%GL_JAR%" set "CP=%CP%;%GL_JAR%"

REM The imgui backend needs its native imgui-java64.dll. It ships INSIDE the fat
REM jar at io/imgui/java/native-bin/; pre-extract it to a known dir and point
REM -Dimgui.library.path there so imgui-java's loader takes the absolute-path
REM System.load branch (immune to custom-classloader resource visibility inside
REM the shaded agent jar). Only needed when the imgui jar is present.
set "IMGUI_OPT="
if exist "%IMGUI_JAR%" (
  set "CP=!CP!;%IMGUI_JAR%"
  set "IMGUI_NATIVE_DIR=%~dp0..\dwm-imgui\target\imgui-native"
  if not exist "!IMGUI_NATIVE_DIR!\imgui-java64.dll" (
    echo [run-mcp-overlay] extracting imgui-java64.dll -^> !IMGUI_NATIVE_DIR!
    if not exist "!IMGUI_NATIVE_DIR!" mkdir "!IMGUI_NATIVE_DIR!"
    "%JBR_HOME%\bin\jar" xf "%IMGUI_JAR%" io/imgui/java/native-bin/imgui-java64.dll
    move /Y "io\imgui\java\native-bin\imgui-java64.dll" "!IMGUI_NATIVE_DIR!\" >nul 2>&1
    rmdir /S /Q "io" >nul 2>&1
  )
  set "IMGUI_OPT=-Dimgui.library.path=!IMGUI_NATIVE_DIR! --enable-native-access=ALL-UNNAMED"
)

if not exist "%GL_JAR%" if not exist "%IMGUI_JAR%" (
  echo ERROR: no overlay backend jar found.
  echo Build one first:  scripts\build-jars.bat
  exit /b 3
)
echo [run-mcp-overlay] overlay ARMED (-Dmcp.core.overlay=true) backend=%BACKEND% (empty = auto).

cd /d "%~dp0..\test_run"

"%JAVA%" "@%ARGS%" ^
  -Dmcp.core.overlay=true ^
  %BACKEND_OPT% ^
  %IMGUI_OPT% ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
