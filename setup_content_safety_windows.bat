@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

set "SAFETY_PY=.venv-safety\Scripts\python.exe"
if not exist "%SAFETY_PY%" (
  set "BOOTSTRAP="
  py -3.12 -c "import sys; sys.exit(0 if sys.version_info[:2]==(3,12) else 1)" >nul 2>nul
  if not errorlevel 1 set "BOOTSTRAP=py -3.12"
  if not defined BOOTSTRAP (
    python -c "import sys; sys.exit(0 if sys.version_info[:2]==(3,12) else 1)" >nul 2>nul
    if not errorlevel 1 set "BOOTSTRAP=python"
  )
  if not defined BOOTSTRAP (
    echo ERROR: Python 3.12 is required for content safety.
    echo Install Python 3.12, then run this file again.
    exit /b 1
  )
  echo [SAFETY] Creating dedicated Python 3.12 environment...
  !BOOTSTRAP! -m venv .venv-safety
  if errorlevel 1 exit /b 1
)
echo [SAFETY] Installing pinned NudeNet, OpenNSFW2 and CPU TensorFlow...
"%SAFETY_PY%" -m pip install -r requirements-content-safety.txt
if errorlevel 1 (
  echo ERROR: Content-safety dependency installation failed. Worker exports remain blocked.
  exit /b 1
)
echo [SAFETY] Verifying both mandatory detectors and weights...
"%SAFETY_PY%" tools\content_safety_scan.py --health
if errorlevel 1 exit /b 1
"%SAFETY_PY%" tools\content_context_scan.py --health
if errorlevel 1 exit /b 1
echo [SAFETY] Validating actual MP4 frame checks...
"%SAFETY_PY%" tools\test_content_safety_scan.py
if errorlevel 1 exit /b 1
echo [SAFETY] READY. Restart the AutoNewsRoller GPU worker.
exit /b 0
