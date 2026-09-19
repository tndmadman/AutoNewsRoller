@echo off
setlocal
cd /d "%~dp0"
call build_windows.bat || exit /b %errorlevel%
java -cp build\classes autonewsroller.Main --batch-target 1 %*
