param(
  [string]$Category = "general",
  [int]$Target = 3
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $Root

function Require-Command([string]$Name) {
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "Required command not found: $Name"
  }
}

function Parse-Rate([string]$Value) {
  if ($Value -match "^([0-9.]+)/([0-9.]+)$") {
    return [double]$Matches[1] / [Math]::Max(0.000001,[double]$Matches[2])
  }
  return [double]$Value
}

Require-Command "java"
Require-Command "ffmpeg"
Require-Command "ffprobe"

& "$Root\build_windows.bat"
if ($LASTEXITCODE -ne 0) { throw "Java build failed." }

$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$relativeBatch = "output/acceptance_$stamp"
$batch = Join-Path $Root $relativeBatch
New-Item -ItemType Directory -Force -Path $batch | Out-Null
$runLog = Join-Path $batch "live_acceptance.log"
$reportPath = Join-Path $batch "acceptance_report.json"
$comfyLog = Join-Path $Root "output/runtime/comfyui.log"
$comfyStartLines = 0
if (Test-Path $comfyLog) { $comfyStartLines = (Get-Content $comfyLog).Count }

Write-Host "============================================================"
Write-Host " AutoNewsRoller live acceptance"
Write-Host " Stories: $Target"
Write-Host " Category: $Category"
Write-Host " Batch: $relativeBatch"
Write-Host "============================================================"

$args = @(
  "-cp","build\classes","autonewsroller.Main",
  "--batch-target",$Target,
  "--workers","1",
  "--category",$Category,
  "--duration","70",
  "--encoder","auto",
  "--comfyui",
  "--batch-dir",$relativeBatch
)

& java @args 2>&1 | Tee-Object -FilePath $runLog
$javaExit = $LASTEXITCODE
if ($javaExit -ne 0) { throw "Live batch failed with exit code $javaExit. See $runLog" }

$videos = @(Get-ChildItem (Join-Path $batch "final_videos") -Filter *.mp4 -File | Sort-Object LastWriteTime)
if ($videos.Count -lt $Target) { throw "Expected at least $Target final videos, found $($videos.Count)." }

$frameDir = Join-Path $batch "representative_frames"
New-Item -ItemType Directory -Force -Path $frameDir | Out-Null

$videoReports = @()
$failures = New-Object System.Collections.Generic.List[string]

foreach ($video in $videos | Select-Object -First $Target) {
  $probeRaw = & ffprobe -v error -show_entries format=duration -show_entries stream=index,codec_type,width,height,avg_frame_rate,r_frame_rate -of json $video.FullName
  if ($LASTEXITCODE -ne 0) { throw "ffprobe failed for $($video.FullName)" }
  $probe = $probeRaw | ConvertFrom-Json
  $duration = [double]$probe.format.duration
  $vstream = @($probe.streams | Where-Object { $_.codec_type -eq "video" })[0]
  $avgFps = Parse-Rate ([string]$vstream.avg_frame_rate)
  $nominalFps = Parse-Rate ([string]$vstream.r_frame_rate)

  $sidecarPath = $video.FullName + ".json"
  if (-not (Test-Path $sidecarPath)) { $failures.Add("Missing sidecar for $($video.Name)") }
  $sidecar = if (Test-Path $sidecarPath) { Get-Content $sidecarPath -Raw | ConvertFrom-Json } else { $null }

  $storyScenes = 0
  $totalScenes = 0
  $uniqueImages = 0
  $ttsEngine = ""
  $audioDuration = 0
  $narrationWords = 0
  if ($sidecar) {
    $items = @($sidecar.visualPlan.items)
    $totalScenes = $items.Count
    $storyScenes = @($items | Where-Object { $_.type -ne "SOURCE_CARD" }).Count
    $uniqueImages = [int]$sidecar.comfyImagesGenerated
    $ttsEngine = [string]$sidecar.ttsEngineActuallyUsed
    $audioDuration = [double]$sidecar.narrationAudioDuration
    $narrationWords = [int]$sidecar.narrationWords
  }

  if ($duration -le 60) { $failures.Add("$($video.Name): duration $duration <= 60") }
  if ($duration -lt 65 -or $duration -gt 76) { $failures.Add("$($video.Name): final duration $duration outside 65-76 target band") }
  if ($vstream.width -ne 1080 -or $vstream.height -ne 1920) { $failures.Add("$($video.Name): wrong resolution $($vstream.width)x$($vstream.height)") }
  if ([Math]::Abs($avgFps-30.0) -gt 0.05 -or [Math]::Abs($nominalFps-30.0) -gt 0.05) { $failures.Add("$($video.Name): not CFR 30fps avg=$avgFps nominal=$nominalFps") }
  if ($ttsEngine -ne "Kokoro") { $failures.Add("$($video.Name): TTS actually used '$ttsEngine' instead of healthy Kokoro") }
  if ($audioDuration -lt 65 -or $audioDuration -gt 72) { $failures.Add("$($video.Name): narration audio $audioDuration outside 65-72s") }
  if ($storyScenes -lt 7 -or $storyScenes -gt 9) { $failures.Add("$($video.Name): story scenes=$storyScenes") }
  if ($uniqueImages -lt 6 -or $uniqueImages -gt 8) { $failures.Add("$($video.Name): unique Comfy images=$uniqueImages") }
  if ($narrationWords -lt 165 -or $narrationWords -gt 220) { $failures.Add("$($video.Name): narration words=$narrationWords") }

  $safe = [IO.Path]::GetFileNameWithoutExtension($video.Name) -replace '[^A-Za-z0-9._-]','_'
  $times = @(5.0, $duration*0.35, $duration*0.65, [Math]::Max(1,$duration-4.0))
  $frames = @()
  for ($i=0; $i -lt $times.Count; $i++) {
    $frame = Join-Path $frameDir ("{0}_{1:00}.jpg" -f $safe,($i+1))
    & ffmpeg -y -hide_banner -loglevel error -ss ("{0:0.000}" -f $times[$i]) -i $video.FullName -frames:v 1 -q:v 2 $frame
    if ($LASTEXITCODE -eq 0 -and (Test-Path $frame)) { $frames += $frame }
  }

  $videoReports += [ordered]@{
    file = $video.FullName
    duration = [Math]::Round($duration,3)
    width = [int]$vstream.width
    height = [int]$vstream.height
    averageFps = [Math]::Round($avgFps,4)
    nominalFps = [Math]::Round($nominalFps,4)
    ttsEngine = $ttsEngine
    narrationAudioDuration = [Math]::Round($audioDuration,3)
    narrationWords = $narrationWords
    storyScenes = $storyScenes
    totalScenes = $totalScenes
    uniqueComfyImages = $uniqueImages
    representativeFrames = $frames
  }
}

$runLines = Get-Content $runLog
$timings = @()
foreach ($line in $runLines) {
  if ($line -match "\[ComfyUI\] Image ([0-9]+) completed in ([0-9.]+) sec") {
    $timings += [double]$Matches[2]
  }
}
if ($timings.Count -lt 5) { $failures.Add("Only $($timings.Count) Comfy image timings found; need >=5 consecutive generations.") }

$newComfyLines = @()
if (Test-Path $comfyLog) { $newComfyLines = @(Get-Content $comfyLog | Select-Object -Skip $comfyStartLines) }
$clipLoads = @($newComfyLines | Where-Object { $_ -match "Requested to load SDXLClipModel" }).Count
$sdxlLoads = @($newComfyLines | Where-Object { $_ -match "Requested to load SDXL(?!ClipModel)" }).Count
$vaeLoads = @($newComfyLines | Where-Object { $_ -match "Requested to load AutoencoderKL" }).Count
if ($clipLoads -gt 1 -or $sdxlLoads -gt 1 -or $vaeLoads -gt 1) {
  $failures.Add("Comfy model reloads exceeded one cold load: CLIP=$clipLoads SDXL=$sdxlLoads VAE=$vaeLoads")
}

$cold = if ($timings.Count -gt 0) { $timings[0] } else { $null }
$warm = if ($timings.Count -gt 1) { @($timings | Select-Object -Skip 1) } else { @() }
$warmAvg = if ($warm.Count -gt 0) { ($warm | Measure-Object -Average).Average } else { $null }

$report = [ordered]@{
  generatedAt = (Get-Date).ToString("o")
  batch = $batch
  category = $Category
  targetVideos = $Target
  videos = $videoReports
  comfy = [ordered]@{
    imageTimingsSeconds = $timings
    firstImageSeconds = $cold
    coldStartObserved = ($clipLoads -gt 0 -or $sdxlLoads -gt 0 -or $vaeLoads -gt 0)
    subsequentAverageSeconds = $warmAvg
    newLogModelLoads = [ordered]@{
      SDXLClipModel = $clipLoads
      SDXL = $sdxlLoads
      AutoencoderKL = $vaeLoads
    }
    fiveConsecutiveWarmReuse = ($timings.Count -ge 5 -and $clipLoads -le 1 -and $sdxlLoads -le 1 -and $vaeLoads -le 1)
  }
  representativeFramesDirectory = $frameDir
  failures = @($failures)
  passed = ($failures.Count -eq 0)
}
$report | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $reportPath

Write-Host ""
Write-Host "Acceptance report: $reportPath"
Write-Host "Representative frames: $frameDir"
if ($failures.Count -gt 0) {
  Write-Host "FAILED acceptance checks:" -ForegroundColor Red
  $failures | ForEach-Object { Write-Host " - $_" -ForegroundColor Red }
  exit 2
}
Write-Host "PASS: three-video live acceptance and Comfy warm-reuse checks." -ForegroundColor Green
exit 0
