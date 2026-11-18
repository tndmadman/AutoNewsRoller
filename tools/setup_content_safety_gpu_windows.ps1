# Isolated, opt-in GPU safety environment. Does not touch the live CPU venv.
param([string]$Python = "py -3.12", [switch]$CreateOnly)
$ErrorActionPreference="Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
$root=(Get-Location).Path
$venv=Join-Path $root ".venv-safety-gpu"
$exe=Join-Path $venv "Scripts/python.exe"
if(-not (Test-Path -LiteralPath $exe)){
    $args=@($Python -split '\s+')
    if($args.Count -eq 0){throw "Python interpreter is required"}
    & $args[0] @($args|Select-Object -Skip 1) -m venv $venv
    if($LASTEXITCODE -ne 0){throw "GPU virtualenv creation failed"}
}
if(-not (Test-Path -LiteralPath $exe)){throw "GPU virtualenv Python missing"}
& $exe -m pip install -r requirements-content-safety-gpu.txt
if($LASTEXITCODE -ne 0){throw "GPU dependencies installation failed"}
# NudeNet depends on separately named onnxruntime CPU wheel. Both installed
# in a single environment may shadow the CUDA module. Enforce GPU-only wheel.
& $exe -m pip uninstall -y onnxruntime
if($LASTEXITCODE -ne 0){throw "Could not remove shadowing CPU ORT wheel"}
& $exe -m pip install --no-deps --force-reinstall onnxruntime-gpu==1.20.1
if($LASTEXITCODE -ne 0){throw "Could not activate isolated GPU ORT wheel"}
& $exe -c "import onnxruntime as o; assert o.__version__=='1.20.1'; assert 'CUDAExecutionProvider' in o.get_available_providers(); print('ORT EP installed; hardware and model inference NOT YET VALIDATED')"
if($LASTEXITCODE -ne 0){throw "CUDA provider not present in isolated environment"}
if($CreateOnly){Write-Host "GPU environment created. GPU scanner remains DISABLED in defaults.txt";exit 0}
# Driver/DLL readiness is a prerequisite to the actual parity validator.
nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
if($LASTEXITCODE -ne 0){throw "CUDA driver unavailable; CPU safety remains active"}
Write-Host "CUDA environment installed. Execute parity validator on licensed frames."
Write-Host "Set contentSafetyGpuMode=prefer only AFTER validator PASS and actual CUDA nodes are verified."
