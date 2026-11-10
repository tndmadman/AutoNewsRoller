param(
    [string]$Python = "",
    [string]$Video = "",
    [switch]$FullArchive
)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $root
if ([string]::IsNullOrWhiteSpace($Python)) {
    $candidate = Join-Path $root ".venv-safety\Scripts\python.exe"
    if (Test-Path $candidate) { $Python = $candidate }
    else { throw "Install a dedicated Python 3.12 safety environment; use -Python to select its python.exe" }
}
if (!(Test-Path $Python)) { throw "Safety Python interpreter missing: $Python" }
$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$folder = Join-Path $root ("output\content_safety\release_readiness\" + $stamp)
New-Item -ItemType Directory -Path $folder -Force | Out-Null
$report = [ordered]@{
    policy = "content-safety-2026-10-28-v3"
    startedUtc = (Get-Date).ToUniversalTime().ToString("o")
    computer = $env:COMPUTERNAME
    python = $Python
    detectorHealth = @{}
    checks = @{}
    notes = @()
}
function Invoke-PythonCheck([string]$label, [string[]]$arguments) {
    Write-Host "[SAFETY] $label"
    $output = & $Python @arguments 2>&1 | Out-String
    $status = $LASTEXITCODE
    Set-Content -LiteralPath (Join-Path $folder ($label + ".log")) -Value $output -Encoding UTF8
    if ($status -ne 0) { throw "$label failed (exit $status); log saved to $folder" }
    return ($output.Trim())
}
try {
    $report.checks["pythonVersion"] = Invoke-PythonCheck "python-version" @("--version")
    foreach ($name in @("content_safety_scan.py", "content_context_scan.py")) {
        $json = Invoke-PythonCheck ($name.Replace(".py", "") + "-health") @("tools/$name", "--health")
        $result = $json | ConvertFrom-Json
        if ($result.status -ne "READY" -or $result.policyVersion -ne $report.policy) {
            throw "$name did not load the current policy and model weights"
        }
        $report.detectorHealth[$result.detector] = $result
    }
    $smoke = Invoke-PythonCheck "dual-scanner-mp4-smoke" @("tools/test_content_safety_scan.py")
    $report.checks["syntheticVideoSmoke"] = ($smoke -like "*PASS*")
    if (!$report.checks["syntheticVideoSmoke"]) { throw "Synthetic MP4 safety smoke did not report PASS" }

    $ffmpeg = Get-Command ffmpeg -ErrorAction Stop
    $report.checks["ffmpegAvailable"] = $true
    $gpu = & nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw "nvidia-smi did not detect the expected production GPU" }
    $report.checks["nvidiaGpu"] = $gpu.Trim()
    Set-Content -LiteralPath (Join-Path $folder "nvidia-gpu.txt") -Value $gpu

    foreach ($item in @(@("comfyUI", "http://127.0.0.1:8188/system_stats"),
                        @("ollama", "http://127.0.0.1:11434/api/tags"))) {
        try {
            $response = Invoke-WebRequest -Uri $item[1] -UseBasicParsing -TimeoutSec 8
            $report.checks[$item[0]] = ($response.StatusCode -eq 200)
        } catch {
            $report.checks[$item[0]] = $false
            $report.notes += ($item[0] + " was not reachable; live pipeline validation remains blocked")
        }
    }

    if (![string]::IsNullOrWhiteSpace($Video)) {
        $inputFile = (Resolve-Path -LiteralPath $Video).Path
        $report.checks["sampleVideo"] = $inputFile
        foreach ($name in @("content_safety_scan.py", "content_context_scan.py")) {
            $key = $name.Replace(".py","") + "-sample"
            $audit = Invoke-PythonCheck $key @("tools/$name", "--video", $inputFile)
            $result = $audit | ConvertFrom-Json
            if ($result.status -ne "SAFE") { throw "$name rejected the actual sample MP4" }
            if ($result.framesDecoded -lt 1 -or
                $result.framesInspected -ne $result.framesDecoded -or
                $result.framesExpected -ne $result.framesDecoded) {
                throw "$name did not audit all frames of the sample MP4"
            }
            $report.checks[$key] = $result
        }
        $report.notes += "Actual MP4 scanned, but source-grounding and phone-legibility require visual review"
    } else {
        $report.notes += "No real finished MP4 supplied: complete end-to-end worker validation NOT PERFORMED"
    }

    if ($FullArchive) {
        $report.checks["archiveReportPath"] = Invoke-PythonCheck "historical-archive-audit" @(
            "tools/audit_historical_outputs.py", "--root", $root)
    }
    $report.status = if (($report.checks["comfyUI"] -eq $true) -and
                          ($report.checks["ollama"] -eq $true) -and
                          (![string]::IsNullOrWhiteSpace($Video))) { "NEEDS_HUMAN_VISUAL_REVIEW" }
                     else { "PARTIAL_VALIDATION" }
} catch {
    $report.status = "FAILED"
    $report.notes += $_.Exception.Message
    Write-Error $_.Exception.Message
} finally {
    $report.finishedUtc = (Get-Date).ToUniversalTime().ToString("o")
    $report | ConvertTo-Json -Depth 16 |
        Set-Content -LiteralPath (Join-Path $folder "release-readiness.json") -Encoding UTF8
    Write-Host "[SAFETY] Readiness report: $folder\release-readiness.json"
}
if ($report.status -eq "FAILED") { exit 1 }
