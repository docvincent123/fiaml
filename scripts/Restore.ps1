param([Parameter(Mandatory=$true)][string]$BackupFile)
$ErrorActionPreference = 'Stop'
$source = (Resolve-Path $BackupFile).Path
Set-Location (Split-Path $PSScriptRoot -Parent)
$answer = Read-Host 'This replaces the current database. Type RESTORE to continue'
if ($answer -cne 'RESTORE') { throw 'Cancelled.' }
docker compose stop server proxy
if ($LASTEXITCODE -ne 0) { throw 'Could not stop application.' }
docker compose cp $source db:/tmp/restore.dump
if ($LASTEXITCODE -ne 0) { throw 'Could not copy backup. Application remains stopped.' }
docker compose exec -T db pg_restore -U quremed -d quremed --clean --if-exists --single-transaction /tmp/restore.dump
if ($LASTEXITCODE -ne 0) { throw 'Restore failed. Application remains stopped; inspect database before restarting.' }
docker compose exec -T db psql -U quremed -d quremed -c 'UPDATE sessions SET revoked_at=now();'
if ($LASTEXITCODE -ne 0) { throw 'Could not revoke restored sessions. Application remains stopped.' }
docker compose exec -T db rm /tmp/restore.dump
docker compose up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Restored, but startup failed.' }
