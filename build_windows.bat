@echo off
setlocal EnableExtensions
cd /d "%~dp0"
if not exist build\classes mkdir build\classes
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Get-ChildItem -Path 'src' -Recurse -Filter '*.java' -File | ForEach-Object { [char]34 + $_.FullName.Replace('\','/') + [char]34 } | Set-Content -Encoding ASCII 'build\sources.txt'"
if errorlevel 1 exit /b 1
javac -encoding UTF-8 -d build\classes @build\sources.txt
if errorlevel 1 exit /b 1
echo Java compile passed.
exit /b 0
