@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "INTERVAL_MINUTES=30"
if not "%~1"=="" set "INTERVAL_MINUTES=%~1"

call build_windows.bat
if errorlevel 1 exit /b 1

:loop
echo.
echo ============================================================
echo AutoNewsRoller watch cycle - scanning ALL enabled RSS feeds
echo ============================================================
java -cp build\classes autonewsroller.Main --batch-target 1 --category general
set "RUN_STATUS=!errorlevel!"

if "!RUN_STATUS!"=="0" (
    echo Watch cycle completed with an approved video.
) else if "!RUN_STATUS!"=="2" (
    echo No verified story was available this cycle.
    echo All enabled RSS feeds were scanned before this result.
) else (
    echo Watch cycle failed with exit code !RUN_STATUS!.
    echo The watcher will retry after the configured interval.
)

for /f "delims=" %%A in ('powershell -NoProfile -Command "[int](%INTERVAL_MINUTES%*60)"') do set "WAIT_SECONDS=%%A"
echo Next full RSS scan in %INTERVAL_MINUTES% minute(s). Press Ctrl+C to stop.
timeout /t !WAIT_SECONDS! /nobreak >nul
goto loop
