@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "CONTROLLER=%~1"
if "%CONTROLLER%"=="" set "CONTROLLER=http://127.0.0.1:8787"
set "WORKER_ID=%~2"
if "%WORKER_ID%"=="" set "WORKER_ID=%COMPUTERNAME%"

call build_windows.bat
if errorlevel 1 exit /b 1

echo.
echo ============================================================
echo  AUTONEWSROLLER WINDOWS GPU WORKER // WEB CONSOLE
echo  Browser: http://127.0.0.1:8788
echo  Controller: %CONTROLLER%
echo  Worker ID: %WORKER_ID%
echo ============================================================
echo.
echo Configure AUTONEWS_TOKEN in your environment if required.
echo Closing this window drains active work before the process exits.
java -cp build\classes autonewsroller.Main --worker-ui --controller-url "%CONTROLLER%" --worker-id "%WORKER_ID%"
if errorlevel 1 (
  echo Worker web console could not start. Check port 8788 and Java output above.
  exit /b 1
)
exit /b 0
