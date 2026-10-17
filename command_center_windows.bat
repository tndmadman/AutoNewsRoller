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
echo  Worker UI:  http://127.0.0.1:8788
echo  Full RSS scan every %SCAN_MINUTES% minute(s)
echo ============================================================
echo.

rem Reuse an existing worker console rather than spawning another claim loop.
powershell -NoProfile -Command "try { $r = Invoke-WebRequest -Uri 'http://127.0.0.1:8788/api/worker/health' -UseBasicParsing -TimeoutSec 2; if ($r.StatusCode -eq 200) { exit 0 } } catch {} ; exit 1" >nul 2>&1
if errorlevel 1 (
  start "AutoNewsRoller GPU Worker UI" cmd /k call run_gpu_worker_ui_windows.bat "http://127.0.0.1:8787" "%COMPUTERNAME%"
) else (
  echo Existing worker web console detected on port 8788; reusing it.
)

start "" powershell -NoProfile -Command "Start-Sleep -Seconds 2; Start-Process 'http://127.0.0.1:8787'"
java -cp build\classes autonewsroller.Main --command-center --host 127.0.0.1 --port 8787 --scan-minutes %SCAN_MINUTES% --auto-queue
exit /b %errorlevel%
