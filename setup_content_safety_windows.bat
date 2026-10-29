@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

rem The pinned TensorFlow CPU 2.20 wheel supports CPython 3.10 through 3.13.
rem Keep all safety packages isolated from ComfyUI, Kokoro, and Qwen TTS.
set "SAFETY_PY=.venv-safety\Scripts\python.exe"
if not exist "%SAFETY_PY%" (
  set "BOOTSTRAP="
  rem Prefer a real installed CPython through the Windows Python launcher.
  for %%V in (3.13 3.12 3.11 3.10) do (
    if not defined BOOTSTRAP (
      py -%%V -c "import sys, venv, ensurepip; sys.exit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) and sys.maxsize > 2**32 else 1)" >nul 2>nul
      if not errorlevel 1 set "BOOTSTRAP=py -%%V"
    )
  )
  rem Not every Windows Python installation has the py launcher.
  if not defined BOOTSTRAP (
    python -c "import sys, venv, ensurepip; sys.exit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) and sys.maxsize > 2**32 else 1)" >nul 2>nul
    if not errorlevel 1 set "BOOTSTRAP=python"
  )
  if not defined BOOTSTRAP (
    echo ERROR: No compatible standalone 64-bit Python 3.10-3.13 was found.
    echo ComfyUI's embedded Python is not a supported virtualenv bootstrap.
    echo If you have Python 3.13, check: py -0p ^& python --version
    echo Otherwise install Python 3.13 using:
    echo   winget install --id Python.Python.3.13 -e --source winget
    echo Reopen your terminal and run setup_content_safety_windows.bat again.
    exit /b 1
  )
  echo [SAFETY] Creating dedicated Python environment using !BOOTSTRAP! ...
  !BOOTSTRAP! -m venv .venv-safety
  if errorlevel 1 (
    echo ERROR: Could not create .venv-safety. The chosen Python may not support venv.
    exit /b 1
  )
)
"%SAFETY_PY%" -c "import sys; sys.exit(0 if sys.version_info[:2] in ((3,10),(3,11),(3,12),(3,13)) and sys.maxsize > 2**32 else 1)"
if errorlevel 1 (
  echo ERROR: Existing .venv-safety is invalid or uses an unsupported Python.
  echo Rename .venv-safety to .venv-safety-old, then rerun setup.
  exit /b 1
)
"%SAFETY_PY%" --version
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
