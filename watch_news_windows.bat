@echo off
setlocal EnableExtensions
cd /d "%~dp0"
set "INTERVAL_MINUTES=30"
if not "%~1"=="" set "INTERVAL_MINUTES=%~1"
:loop
call run_news_video_windows.bat
for /f "delims=" %%A in ('powershell -NoProfile -Command "[int](%INTERVAL_MINUTES%*60)"') do set "WAIT_SECONDS=%%A"
timeout /t %WAIT_SECONDS% /nobreak >nul
goto loop
