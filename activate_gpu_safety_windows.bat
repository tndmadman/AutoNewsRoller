@echo off
setlocal
title AutoNewsRoller - Validate and Activate RTX GPU Safety
cd /d "%~dp0"
echo.
echo ============================================================
echo AUTO NEWS ROLLER -- RTX GPU SAFETY ACTIVATION
echo Keeps both safety scanners mandatory. No bypass.
echo STOP the worker and controller before continuing.
echo ============================================================
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\activate_safety_gpu_windows.ps1" %*
set "RESULT=%ERRORLEVEL%"
echo.
if not "%RESULT%"=="0" (
  echo GPU ACTIVATION DID NOT PASS. CPU safety remains mandatory.
) else (
  echo GPU SAFETY ENABLED. Restart controller and worker.
)
echo.
pause
exit /b %RESULT%
