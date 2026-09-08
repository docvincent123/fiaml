$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Install and start Docker Desktop with Linux containers, then run this file again.' }
docker info *> $null
if ($LASTEXITCODE -ne 0) { throw 'Docker Desktop is not running.' }
if (-not (Test-Path '.env')) {
    $serverIp = Read-Host 'Local server IPv4 address (example 192.168.1.100)'
    $parsedIp = $null
    if (-not [System.Net.IPAddress]::TryParse($serverIp, [ref]$parsedIp) -or $parsedIp.AddressFamily -ne [System.Net.Sockets.AddressFamily]::InterNetwork) { throw 'Enter a valid IPv4 address.' }
    $adminPass = Read-Host 'Initial admin password (12+ chars; letters, digits, ! @ % _ - allowed)' -AsSecureString
    $credential = New-Object System.Management.Automation.PSCredential('admin', $adminPass)
    $plain = $credential.GetNetworkCredential().Password
    if ($plain -notmatch '^[a-zA-Z0-9!@%_-]{12,128}$') { throw 'Password must be 12-128 characters using letters, digits, ! @ % _ -.' }
    function New-HexSecret { $bytes = New-Object byte[] 32; $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create(); try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }; return -join ($bytes | ForEach-Object { $_.ToString('x2') }) }
    $lines = @(('DB_PASSWORD=' + (New-HexSecret)), ('JWT_SECRET=' + (New-HexSecret)), 'ADMIN_LOGIN=admin', ('ADMIN_PASSWORD=' + $plain), ('PUBLIC_URL=https://' + $serverIp))
    [System.IO.File]::WriteAllLines((Join-Path $PWD '.env'), $lines, (New-Object System.Text.UTF8Encoding($false)))
    $plain = $null
    $currentUser = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
    icacls '.env' /inheritance:r /grant:r "${currentUser}:(F)" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not restrict access to .env. Check file permissions.' }
}
docker compose up -d --build --wait --wait-timeout 240
if ($LASTEXITCODE -ne 0) { docker compose logs --tail 60; throw 'Server startup failed. Review logs above.' }
docker compose cp proxy:/data/caddy/pki/authorities/local/root.crt ./QureMed-Local-CA.crt
if ($LASTEXITCODE -ne 0) { throw 'Could not export the local public certificate.' }
Write-Host 'Server started. Install QureMed-Local-CA.crt on client devices; see README.md.'
Write-Host 'Run scripts/Trust-Local-Certificate.ps1 on this PC to trust this local server CA.'
Get-Content '.env' | Where-Object { $_ -like 'PUBLIC_URL=*' }
