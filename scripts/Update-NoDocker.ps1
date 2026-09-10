$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue) { throw 'Stop the running RehaFlow server before updating.' }
& (Join-Path $PSScriptRoot 'Backup-NoDocker.ps1')
& npm.cmd ci
if ($LASTEXITCODE -ne 0) { throw 'Dependency installation failed.' }
& npm.cmd run build
if ($LASTEXITCODE -ne 0) { throw 'Build failed; existing database was not migrated.' }
& (Join-Path $PSScriptRoot 'Enable-LanHttps.ps1')
Write-Host 'Update built. Start-QureMed-Without-Docker.cmd will apply the database migration on startup.' -ForegroundColor Green
