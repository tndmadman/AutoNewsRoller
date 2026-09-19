@echo off
setlocal EnableExtensions
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "tools\live_acceptance_windows.ps1" %*
exit /b %errorlevel%
