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
REM  BACKEND SELECTION (-Dmcp.core.overlay.backend). MD3-UI backends drive the real
REM  DWM MaterialButton tree (DrawContext axis); the plain ids draw a static panel:
REM    skiko-ui  Skia/Skiko MD3 (true vector + real text, highest fidelity)  (native DLL)
REM    gl-ui     pure-Java MD3 (immediate-mode GL, placeholder text)          (no native/Kotlin)
REM    imgui-ui  Dear ImGui MD3 (native rounded rect + real text)            (native DLL)
REM    gl        pure-Java static GL panel                                    (no native/Kotlin)
REM    imgui     Dear ImGui static window                                     (native DLL)
REM  When unset, core tries backends in preference order (skiko-ui, gl-ui, imgui-ui,
REM  gl, imgui). Pass the id as the first script argument.
REM    Usage:  scripts\run-mcp-overlay.bat [skiko-ui^|gl-ui^|imgui-ui^|gl^|imgui]
REM
REM  Build the jars first:  scripts\build-jars.bat
REM ============================================================================

setlocal enabledelayedexpansion

if "%JBR_HOME%"=="" set "JBR_HOME=%~dp0..\_tools\jbrsdk-25.0.3-windows-x64-b508.16"
set "JAVA=%JBR_HOME%\bin\java.exe"

set "GAME_JAR=%~dp0..\client\target\MCP-1.8.9.jar"
set "CORE_JAR=%~dp0..\core\target\core-1.8.9-all.jar"
set "GL_JAR=%~dp0..\dwm-gl\target\dwm-gl-1.8.9-all.jar"
set "IMGUI_JAR=%~dp0..\dwm-imgui\target\dwm-imgui-1.8.9-all.jar"
set "SKIKO_JAR=%~dp0..\dwm-skiko\target\dwm-skiko-1.8.9-all.jar"
set "ARGS=%~dp0jvm-args-mcp.txt"

set "BACKEND=%~1"

REM Base classpath: game + kernel. Overlay backend jars appended when present.
set "CP=%GAME_JAR%;%CORE_JAR%"
set "BACKEND_OPT="
if not "%BACKEND%"=="" set "BACKEND_OPT=-Dmcp.core.overlay.backend=%BACKEND%"

REM Pure-Java GL backend (gl / gl-ui): no native, append if built.
if exist "%GL_JAR%" set "CP=%CP%;%GL_JAR%"

REM imgui backend (imgui / imgui-ui): needs imgui-java64.dll. It ships INSIDE the fat
REM jar at io/imgui/java/native-bin/; pre-extract + -Dimgui.library.path so the loader
REM takes the absolute-path System.load branch (immune to shaded-classloader resource
REM visibility). Only when the imgui jar is present.
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
  set "IMGUI_OPT=-Dimgui.library.path=!IMGUI_NATIVE_DIR!"
)

REM skiko backend (skiko-ui): skiko-windows-x64.dll + icudtl.dat ship in the fat jar and
REM are extracted at runtime by org.jetbrains.skiko.Library. -Dskiko.renderApi=OPENGL is
REM REQUIRED (forces the GL backend that wraps MC's context; proven by the prior Compose
REM backend). Only when the skiko jar is present.
set "SKIKO_OPT="
if exist "%SKIKO_JAR%" (
  set "CP=!CP!;%SKIKO_JAR%"
  set "SKIKO_OPT=-Dskiko.renderApi=OPENGL"
)

if not exist "%GL_JAR%" if not exist "%IMGUI_JAR%" if not exist "%SKIKO_JAR%" (
  echo ERROR: no overlay backend jar found.
  echo Build them first:  scripts\build-jars.bat
  exit /b 3
)
echo [run-mcp-overlay] overlay ARMED (-Dmcp.core.overlay=true) backend=%BACKEND% (empty = auto).

cd /d "%~dp0..\test_run"

"%JAVA%" "@%ARGS%" ^
  -Dmcp.core.overlay=true ^
  --enable-native-access=ALL-UNNAMED ^
  %BACKEND_OPT% ^
  %IMGUI_OPT% ^
  %SKIKO_OPT% ^
  -javaagent:"%CORE_JAR%" ^
  -cp "%CP%" ^
  net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
