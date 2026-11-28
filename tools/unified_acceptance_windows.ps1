# Read-only acceptance audit for three canaries produced in an isolated checkout.
param([Parameter(Mandatory=$true)][string]$BatchRoot,[int]$ExpectedFps=30,[switch]$CaptureFrames)
$ErrorActionPreference = "Stop"
$root = (Resolve-Path -LiteralPath $BatchRoot).Path
if (-not (Get-Command ffprobe -ErrorAction SilentlyContinue)) { throw "ffprobe is required" }
if ($CaptureFrames -and -not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) { throw "ffmpeg is required" }
if ($ExpectedFps -le 0) { throw "ExpectedFps must be positive" }
$modes = @("STANDARD","MEME_RAGEBAIT","FRAMING_EXPOSURE")
$records = New-Object System.Collections.Generic.List[object]
$problems = New-Object System.Collections.Generic.List[string]
$reportPath = Join-Path $root "unified_acceptance_report.json"
$frameRoot = Join-Path $root "representative_frames"

function Rate([string]$value) {
    if ([string]::IsNullOrWhiteSpace($value)) { return [double]::NaN }
    $part = $value.Split("/")
    if ($part.Count -eq 2) {
        if ([double]$part[1] -eq 0) { return [double]::NaN }
        return [double]$part[0] / [double]$part[1]
    }
    return [double]$value
}
function Add-Failure([string]$item,[string]$reason) {
    $script:problems.Add(("{0}: {1}" -f $item,$reason))
}
function Capture([string]$file,[string]$prefix,[double]$duration) {
    if (-not $CaptureFrames) { return @() }
    New-Item -ItemType Directory -Force -Path $frameRoot | Out-Null
    $times = @(0.5,($duration*0.25),($duration*0.5),($duration*0.75),([Math]::Max(0.5,$duration-0.6)))
    $paths = @()
    for ($i=0; $i -lt $times.Count; $i++) {
        $out = Join-Path $frameRoot ("{0}_{1:00}.jpg" -f $prefix,($i+1))
        $args = @("-nostdin","-y","-hide_banner","-loglevel","error","-ss",([string]$times[$i]),"-i",$file,"-frames:v","1",$out)
        & ffmpeg @args | Out-Null
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $out)) {
            Add-Failure $prefix "Could not extract frame $i"
        } else { $paths += $out }
    }
    return $paths
}
try {
    $videos = @(Get-ChildItem -LiteralPath $root -Recurse -File -Filter "*.mp4" | Where-Object { $_.DirectoryName -match "(?i)final_videos" })
    if ($videos.Count -eq 0) { Add-Failure "batch" "No final_videos MP4 files found" }
    foreach ($video in $videos) {
        $before = $problems.Count
        $sidecarPath = $video.FullName + ".json"
        if (-not (Test-Path -LiteralPath $sidecarPath)) { Add-Failure $video.Name "Missing sidecar"; continue }
        try { $sidecar = Get-Content -LiteralPath $sidecarPath -Raw | ConvertFrom-Json }
        catch { Add-Failure $video.Name "Invalid sidecar JSON"; continue }
        $mode = [string]$sidecar.productionMode
        if ($modes -notcontains $mode) { Add-Failure $video.Name "Invalid mode"; continue }
        $hash = (Get-FileHash -LiteralPath $video.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        $approval = $sidecar.contentSafety
        if (-not $approval -or $approval.status -ne "APPROVED_FOR_EXPORT" -or
            $approval.policyVersion -ne "content-safety-2026-10-28-v3" -or
            ([string]$approval.videoSha256).ToLowerInvariant() -ne $hash) {
            Add-Failure $video.Name "Missing, expired or mismatched safety approval"
        }
        $safety = $approval.finalVideoAudit
        if (-not $safety -or $safety.status -ne "SAFE" -or [int]$safety.framesDecoded -lt 1 -or
            [int]$safety.framesInspected -ne [int]$safety.framesDecoded -or
            [int]$safety.framesExpected -ne [int]$safety.framesDecoded -or
            [int]$safety.disclosureFramesVerified -ne [int]$safety.framesDecoded -or
            -not [bool]$safety.disclosureVerified) {
            Add-Failure $video.Name "MP4 full-frame safety/disclosure coverage incomplete"
        }
        $context = $safety.contextAudit
        if (-not $context -or $context.status -ne "SAFE" -or [int]$context.framesDecoded -lt 1 -or
            [int]$context.framesInspected -ne [int]$context.framesDecoded -or
            [int]$context.framesExpected -ne [int]$context.framesDecoded) {
            Add-Failure $video.Name "OpenNSFW2 audit incomplete"
        }
        $probeArgs = @("-v","error","-show_entries","format=duration:stream=codec_type,codec_name,width,height,r_frame_rate,avg_frame_rate,duration","-of","json",$video.FullName)
        $probeText = & ffprobe @probeArgs
        if ($LASTEXITCODE -ne 0) { Add-Failure $video.Name "ffprobe failed"; continue }
        try { $probe = ($probeText -join [Environment]::NewLine) | ConvertFrom-Json }
        catch { Add-Failure $video.Name "Invalid ffprobe JSON"; continue }
        $videoStream = @($probe.streams | Where-Object { $_.codec_type -eq "video" } | Select-Object -First 1)
        $audioStream = @($probe.streams | Where-Object { $_.codec_type -eq "audio" } | Select-Object -First 1)
        if ($videoStream.Count -ne 1 -or $audioStream.Count -ne 1) { Add-Failure $video.Name "Missing encoded video or audio"; continue }
        $fps = Rate ([string]$videoStream[0].avg_frame_rate)
        $nominal = Rate ([string]$videoStream[0].r_frame_rate)
        $duration = [double]$probe.format.duration
        $videoSeconds = [double]$videoStream[0].duration
        $audioSeconds = [double]$audioStream[0].duration
        if ([int]$videoStream[0].width -ne 1080 -or [int]$videoStream[0].height -ne 1920) {
            Add-Failure $video.Name "Expected portrait 1080x1920 output"
        }
        if ([double]::IsNaN($fps) -or [double]::IsNaN($nominal) -or
            [Math]::Abs($fps-$ExpectedFps) -gt 0.05 -or
            [Math]::Abs($nominal-$ExpectedFps) -gt 0.05 -or
            [Math]::Abs($fps-$nominal) -gt 0.05) {
            Add-Failure $video.Name "Configured constant frame rate not met"
        }
        if ($videoSeconds -le 0 -or $audioSeconds -le 0 -or
            [Math]::Abs($videoSeconds-$audioSeconds) -gt 0.35) {
            Add-Failure $video.Name "Encoded video/audio duration mismatch"
        }
        if ($mode -eq "STANDARD" -and ($duration -lt 65 -or $duration -gt 85)) {
            Add-Failure $video.Name "STANDARD duration outside 65-85 seconds"
        }
        if ($mode -ne "FRAMING_EXPOSURE" -and [int]$sidecar.comfyImagesGenerated -le 0) {
            Add-Failure $video.Name "No accepted Comfy illustrations"
        }
        if ($mode -eq "FRAMING_EXPOSURE" -and [int]$sidecar.comfyImagesGenerated -ne 0) {
            Add-Failure $video.Name "Framing graphics unexpectedly contain generated image count"
        }
        $prefix = ($mode + "_" + $video.BaseName) -replace "[^A-Za-z0-9._-]","_"
        $frames = @(Capture $video.FullName $prefix $duration)
        $records.Add([ordered]@{
            mode=$mode;file=$video.FullName;sha256=$hash;durationSeconds=$duration
            averageFps=$fps;nominalFps=$nominal;videoStreamSeconds=$videoSeconds
            audioStreamSeconds=$audioSeconds;framesInspected=[int]$safety.framesInspected
            acceptedComfyImages=[int]$sidecar.comfyImagesGenerated
            ttsEngine=[string]$sidecar.ttsEngineActuallyUsed
            encoder=[string]$sidecar.videoEncoderActuallyUsed
            representativeFrames=$frames
            passedStaticChecks=($problems.Count -eq $before)
        })
    }
    foreach ($mode in $modes) {
        if (@($records | Where-Object { $_.mode -eq $mode -and $_.passedStaticChecks }).Count -eq 0) {
            Add-Failure "three-mode" "No passing $mode canary"
        }
    }
} catch {
    Add-Failure "harness" $_.Exception.Message
} finally {
    $report = [ordered]@{
        createdAtUtc=(Get-Date).ToUniversalTime().ToString("o")
        batchRoot=$root;expectedFps=$ExpectedFps;modes=$modes;videos=$records.ToArray()
        failures=$problems.ToArray();staticChecksPassed=($problems.Count -eq 0)
        liveHardwareExecutionVerified=$false;phoneVisualReviewComplete=$false
        note="Read-only verification of existing canaries. Execute real isolated controller/worker canaries and human phone-size review separately."
    }
    $report | ConvertTo-Json -Depth 16 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Host ("Unified acceptance report: " + $reportPath)
}
if ($problems.Count -gt 0) {
    foreach ($failure in $problems) { Write-Error $failure }
    exit 2
}
Write-Host "PASS: three-mode existing MP4 static verification (live-hardware review pending)"
