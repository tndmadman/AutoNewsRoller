@echo off
setlocal
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
(for /r src %%F in (*.java) do @echo "%%F") > build\sources.txt
javac -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b %errorlevel%
echo Java compile passed.
