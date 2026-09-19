@echo off
setlocal EnableExtensions
cd /d "%~dp0"
where py >nul 2>nul && (set "PY=py -3.12") || (set "PY=python")
%PY% --version >nul 2>nul || set "PY=py"
%PY% --version || exit /b 1
where javac >nul 2>nul || (echo ERROR: JDK 21+ javac is required.& exit /b 1)
where ffmpeg >nul 2>nul || (echo ERROR: FFmpeg must be on PATH.& exit /b 1)
where ffprobe >nul 2>nul || (echo ERROR: ffprobe must be on PATH.& exit /b 1)
where ollama >nul 2>nul || echo WARNING: Ollama is not on PATH. Install it before non-dry runs.

if not exist .venv-news %PY% -m venv .venv-news || exit /b %errorlevel%
if not exist .venv-kokoro %PY% -m venv .venv-kokoro || exit /b %errorlevel%
if not exist .venv-qwen3-tts %PY% -m venv .venv-qwen3-tts || exit /b %errorlevel%

.venv-kokoro\Scripts\python.exe -m pip install --upgrade pip || exit /b %errorlevel%
.venv-kokoro\Scripts\python.exe -m pip install -r requirements-kokoro.txt || exit /b %errorlevel%
.venv-kokoro\Scripts\python.exe -c "import kokoro,soundfile,numpy; print('Kokoro imports passed')" || exit /b %errorlevel%

.venv-qwen3-tts\Scripts\python.exe -m pip install --upgrade pip || exit /b %errorlevel%
.venv-qwen3-tts\Scripts\python.exe -m pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu124 || exit /b %errorlevel%
.venv-qwen3-tts\Scripts\python.exe -m pip install -r requirements-qwen3-tts.txt qwen-tts || exit /b %errorlevel%
.venv-qwen3-tts\Scripts\python.exe -c "import torch; import qwen_tts; assert torch.cuda.is_available(), 'CUDA is not visible to PyTorch'; print('Qwen3-TTS CUDA imports passed:', torch.cuda.get_device_name(0))" || exit /b %errorlevel%

call build_windows.bat || exit /b %errorlevel%
echo.
echo Setup complete. Run:
echo   batch_create_news_videos_windows.bat --self-test
echo then:
echo   batch_create_news_videos_windows.bat 5 2
