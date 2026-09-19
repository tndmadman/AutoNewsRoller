@echo off
setlocal EnableExtensions
cd /d "%~dp0"

call build_windows.bat
if errorlevel 1 exit /b 1

java -cp build\classes autonewsroller.Main --batch-target 1 %*
exit /b %errorlevel%
