@echo off
REM ============================================================
REM   Auto-Upgrade Server - Windows Start Script
REM   Usage: start.bat [port] [host]
REM   Default: port=8090 host=0.0.0.0
REM   Env vars (optional):
REM     JAVA_HOME                JDK 1.8 install path
REM     UPGRADE_SERVER_BASE_URL  Override public distribute URL (reverse proxy)
REM   Hint: Ctrl+C to stop
REM ============================================================

setlocal enabledelayedexpansion

REM Parse port and host
set "PORT=%~1"
if "%PORT%"=="" set "PORT=8090"
set "HOST=%~2"
if "%HOST%"=="" set "HOST=0.0.0.0"

REM Resolve java command path
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" (
        set "JAVACMD=%JAVA_HOME%\bin\java.exe"
    ) else (
        echo [ERROR] JAVA_HOME is set but %%JAVA_HOME%%\bin\java.exe not found
        echo        JAVA_HOME=%JAVA_HOME%
        exit /b 1
    )
) else (
    set "JAVACMD=java"
)

REM Verify java executable
"%JAVACMD%" -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] java command not found, install JDK 1.8 or set JAVA_HOME
    exit /b 1
)

REM Locate upgrade-server.jar
set "JAR_PATH=%~dp0target\upgrade-server.jar"
if not exist "%JAR_PATH%" (
    set "JAR_PATH=%~dp0upgrade-server.jar"
)
if not exist "%JAR_PATH%" (
    echo [ERROR] upgrade-server.jar not found
    echo        Run in project root: mvn -s settings.xml clean package -DskipTests
    exit /b 1
)

echo ============================================
echo   Auto-Upgrade Server
echo   Listen: %HOST%:%PORT%
echo   JDK  : %JAVACMD%
echo   Jar  : %JAR_PATH%
if defined UPGRADE_SERVER_BASE_URL (
    echo   Public: %UPGRADE_SERVER_BASE_URL%
) else (
    echo   Public: http://127.0.0.1:%PORT%
)
echo ============================================
echo Hint: Ctrl+C to stop
echo.

"%JAVACMD%" -jar "%JAR_PATH%" %PORT% %HOST%

endlocal
