@echo off
setlocal EnableExtensions
cd /d "%~dp0"

set "CONTROLLER=%~1"
if "%CONTROLLER%"=="" set "CONTROLLER=http://127.0.0.1:8787"
set "WORKER_ID=%~2"
if "%WORKER_ID%"=="" set "WORKER_ID=%COMPUTERNAME%"

call build_windows.bat
if errorlevel 1 exit /b 1

echo Connecting GPU worker %WORKER_ID% to %CONTROLLER%
echo If the controller requires authentication, set AUTONEWS_TOKEN before running this file.
java -cp build\classes autonewsroller.Main --worker --controller-url "%CONTROLLER%" --worker-id "%WORKER_ID%"
exit /b %errorlevel%
