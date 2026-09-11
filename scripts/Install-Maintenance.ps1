param([switch]$Remove)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
# Per-user tasks retain the same Node/Caddy paths and certificate permissions.
$user = [Security.Principal.WindowsIdentity]::GetCurrent().Name
$names = @('RehaFlow Server','RehaFlow Backup')
if ($Remove) {
    foreach ($name in $names) { Unregister-ScheduledTask -TaskName $name -Confirm:$false -ErrorAction SilentlyContinue }
    Write-Host 'Automatic tasks removed. Existing data and backups are preserved.'
    exit
}
if (-not (Test-Path (Join-Path $root '.env.nodocker'))) { throw 'Configure the server first.' }
if (-not (Test-Path (Join-Path $root 'server/dist/main.js'))) { throw 'Build the server first.' }
$powerShell = Join-Path $PSHOME 'powershell.exe'
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
$serverAction = New-ScheduledTaskAction -Execute $powerShell -Argument ('-NoProfile -ExecutionPolicy Bypass -NonInteractive -WindowStyle Hidden -File "' + (Join-Path $PSScriptRoot 'Run-NoDocker.ps1') + '"') -WorkingDirectory $root
$backupAction = New-ScheduledTaskAction -Execute $powerShell -Argument ('-NoProfile -ExecutionPolicy Bypass -NonInteractive -WindowStyle Hidden -File "' + (Join-Path $PSScriptRoot 'Backup-NoDocker.ps1') + '"') -WorkingDirectory $root
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit ([TimeSpan]::Zero) -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $names[0] -Action $serverAction -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $user) -Principal $principal -Settings $settings -Force | Out-Null
$backupSettings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 2) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
Register-ScheduledTask -TaskName $names[1] -Action $backupAction -Trigger (New-ScheduledTaskTrigger -Daily -At '12:00') -Principal $principal -Settings $backupSettings -Force | Out-Null
Write-Host 'Server starts after THIS Windows user signs in. Daily backup: 12:00, missed runs start when available.' -ForegroundColor Green
Write-Host 'No second server is started now. Sign out/in after stopping your manual server.'
