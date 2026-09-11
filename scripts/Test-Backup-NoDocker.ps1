param([Parameter(Mandatory=$true)][string]$BackupFile)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$BackupFile = (Resolve-Path -LiteralPath $BackupFile).Path
$line = [IO.File]::ReadAllLines((Join-Path $root '.env.nodocker')) | Where-Object { $_.StartsWith('DATABASE_URL=') } | Select-Object -First 1
$uri = [Uri]$line.Substring(13)
$restore = Get-ChildItem (Join-Path $env:ProgramFiles 'PostgreSQL') -Directory | Sort-Object Name -Descending | ForEach-Object { Join-Path $_.FullName 'bin/pg_restore.exe' } | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $restore) { throw 'PostgreSQL tools not found.' }
$bin = Split-Path $restore -Parent
$db = 'rehaflow_verify_' + [Guid]::NewGuid().ToString('N')
$secret = Read-Host 'Existing PostgreSQL postgres password (test database only)' -AsSecureString
$credential = New-Object System.Management.Automation.PSCredential('postgres',$secret)
$previous = $env:PGPASSWORD
$created = $false
try {
    $env:PGPASSWORD = $credential.GetNetworkCredential().Password
    & (Join-Path $bin 'createdb.exe') -w -h $uri.Host -p $uri.Port -U postgres -T template0 $db
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated verification database.' }
    $created = $true
    & $restore -w -h $uri.Host -p $uri.Port -U postgres --exit-on-error --no-owner --no-privileges -d $db $BackupFile
    if ($LASTEXITCODE -ne 0) { throw 'Restore verification failed.' }
    & (Join-Path $bin 'psql.exe') -w -h $uri.Host -p $uri.Port -U postgres -d $db -v ON_ERROR_STOP=1 -c 'SELECT count(*) AS patients FROM patients; SELECT count(*) AS tasks FROM tasks; SELECT count(*) AS documents FROM patient_documents;'
    if ($LASTEXITCODE -ne 0) { throw 'Restored database checks failed.' }
    $result = @{verifiedAt=(Get-Date).ToUniversalTime().ToString('o');sha256=(Get-FileHash -LiteralPath $BackupFile -Algorithm SHA256).Hash}
    $result | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $root '.local/restore-status.json') -Encoding UTF8
    Write-Host 'Restore verified in an isolated database. Production database was not changed.' -ForegroundColor Green
} finally {
    if ($created) {
        & (Join-Path $bin 'dropdb.exe') -w -h $uri.Host -p $uri.Port -U postgres $db
        if ($LASTEXITCODE -ne 0) { Write-Warning ('Remove leftover verification database manually: ' + $db) }
    }
    $env:PGPASSWORD = $previous
}
