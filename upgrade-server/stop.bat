@echo off
REM ============================================================
REM   Auto-Upgrade Server - Windows Stop Script
REM   Usage: stop.bat [port]
REM   Default: port=8090
REM   Find listening process by port and taskkill it
REM ============================================================

setlocal

set "PORT=8090"
if not "%~1"=="" set "PORT=%~1"

echo [INFO] Finding process listening on port %PORT% ...

set "PID="
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":*%PORT% " ^| findstr "LISTENING"') do (
    if not "%%a"=="0" (
        set "PID=%%a"
        goto :found
    )
)

echo [WARN] No process listening on port %PORT%, server may not be running
exit /b 1

:found
echo [INFO] Found PID=%PID%, stopping...
taskkill /F /PID %PID% >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Stop failed, may need administrator privileges
    exit /b 1
)
echo [INFO] Server stopped

endlocal
