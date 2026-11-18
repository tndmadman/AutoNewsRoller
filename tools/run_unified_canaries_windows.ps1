# Isolated opt-in canary runner. Never connects to production queues or social publishing.
param(
    [switch]$RunLive,
    [string]$RunRoot = "",
    [string]$ExpectedSha = ""
)
$ErrorActionPreference = "Stop"
$source = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
if ($env:OS -ne "Windows_NT") { throw "This runner requires a Windows host" }
$sha = (& git -C $source rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $sha -notmatch "^[0-9a-f]{40}$") { throw "Valid Git HEAD is required" }
if ($ExpectedSha -and $ExpectedSha -ne $sha) { throw "Refusing mismatched HEAD: expected $ExpectedSha, got $sha" }
if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path ([IO.Path]::GetTempPath()) ("anr-unified-canary-" + [Guid]::NewGuid().ToString("N"))
}
if (Test-Path -LiteralPath $RunRoot) { throw "RunRoot already exists: refusing to overwrite existing artifacts" }
New-Item -ItemType Directory -Path $RunRoot | Out-Null
$checkout = Join-Path $RunRoot "checkout"
$batch = Join-Path $RunRoot "canaries"
$reportFile = Join-Path $RunRoot "unified_windows_canary_report.json"
$failures = New-Object System.Collections.Generic.List[string]
$machine = [ordered]@{ operatingSystem = [Environment]::OSVersion.VersionString; processor = "UNKNOWN"; graphics = "NOT_CHECKED" }
try {
    $cpu = Get-CimInstance Win32_Processor -ErrorAction Stop | Select-Object -First 1
    $machine.processor = [string]$cpu.Name
} catch { $machine.processor = "UNAVAILABLE" }
$report = [ordered]@{
    startedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    gitSha = $sha
    sourceBranch = ([string](& git -C $source branch --show-current)).Trim()
    runRoot = $RunRoot
    syntheticSourceFixtures = $true
    mode = $(if ($RunLive) { "LIVE_HARDWARE_REQUESTED" } else { "DRY_CHECK_ONLY" })
    gpu = $machine
    livePipelineStatus = "NOT_RUN"
    automatedMediaAuditStatus = "NOT_RUN"
    humanInspectionStatus = "NOT_REVIEWED"
    controllerWorkerLeaseTest = "NOT_RUN"
    socialPublishingStatus = "DISABLED"
    canaryExecutionReport = (Join-Path $batch "unified_canary_execution.json")
    mediaAuditReport = (Join-Path $batch "unified_acceptance_report.json")
    failures = @()
}
try {
    & git -C $source worktree add --detach $checkout $sha | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Isolated Git worktree creation failed" }
    $isolatedSha = (& git -C $checkout rev-parse HEAD).Trim()
    if ($isolatedSha -ne $sha) { throw "Isolated checkout SHA differs from validated source" }

    # Prevent external model process auto-start during acceptance. Reuse only
    # externally managed services already running on this machine.
    Add-Content -LiteralPath (Join-Path $checkout "defaults.txt") -Value "`ncomfyAutoStart=false`n"
    $previousHelperGuard = $env:AUTONEWS_CANARY_NO_HELPER_START
    $env:AUTONEWS_CANARY_NO_HELPER_START = "1"
    try {
        if (-not $RunLive) {
            $report.livePipelineStatus = "NOT_RUN"
        } else {
            if (-not (Get-Command nvidia-smi -ErrorAction SilentlyContinue)) {
                $report.gpu.graphics = "NVIDIA_DRIVER_NOT_FOUND"
                $report.livePipelineStatus = "NOT_RUN_NO_GPU"
            } else {
                $gpus = @( & nvidia-smi --query-gpu=name,driver_version --format=csv,noheader 2>$null )
                if ($LASTEXITCODE -ne 0 -or $gpus.Count -lt 1) {
                    $report.gpu.graphics = "NVIDIA_DRIVER_NOT_READY"
                    $report.livePipelineStatus = "NOT_RUN_NO_GPU"
                } else {
                    $report.gpu.graphics = ($gpus -join "; ")
                    $report.livePipelineStatus = "RUNNING"
                    Push-Location $checkout
                    try {
                        & cmd.exe /c build_windows.bat
                        if ($LASTEXITCODE -ne 0) { throw "Windows build failed" }
                        New-Item -ItemType Directory -Path $batch -Force | Out-Null
                        & java -cp build/classes autonewsroller.orchestration.UnifiedCanaryRunner $batch
                        if ($LASTEXITCODE -ne 0) { throw "One or more live pipeline canaries failed" }
                        $report.livePipelineStatus = "PASSED_AUTOMATED_PIPELINE"
                        & powershell -NoProfile -ExecutionPolicy Bypass -File tools/unified_acceptance_windows.ps1 -BatchRoot $batch -CaptureFrames
                        if ($LASTEXITCODE -ne 0) { throw "Automated MP4 media acceptance failed" }
                        $report.automatedMediaAuditStatus = "PASSED_STATIC_AND_MEDIA"
                    } finally {
                        Pop-Location
                    }
                }
            }
        }
    } finally {
        $env:AUTONEWS_CANARY_NO_HELPER_START = $previousHelperGuard
    }
} catch {
    $failures.Add($_.Exception.Message)
    if ($report.livePipelineStatus -eq "RUNNING") { $report.livePipelineStatus = "FAILED" }
    if ($report.automatedMediaAuditStatus -eq "NOT_RUN" -and $report.livePipelineStatus -eq "PASSED_AUTOMATED_PIPELINE") {
        $report.automatedMediaAuditStatus = "FAILED"
    }
} finally {
    # Preserve checkout, failed logs, MP4s, stage artifacts and extracted frames.
    # We started no long-running controller or worker and kill no external process.
    $report.failures = @($failures.ToArray())
    $report.finishedAtUtc = (Get-Date).ToUniversalTime().ToString("o")
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $reportFile -Encoding UTF8
    Write-Host ("Isolated canary report: " + $reportFile)
    Write-Host ("Live pipeline: " + $report.livePipelineStatus)
    Write-Host ("Media audit: " + $report.automatedMediaAuditStatus)
    Write-Host "Human inspection remains NOT_REVIEWED until a person inspects extracted frames."
}
if ($failures.Count -gt 0) { exit 1 }
