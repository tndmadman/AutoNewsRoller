@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
if /I "%~1"=="--self-test" goto selftest

echo AutoNewsRoller automated news video creator
echo.
set "TARGET=%~1"
set "WORKERS=%~2"
if "%TARGET%"=="" set /p "TARGET=Approved video target [30]: "
if "%TARGET%"=="" set "TARGET=30"
if "%WORKERS%"=="" set /p "WORKERS=Parallel workers [4]: "
if "%WORKERS%"=="" set "WORKERS=4"
set /p "CATEGORY=Output category [general] - all enabled RSS feeds are still scanned: "
if "%CATEGORY%"=="" set "CATEGORY=general"
set /p "AGE=Maximum article age [24 hours]: "
if "%AGE%"=="" set "AGE=24"
set /p "MINSRC=Minimum independent sources [2]: "
if "%MINSRC%"=="" set "MINSRC=2"
set /p "DURATION=Video duration [60 seconds]: "
if "%DURATION%"=="" set "DURATION=60"
echo Encoder: 1 Auto  2 NVIDIA NVENC  3 CPU x264
set /p "ENCCHOICE=Encoder [1]: "
if "%ENCCHOICE%"=="2" (set "ENCODER=nvenc") else if "%ENCCHOICE%"=="3" (set "ENCODER=x264") else (set "ENCODER=auto")
set /p "USECOMFY=Use local ComfyUI for optional illustrative visuals? y/N: "
set "COMFYFLAG="
if /I "%USECOMFY%"=="Y" set "COMFYFLAG=--comfyui"
if /I "%USECOMFY%"=="YES" set "COMFYFLAG=--comfyui"
set /p "KEEPOLLAMA=Keep Ollama model warm after requests? Y/n: "
set "OLLAMAFLAG=--keep-ollama-loaded"
if /I "%KEEPOLLAMA%"=="N" set "OLLAMAFLAG=--unload-ollama-after"
if /I "%KEEPOLLAMA%"=="NO" set "OLLAMAFLAG=--unload-ollama-after"
call build_windows.bat
if errorlevel 1 exit /b 1
for /f "delims=" %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "STAMP=%%I"
set "BATCHDIR=output\batch_!STAMP!"
start "AutoNewsRoller Dashboard" powershell -NoProfile -ExecutionPolicy Bypass -File "tools\batch_dashboard.ps1" -EventsPath "!BATCHDIR!\runtime\events.jsonl" -Target "%TARGET%"
java -cp build\classes autonewsroller.Main --batch-dir "!BATCHDIR!" --batch-target "%TARGET%" --workers "%WORKERS%" --category "%CATEGORY%" --max-age-hours "%AGE%" --minimum-independent-sources "%MINSRC%" --duration "%DURATION%" --encoder "%ENCODER%" %COMFYFLAG% %OLLAMAFLAG%
set "RUN_STATUS=!errorlevel!"
if "!RUN_STATUS!"=="2" (
    echo.
    echo All enabled RSS feeds were scanned, but there were not enough verified candidate stories to reach the requested target.
)
exit /b !RUN_STATUS!

:selftest
call build_windows.bat
if errorlevel 1 exit /b 1
where py >nul 2>nul && (set "PY=py") || (set "PY=python")
%PY% -m py_compile tools\fetch_news.py tools\article_extract.py tools\kokoro_tts.py tools\qwen3_tts.py tools\qwen3_tts_server.py
if errorlevel 1 exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -Command "$errors=$null;$tokens=$null;[System.Management.Automation.Language.Parser]::ParseFile((Resolve-Path 'tools\batch_dashboard.ps1'),[ref]$tokens,[ref]$errors)|Out-Null;if($errors.Count){$errors | ForEach-Object { Write-Error $_ };exit 1}"
if errorlevel 1 exit /b 1
java -cp build\classes autonewsroller.Main --self-test
if errorlevel 1 exit /b 1
java -cp build\classes autonewsroller.Main --dry-run --fixture --batch-target 1 --workers 2 --max-age-hours 24 --minimum-independent-sources 2 --duration 60 --encoder x264
if errorlevel 1 exit /b 1
exit /b 0
