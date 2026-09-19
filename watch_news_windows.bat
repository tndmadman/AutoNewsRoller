@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo watch_news_windows.bat now launches the AutoNewsRoller Command Center.
echo The first argument remains the RSS scan interval in minutes.
call command_center_windows.bat %*
exit /b %errorlevel%
