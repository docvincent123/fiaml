param([string]$OutputDirectory = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $projectRoot '.backups' }
$config = Join-Path $projectRoot '.env.nodocker'
$line = [IO.File]::ReadAllLines($config) | Where-Object { $_.StartsWith('DATABASE_URL=') } | Select-Object -First 1
if (-not $line) { throw 'DATABASE_URL was not found.' }
$uri = [Uri]$line.Substring(13)
$credential = $uri.UserInfo.Split(':',2)
$dump = Get-Command pg_dump.exe -ErrorAction SilentlyContinue
if ($dump) { $dumpPath=$dump.Source } else {
    $dumpPath=Get-ChildItem (Join-Path $env:ProgramFiles 'PostgreSQL') -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin/pg_dump.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $dumpPath) { throw 'pg_dump.exe was not found.' }
New-Item -ItemType Directory -Force $OutputDirectory | Out-Null
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $OutputDirectory /inheritance:r /grant:r "${currentUser}:(OI)(CI)(F)" '*S-1-5-18:(OI)(CI)(F)' '*S-1-5-32-544:(OI)(CI)(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect backup directory.' }
$file = Join-Path $OutputDirectory ('quremed-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '.dump')
$previousPassword = $env:PGPASSWORD
try {
    $env:PGPASSWORD = [Uri]::UnescapeDataString($credential[1])
    & $dumpPath -h $uri.Host -p $uri.Port -U ([Uri]::UnescapeDataString($credential[0])) -d ($uri.AbsolutePath.TrimStart('/')) -Fc -f ($file + '.partial')
    if ($LASTEXITCODE -ne 0) { throw 'Backup failed; do not use the incomplete dump.' }
    $restorePath = Join-Path (Split-Path $dumpPath -Parent) 'pg_restore.exe'
    & $restorePath --list ($file + '.partial') | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Backup archive validation failed.' }
    Move-Item -LiteralPath ($file + '.partial') -Destination $file
    $status = @{completedAt=(Get-Date).ToUniversalTime().ToString('o');sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash;bytes=(Get-Item -LiteralPath $file).Length}
    $statusPath = Join-Path $projectRoot '.local/backup-status.json'
    $status | ConvertTo-Json | Set-Content -LiteralPath ($statusPath + '.tmp') -Encoding UTF8
    Move-Item -LiteralPath ($statusPath + '.tmp') -Destination $statusPath -Force
    Write-Host "Backup saved: $file (includes attached documents)." -ForegroundColor Green
} finally { $env:PGPASSWORD = $previousPassword }
