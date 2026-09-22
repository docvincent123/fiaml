$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$file = Join-Path $root '.local/server-processes.json'
$tracked = @()
if (Test-Path $file) { $tracked = @(Get-Content -LiteralPath $file -Raw | ConvertFrom-Json) }
$task = Get-ScheduledTask -TaskName 'RehaFlow Server' -ErrorAction SilentlyContinue
if ($task) { Stop-ScheduledTask -TaskName 'RehaFlow Server' }
foreach ($item in $tracked) {
    $process = Get-Process -Id $item.id -ErrorAction SilentlyContinue
    if ($process -and $process.StartTime.ToUniversalTime().ToString('o') -eq $item.started -and $process.ProcessName -in @('node','caddy')) { Stop-Process -Id $process.Id }
}
Remove-Item -LiteralPath $file -ErrorAction SilentlyContinue
Write-Host 'Tracked RehaFlow server processes stopped. Other processes were not targeted.'
