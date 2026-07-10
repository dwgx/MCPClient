@echo off
REM ============================================================================
REM  build.bat - compile core-jvmti.dll with MSVC cl.exe (windows-x64).
REM
REM  Prereq: MSVC Build Tools installed and this run from an "x64 Native Tools
REM  Command Prompt for VS" (so cl.exe + the x64 libs are on PATH). If you use a
REM  plain prompt, run vcvars64.bat first:
REM     "C:\Program Files\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
REM
REM  Output: build\core-jvmti.dll  (point -agentpath + -Dmcp.core.jvmtiLib at it)
REM ============================================================================
setlocal
set HERE=%~dp0
set JBR=%HERE%..\..\..\..\..\_tools\jbrsdk-25.0.3-windows-x64-b508.16\include

if not exist "%JBR%\jvmti.h" (
  echo [build] ERROR: jvmti.h not found under %JBR%
  echo [build] Check the JBR path in this script.
  exit /b 1
)

if not exist "%HERE%build" mkdir "%HERE%build"

echo [build] compiling core-jvmti.dll ...
cl /nologo /LD /O2 /MD ^
   /I "%JBR%" /I "%JBR%\win32" ^
   "%HERE%core-jvmti.c" ^
   /Fe:"%HERE%build\core-jvmti.dll" ^
   /Fo:"%HERE%build\\" ^
   /link /IMPLIB:"%HERE%build\core-jvmti.lib"

if errorlevel 1 (
  echo [build] FAILED
  exit /b 1
)
echo [build] OK -^> %HERE%build\core-jvmti.dll
echo [build] launch with:  -agentpath:%HERE%build\core-jvmti.dll  -Dmcp.core.jvmtiLib=%HERE%build\core-jvmti.dll
endlocal
