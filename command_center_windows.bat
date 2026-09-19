@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "SCAN_MINUTES=10"
if not "%~1"=="" set "SCAN_MINUTES=%~1"

call build_windows.bat
if errorlevel 1 exit /b 1

echo.
echo ============================================================
echo  AUTONEWSROLLER COMMAND CENTER
echo  Controller: http://127.0.0.1:8787
echo  Full RSS scan every %SCAN_MINUTES% minute(s)
echo ============================================================
echo.

start "AutoNewsRoller GPU Worker" cmd /k java -cp build\classes autonewsroller.Main --worker --controller-url http://127.0.0.1:8787 --worker-id "%COMPUTERNAME%"
start "" powershell -NoProfile -Command "Start-Sleep -Seconds 2; Start-Process 'http://127.0.0.1:8787'"

java -cp build\classes autonewsroller.Main --command-center --host 127.0.0.1 --port 8787 --scan-minutes %SCAN_MINUTES% --auto-queue
exit /b %errorlevel%
