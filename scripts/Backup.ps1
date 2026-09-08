$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
New-Item -ItemType Directory -Force backups | Out-Null
$backupName = 'quremed-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.dump'
docker compose exec -T db pg_dump -U quremed -d quremed -Fc -f /tmp/quremed-backup.dump
if ($LASTEXITCODE -ne 0) { throw 'Database backup failed.' }
docker compose cp db:/tmp/quremed-backup.dump "backups/$backupName"
if ($LASTEXITCODE -ne 0) { throw 'Could not copy backup.' }
docker compose exec -T db rm /tmp/quremed-backup.dump
Write-Host "Backup saved: backups/$backupName. Copy to protected external storage."
