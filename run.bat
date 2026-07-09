@echo off
REM ============================================================
REM  MCPClient - Minecraft 1.8.9 on LWJGL3 + JDK 25 (Windows)
REM  Run this on your real desktop to see the game window.
REM  Requires: JDK 25 (Temurin), and a built target\MCP-1.8.9.jar
REM            (build with: mvnw.cmd -q clean package -DskipTests)
REM ============================================================

setlocal

REM --- Point JAVA_HOME at your JDK 25 if not already on PATH ---
if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
set "JAVA=%JAVA_HOME%\bin\java.exe"

set "JAR=%~dp0client\target\MCP-1.8.9.jar"
set "ARGS=%~dp0jvm-args-jdk25.txt"

REM --- Working dir must be the game dir (holds assets, saves) ---
cd /d "%~dp0test_run"

"%JAVA%" "@%ARGS%" -cp "%JAR%" net.minecraft.client.main.Main ^
  --version MavenMCP --accessToken 0 --assetsDir assets --assetIndex 1.8 --userProperties "{}"

endlocal
pause
