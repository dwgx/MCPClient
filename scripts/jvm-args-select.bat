@echo off
REM ============================================================================
REM  jvm-args-select.bat - print the MCP JVM arg file the given runtime can boot.
REM
REM  Usage:  scripts\jvm-args-select.bat "<path to java.exe>"
REM
REM  stdout: the chosen arg file's full path, and nothing else -- the caller
REM          captures this line.
REM  stderr: the runtime, the choice, and why (so the reason is visible on the
REM          console without contaminating what was captured).
REM  Exit:   0 when a file was chosen; 2 when none can be booted, in which case
REM          the reason is on stderr and the runtime's own complaint is replayed.
REM
REM  TWO PROBES, BECAUSE ONE FLAG IS NOT THE QUESTION. The first asks whether the
REM  runtime knows -XX:+AllowEnhancedClassRedefinition, which is JBR/DCEVM only: a
REM  stock JDK does not warn about it, it REFUSES TO BOOT. The second asks whether
REM  the file that follows from that answer can actually be booted, because each
REM  file carries flags with their own floors (--sun-misc-unsafe-memory-access,
REM  --enable-native-access). Measured on JDK 21: BOTH candidates refuse to boot,
REM  and handing one over anyway produces exactly the launch failure this picker
REM  exists to prevent. So it refuses and names the cause.
REM
REM  WHY A PROBE AND NOT A VERSION STRING. The question is "can this runtime boot
REM  this file", so the runtime is asked exactly that. Pattern-matching a banner
REM  would be wrong about a JBR whose string we have never seen, and wrong about a
REM  non-DCEVM JBR build that prints "jbr" but cannot redefine.
REM ============================================================================
setlocal
set "JAVA=%~1"
if not exist "%JAVA%" (
  echo ERROR: no java.exe at "%JAVA%" 1>&2
  exit /b 2
)

set "DIR=%~dp0"
set "JBR_ARGS=%DIR%jvm-args-mcp.txt"
set "STOCK_ARGS=%DIR%jvm-args-mcp-stock.txt"
if not exist "%JBR_ARGS%" (
  echo ERROR: missing %JBR_ARGS% 1>&2
  exit /b 2
)
if not exist "%STOCK_ARGS%" (
  echo ERROR: missing %STOCK_ARGS% 1>&2
  exit /b 2
)

REM --- probe 1: decide the candidate ------
"%JAVA%" -XX:+AllowEnhancedClassRedefinition -version >nul 2>&1
if errorlevel 1 (
  set "ARGS_FILE=%STOCK_ARGS%"
  set "ARGS_NAME=jvm-args-mcp-stock.txt"
  set "ARGS_WHY=-XX:+AllowEnhancedClassRedefinition REFUSED -- stock JDK, not a DCEVM build"
) else (
  set "ARGS_FILE=%JBR_ARGS%"
  set "ARGS_NAME=jvm-args-mcp.txt"
  set "ARGS_WHY=-XX:+AllowEnhancedClassRedefinition accepted -- JetBrains Runtime with DCEVM"
)

REM --- probe 2: and the candidate must boot, or the launch dies one step later ---
"%JAVA%" "@%ARGS_FILE%" -version >nul 2>&1
if errorlevel 1 goto :unbootable

echo [jvm-args] runtime   : %JAVA% 1>&2
echo [jvm-args] probe     : %ARGS_WHY% 1>&2
if "%ARGS_NAME%"=="jvm-args-mcp.txt" (
  echo [jvm-args] arg file  : %ARGS_NAME% -- DCEVM enabled, redefine_class can add fields and methods 1>&2
) else (
  echo [jvm-args] arg file  : %ARGS_NAME% -- same config without the DCEVM flag; 1>&2
  echo [jvm-args]             add-field/add-method hot redefine unavailable, retransform unaffected 1>&2
)
echo %ARGS_FILE%
endlocal
exit /b 0

:unbootable
echo [jvm-args] runtime   : %JAVA% 1>&2
echo [jvm-args] probe     : %ARGS_WHY% 1>&2
echo [jvm-args] arg file  : NONE -- this runtime cannot boot either MCP arg file 1>&2
echo [jvm-args] reason    : they are written for JDK 25 or a JetBrains Runtime 1>&2
echo [jvm-args]             ^(--sun-misc-unsafe-memory-access, --enable-native-access^); 1>&2
echo [jvm-args]             point JBR_HOME ^(DCEVM^) or JAVA_HOME at a JDK 25. 1>&2
echo [jvm-args] the runtime complaint follows, then the one-line reproduction: 1>&2
"%JAVA%" "@%ARGS_FILE%" -version >nul
endlocal
exit /b 2
