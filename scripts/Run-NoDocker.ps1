$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

$configPath = Join-Path $projectRoot '.env.nodocker'
if (-not (Test-Path $configPath)) {
    throw 'Configuration is missing. Run Start-QureMed-Without-Docker.cmd first.'
}

foreach ($line in [System.IO.File]::ReadAllLines($configPath)) {
    if ([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#')) { continue }
    $separator = $line.IndexOf('=')
    if ($separator -lt 1) { continue }
    $name = $line.Substring(0, $separator)
    $value = $line.Substring($separator + 1)
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$env:Path = $machinePath + ';' + $userPath
$npm = Get-Command npm.cmd -ErrorAction SilentlyContinue
if (-not $npm) { throw 'Node.js was not found. Run the installer again.' }

# Re-evaluate the connected LAN on each start, including after a Wi-Fi change.
. (Join-Path $PSScriptRoot 'Server-Network.ps1')
if ($env:PUBLIC_URL -and $env:PUBLIC_URL.StartsWith('https://')) {
    $currentIp = Get-QureMedServerIp
    if (([Uri]$env:PUBLIC_URL).Host -ne $currentIp) {
        & (Join-Path $PSScriptRoot 'Enable-LanHttps.ps1') -ServerIp $currentIp
        $env:PUBLIC_URL = 'https://' + $currentIp
        $env:ALLOWED_ORIGINS = $env:PUBLIC_URL
        Write-Host 'Server address updated. Use the new Phone address on staff devices. The existing CA and database were preserved.' -ForegroundColor Yellow
    }
}

Write-Host ''
Write-Host 'QureMed Industries - RehaFlow local server' -ForegroundColor Cyan
Write-Host ('Computer: http://localhost:' + $env:PORT) -ForegroundColor Green
Write-Host ('Phone:    ' + $env:PUBLIC_URL) -ForegroundColor Green
Write-Host 'Keep this window open. Press Ctrl+C to stop the server.' -ForegroundColor Yellow
Write-Host ''
$proxy = $null
try {
    if ($env:PUBLIC_URL.StartsWith('https://')) {
        $caddy = Get-Command caddy.exe -ErrorAction Stop
        $caddyConfig = Join-Path $projectRoot '.local/Caddyfile'
        if (-not (Test-Path $caddyConfig)) { throw 'Run Enable-LanHttps.ps1 first.' }
        if (Get-NetTCPConnection -LocalPort 443 -State Listen -ErrorAction SilentlyContinue) { throw 'Port 443 is already in use. Stop the previous RehaFlow server before starting another.' }
        $proxy = Start-Process -FilePath $caddy.Source -ArgumentList @('run','--config',('"' + $caddyConfig + '"'),'--adapter','caddyfile') -PassThru -WindowStyle Hidden -RedirectStandardError (Join-Path $projectRoot '.local/caddy-error.log') -RedirectStandardOutput (Join-Path $projectRoot '.local/caddy.log')
        $ca = Join-Path $projectRoot '.local/caddy-data/pki/authorities/local/root.crt'
        for ($attempt=0; $attempt -lt 20 -and -not (Test-Path $ca); $attempt++) { Start-Sleep -Milliseconds 500; $proxy.Refresh(); if ($proxy.HasExited) { throw 'HTTPS stopped; see .local/caddy-error.log.' } }
        if (-not (Test-Path $ca)) { throw 'Local CA certificate was not created.' }
        Copy-Item $ca (Join-Path $projectRoot 'QureMed-Local-CA.crt') -Force
        Write-Host 'Install QureMed-Local-CA.crt on staff phones as a CA certificate.' -ForegroundColor Cyan
    }
    & $npm.Source run start
    if ($LASTEXITCODE -ne 0) { throw "Server stopped with code $LASTEXITCODE." }
} finally {
    if ($proxy) { $proxy.Refresh(); if (-not $proxy.HasExited) { Stop-Process -Id $proxy.Id } }
}
