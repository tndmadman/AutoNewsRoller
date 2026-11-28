@echo off
setlocal
title AutoNewsRoller - Disable RTX GPU Safety
cd /d "%~dp0"
set "SETTINGS=%~dp0output\runtime\safety_gpu_settings.properties"
echo.
if exist "%SETTINGS%" (
  move /Y "%SETTINGS%" "%SETTINGS%.disabled" >nul
  if errorlevel 1 (
    echo ERROR: Could not disable GPU override.
    echo.
    pause
    exit /b 1
  )
  echo GPU runtime override disabled.
) else (
  echo No active GPU runtime override found.
)
echo.
echo Mandatory CPU safety scans remain enabled.
echo Restart both the controller and GPU worker to apply the change.
echo.
pause
exit /b 0
