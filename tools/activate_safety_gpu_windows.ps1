# Hardware-gated AutoNewsRoller GPU safety activation.
# Never enables CUDA without passing local model, frame and performance tests.
param(
    [string]$ImagesList = "",
    [string]$VideosList = "",
    [switch]$ConfirmFixtureRights
)
$ErrorActionPreference = "Stop"
# CPU reference/benchmark work must not saturate the 48-thread Threadripper.
$env:AUTONEWS_SAFETY_CPU_THREADS = "8"
$env:OMP_NUM_THREADS = "8"
$env:OPENBLAS_NUM_THREADS = "8"
$env:MKL_NUM_THREADS = "8"
$env:NUMEXPR_NUM_THREADS = "8"
$env:TF_NUM_INTRAOP_THREADS = "8"
$env:TF_NUM_INTEROP_THREADS = "1"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $root ("output/runtime/gpu-activation/" + $stamp)
New-Item -Path $runDir -ItemType Directory -Force | Out-Null
$report = [ordered]@{ status = "NOT_APPROVED"; startedUtc = (Get-Date).ToUniversalTime().ToString("o"); results = @(); failures = @() }
function Step([string]$name) {
    Write-Host ""
    Write-Host ("[GPU SAFETY] " + $name) -ForegroundColor Cyan
    $report.results += $name
}
function PythonCommand([string]$executable, [string[]]$arguments) {
    & $executable @arguments
    if ($LASTEXITCODE -ne 0) { throw ("Python failed (exit " + $LASTEXITCODE + "): " + ($arguments -join " ")) }
}
function Lines([string]$path) {
    $content = @((Get-Content -LiteralPath $path -Encoding UTF8) |
        ForEach-Object { $_.Trim() } | Where-Object { $_ -and -not $_.StartsWith("#") })
    if ($content.Count -eq 0) { throw "Fixture list is empty: $path" }
    return $content
}
function ResolveFixtures([string]$listPath) {
    $resolved = (Resolve-Path -LiteralPath $listPath).Path
    $parent = Split-Path $resolved -Parent
    $items = @(Lines $resolved)
    return @($items | ForEach-Object {
        $entry = $_
        if (-not [System.IO.Path]::IsPathRooted($entry)) {
            $entry = Join-Path $parent $entry
        }
        (Resolve-Path -LiteralPath $entry).Path
    })
}
function WriteUtf8Lines([string]$path, [string[]]$lines) {
    [System.IO.File]::WriteAllLines($path, $lines, (New-Object System.Text.UTF8Encoding($false)))
}
function ProbeFrames([string]$video) {
    $n = & ffprobe -v error -select_streams v:0 -count_frames -show_entries stream=nb_read_frames -of default=noprint_wrappers=1:nokey=1 "$video"
    if ($LASTEXITCODE -ne 0) { throw "Could not count MP4 video frames: $video" }
    $count = 0
    if (-not [int]::TryParse("$n".Trim(), [ref]$count)) { throw "Unknown video frame count for $video" }
    return $count
}
function VerifyFull([string]$python, [string]$script, [string]$backend, [string]$video, [string]$model, [string]$parity) {
    $args = @("tools/$script", "--video", $video, "--backend", $backend)
    if ($backend -eq "cuda") {
        $args += @("--parity-manifest", $parity)
        if ($script -eq "content_context_scan.py") { $args += @("--onnx-model", $model) }
    }
    $output = & $python @args
    if ($LASTEXITCODE -ne 0) { throw "$script $backend full-video audit failed" }
    $data = $output | ConvertFrom-Json
    if ($data.status -notin @("SAFE","UNSAFE","REVIEW_REQUIRED")) { throw "Invalid full-video safety decision" }
    if ($data.framesDecoded -lt 100 -or $data.framesDecoded -ne $data.framesInspected -or $data.framesExpected -ne $data.framesDecoded) {
        throw "Full-video frame coverage mismatch"
    }
    if ($backend -eq "cuda" -and ($data.backend -ne "cuda" -or $data.cudaNodes -lt 1)) {
        throw "Full-video CUDA inference was not proven"
    }
    return $data
}
try {
    Step "Check that the controller and GPU worker are stopped."
    $live = @(Get-CimInstance Win32_Process | Where-Object {
        $_.Name -in @("java.exe","javaw.exe") -and
        $_.CommandLine -match "autonewsroller[.]Main" -and
        $_.CommandLine -match "command-center|worker-ui|gpu-worker"
    })
    if ($live.Count -gt 0) {
        throw "AutoNewsRoller is still running. Use DRAIN / STOP, close controller and worker, then rerun this batch file. PIDs: $($live.ProcessId -join ', ')"
    }

    Step "Confirm rights for local media test fixtures."
    if (-not $ConfirmFixtureRights) {
        $confirmation = Read-Host "Confirm you own or have permission to use locally selected test images/videos (type YES)"
        if ($confirmation -cne "YES") { throw "Fixture rights were not confirmed; GPU remains disabled" }
    }

    Step "Select GPU and available Windows Python interpreter."
    $gpu = & nvidia-smi --query-gpu=name,memory.free,driver_version --format=csv,noheader,nounits
    if ($LASTEXITCODE -ne 0 -or -not $gpu) { throw "NVIDIA GPU driver or nvidia-smi unavailable" }
    Write-Host $gpu
    $memory = [int](("$gpu" -split ",")[1].Trim())
    if ($memory -lt 4096) { throw "Less than 4 GiB free VRAM. Close competing models before validation." }
    $python = ""
    foreach ($version in @("3.12","3.11","3.13")) {
        try {
            & py "-$version" -c "import sys;print(sys.version)" 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) { $python = "py -$version"; break }
        } catch {}
    }
    if (-not $python) { throw "Python 3.11, 3.12 or 3.13 via py launcher is required" }
    Write-Host ("Python selected: " + $python)

    Step "Install isolated CUDA scanner environment. Do not change CPU safety venv."
    & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root "tools/setup_content_safety_gpu_windows.ps1") -Python $python
    if ($LASTEXITCODE -ne 0) { throw "Isolated CUDA dependencies failed to install" }
    $gpupy = Join-Path $root ".venv-safety-gpu/Scripts/python.exe"
    $cpupy = Join-Path $root ".venv-safety/Scripts/python.exe"
    if (!(Test-Path -LiteralPath $gpupy) -or !(Test-Path -LiteralPath $cpupy)) {
        throw "CPU or GPU safety Python environment missing"
    }

    Step "Export the original OpenNSFW2 model; do not substitute third-party weights."
    $model = Join-Path $runDir "opennsfw2-0.19.0.onnx"
    PythonCommand $gpupy @("tools/export_opennsfw2_onnx.py","--output",$model)

    Step "Collect local licensed fixtures for hardware parity."
    $images = @()
    if ($ImagesList) {
        $images = @(ResolveFixtures $ImagesList)
    } else {
        $images = @(Get-ChildItem -LiteralPath (Join-Path $root "output") -Recurse -File -Filter "*.png" |
            Where-Object { $_.Name -match "comfy_attempt_[0-9]+[.]png$" } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 100 |
            ForEach-Object { $_.FullName } | Select-Object -Unique | Select-Object -First 25)
    }
    if ($images.Count -lt 25) {
        throw "Found $($images.Count) generated ComfyUI images; 25 heterogeneous images are required. Supply -ImagesList with 25 or more licensed images."
    }
    $imageDirs = @($images | ForEach-Object { Split-Path $_ -Parent } | Select-Object -Unique)
    if (-not $ImagesList -and $imageDirs.Count -lt 3) {
        throw "Auto-selected images come from fewer than 3 job directories. Provide a heterogeneous, licensed -ImagesList."
    }
    foreach ($file in $images) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing image fixture $file" } }
    $video = ""
    if ($VideosList) {
        $listedVideos = @(ResolveFixtures $VideosList)
        $video = $listedVideos[0]
    } else {
        $candidate = Get-ChildItem -LiteralPath (Join-Path $root "output") -Recurse -File -Filter "*.mp4" |
            Where-Object { $_.Length -gt 10000 } | Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($null -ne $candidate) { $video = $candidate.FullName }
    }
    if (-not $video -or -not (Test-Path -LiteralPath $video -PathType Leaf)) {
        throw "No finished MP4 located. Supply -VideosList containing a licensed, encoded MP4."
    }
    $sourceFrames = ProbeFrames $video
    if ($sourceFrames -lt 100) { throw "Selected MP4 has fewer than 100 complete frames" }
    $short = Join-Path $runDir "encoded-parity-clip.mp4"
    & ffmpeg -y -hide_banner -loglevel error -i "$video" -t 5 -an -c:v libx264 -preset veryfast -crf 22 "$short"
    if ($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath $short)) {
        throw "Could not create a complete encoded MP4 parity clip"
    }
    if ((ProbeFrames $short) -lt 100) { throw "Encoded parity clip has fewer than 100 frames" }
    $imagesFile = Join-Path $runDir "images.txt"
    $videosFile = Join-Path $runDir "videos.txt"
    WriteUtf8Lines $imagesFile ([string[]]$images)
    WriteUtf8Lines $videosFile @($short)

    Step "Compare BOTH pinned CPU models with actual NVIDIA CUDA nodes and complete decoded frames."
    $parity = Join-Path $runDir "gpu_safety_parity.json"
    PythonCommand $gpupy @("tools/validate_safety_gpu_parity.py","--images-list",$imagesFile,
        "--videos-list",$videosFile,"--context-onnx",$model,
        "--licensed-fixtures-confirmed","--output",$parity)
    $proof = Get-Content -LiteralPath $parity -Raw | ConvertFrom-Json
    if ($proof.status -ne "PASS" -or $proof.verifiedCudaNodes.NudeNet -lt 1 -or
        $proof.verifiedCudaNodes.OpenNSFW2 -lt 1 -or
        $proof.nudeNetDecisionFlips -ne 0 -or $proof.contextThresholdFlips -ne 0) {
        throw "CUDA parity evidence did not pass both model gates"
    }

    Step "Benchmark each model on complete encoded clips: 5 CPU vs 5 GPU trials."
    $cpuBench = Join-Path $runDir "cpu-benchmark.json"
    $gpuBench = Join-Path $runDir "gpu-benchmark.json"
    PythonCommand $cpupy @("tools/benchmark_safety_video.py","--backend","cpu","--python",$cpupy,
        "--video",$short,"--runs","5","--out",$cpuBench)
    PythonCommand $gpupy @("tools/benchmark_safety_video.py","--backend","cuda","--python",$gpupy,
        "--video",$short,"--parity-manifest",$parity,"--context-onnx",$model,
        "--runs","5","--out",$gpuBench)
    $cpu = Get-Content $cpuBench -Raw | ConvertFrom-Json
    $cuda = Get-Content $gpuBench -Raw | ConvertFrom-Json
    foreach ($detector in @("NudeNet","OpenNSFW2")) {
        $cpuResult = $cpu.modelResults.$detector
        $cudaResult = $cuda.modelResults.$detector
        if ($cudaResult.medianWallSeconds -ge $cpuResult.medianWallSeconds) {
            throw "$detector CUDA median was not faster than CPU; keep CPU mode while tuning CUDA backend"
        }
        Write-Host ("{0} CPU {1:N2}s; CUDA {2:N2}s" -f $detector,
            $cpuResult.medianWallSeconds, $cudaResult.medianWallSeconds)
    }

    Step "Verify both CUDA classifiers inspect every frame of the original MP4."
    foreach ($script in @("content_safety_scan.py","content_context_scan.py")) {
        $full = VerifyFull $gpupy $script "cuda" $video $model $parity
        Write-Host ("{0}: backend={1} frames={2} decision={3}" -f $full.detector,
            $full.backend, $full.framesInspected, $full.status)
    }

    Step "Enable hardware-validated GPU prefer mode in untracked runtime settings."
    $settings = Join-Path $root "output/runtime/safety_gpu_settings.properties"
    $modelUnix = $model.Replace('\','/')
    $parityUnix = $parity.Replace('\','/')
    $lines = @(
        "# Created by activate_gpu_safety_windows.bat after CUDA parity and benchmark PASS",
        "contentSafetyGpuMode=prefer",
        "contentSafetyGpuDevice=0",
        "contentSafetyGpuReserveMiB=4096",
        "contentSafetyGpuPython=auto",
        "contentSafetyGpuContextOnnx=$modelUnix",
        "contentSafetyGpuParityManifest=$parityUnix",
        "contentSafetyCpuThreads=8"
    )
    $pending = "$settings.pending"
    WriteUtf8Lines $pending $lines
    Move-Item -LiteralPath $pending -Destination $settings -Force
    $report.status = "GPU_PREFER_ENABLED"
    Write-Host ""
    Write-Host "GPU SAFETY ENABLED for next controller and worker startup." -ForegroundColor Green
    Write-Host "Controller AND worker must restart to load the GPU settings."
    Write-Host "CUDA provider/VRAM failures will use mandatory bounded CPU scans. Unsafe results still block."
} catch {
    $report.failures += $_.Exception.Message
    Write-Host ""
    Write-Host ("GPU SAFETY NOT ENABLED: " + $_.Exception.Message) -ForegroundColor Red
    Write-Host "The existing mandatory CPU scanners remain active with the new 8-thread budget."
} finally {
    $report.finishedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $reportPath = Join-Path $runDir "activation-report.json"
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host ("Activation report: " + $reportPath)
}
if ($report.status -ne "GPU_PREFER_ENABLED") { exit 1 }
