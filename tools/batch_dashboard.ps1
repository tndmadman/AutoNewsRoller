param(
    [Parameter(Mandatory=$true)][string]$EventsPath,
    [int]$Target = 0
)
$ErrorActionPreference='Stop'
$lastRender=''
while ($true) {
    $events=@()
    if (Test-Path -LiteralPath $EventsPath) {
        foreach($line in Get-Content -LiteralPath $EventsPath -Encoding UTF8) {
            if([string]::IsNullOrWhiteSpace($line)){continue}
            try{$events += ($line | ConvertFrom-Json)}catch{}
        }
    }
    $latest=@{}
    foreach($e in $events){if([int]$e.slot -gt 0){$latest[[int]$e.slot]=$e}}
    $approved=@($latest.Values | Where-Object {$_.stage -eq 'APPROVED'}).Count
    $rejected=@($latest.Values | Where-Object {$_.stage -eq 'REJECTED'}).Count
    $active=@($latest.Values | Where-Object {$_.stage -notin @('APPROVED','REJECTED')}).Count
    $lines=New-Object System.Collections.Generic.List[string]
    $lines.Add('AutoNewsRoller LIVE BATCH MONITOR')
    $targetText=if($Target -gt 0){"/$Target"}else{''}
    $lines.Add("APPROVED $approved$targetText    ACTIVE $active    REJECTED $rejected")
    $lines.Add('')
    $lines.Add('SLOT  WORKER  STAGE       DETAIL')
    foreach($k in @($latest.Keys | Sort-Object)){
        $e=$latest[$k];$detail=[string]$e.detail;if($detail.Length -gt 72){$detail=$detail.Substring(0,69)+'...'}
        $lines.Add(("{0:D3}   W{1:D2}     {2,-11} {3}" -f [int]$e.slot,[int]$e.worker,[string]$e.stage,$detail))
    }
    $lines.Add('')
    $lines.Add('RECENT EVENTS')
    foreach($e in @($events | Select-Object -Last 8)){
        try{$ts=[DateTimeOffset]::Parse($e.timestamp).ToLocalTime().ToString('HH:mm:ss')}catch{$ts='--:--:--'}
        $lines.Add(("$ts W{0:D2} SLOT {1:D3} {2} {3}" -f [int]$e.worker,[int]$e.slot,[string]$e.stage,[string]$e.detail))
    }
    $render=$lines -join [Environment]::NewLine
    if($render -ne $lastRender){Clear-Host;Write-Host $render;$lastRender=$render}
    Start-Sleep -Milliseconds 750
}
