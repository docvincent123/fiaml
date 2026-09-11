param([string]$ServerIp = '')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$configPath = Join-Path $projectRoot '.env.nodocker'
if (-not (Test-Path $configPath)) { throw 'Install the local database first with Start-QureMed-Without-Docker.cmd.' }
& (Join-Path $PSScriptRoot 'Repair-NoDockerConfig.ps1') -ConfigPath $configPath
$settings = [ordered]@{}
foreach ($line in [IO.File]::ReadAllLines($configPath)) {
    $split = $line.IndexOf('=')
    if ($split -gt 0 -and -not $line.StartsWith('#')) { $settings[$line.Substring(0,$split)] = $line.Substring($split+1) }
}
if (-not $ServerIp) {
    if ($settings['PUBLIC_URL']) { $ServerIp = ([Uri]$settings['PUBLIC_URL']).Host }
    else { $ServerIp = '192.168.1.106' }
}
$parsed = $null
if (-not [Net.IPAddress]::TryParse($ServerIp,[ref]$parsed) -or $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { throw 'Use the IPv4 address of THIS computer.' }
$addresses = @(Get-NetIPAddress -AddressFamily IPv4 | Select-Object -ExpandProperty IPAddress)
if ($ServerIp -notin $addresses) { throw "This computer does not have IP $ServerIp. Run ipconfig and use its Wi-Fi IPv4 address, not the router gateway." }
$caddy = Get-Command caddy.exe -ErrorAction SilentlyContinue
if (-not $caddy) {
    & winget.exe install --exact --id CaddyServer.Caddy --accept-package-agreements --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw 'Could not install Caddy for local HTTPS.' }
    $env:Path = [Environment]::GetEnvironmentVariable('Path','Machine') + ';' + [Environment]::GetEnvironmentVariable('Path','User')
    $caddy = Get-Command caddy.exe -ErrorAction Stop
}
$local = Join-Path $projectRoot '.local'
New-Item -ItemType Directory -Force $local | Out-Null
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $local /inheritance:r /grant:r "${currentUser}:(OI)(CI)(F)" '*S-1-5-18:(OI)(CI)(F)' '*S-1-5-32-544:(OI)(CI)(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect local certificate storage.' }
$storage = (Join-Path $local 'caddy-data').Replace('\','/')
$caddyConfig = @"
{
    admin off
    auto_https disable_redirects
    storage file_system {
        root "$storage"
    }
}
https://$ServerIp {
    tls internal
    encode gzip
    reverse_proxy 127.0.0.1:3000
}
"@
$caddyPath = Join-Path $local 'Caddyfile'
[IO.File]::WriteAllText($caddyPath,$caddyConfig,(New-Object Text.UTF8Encoding($false)))
& $caddy.Source validate --config $caddyPath --adapter caddyfile
if ($LASTEXITCODE -ne 0) { throw 'The HTTPS configuration is not valid.' }
$settings['PUBLIC_URL'] = 'https://' + $ServerIp
$settings['ALLOWED_ORIGINS'] = 'https://' + $ServerIp
$settings['HOST'] = '127.0.0.1'
$settings['PORT'] = '3000'
$settings['NODE_ENV'] = 'production'
$settings['TRUST_PROXY'] = '1'
$lines = @($settings.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
[IO.File]::WriteAllLines($configPath,$lines,(New-Object Text.UTF8Encoding($false)))
if (-not (Get-NetFirewallRule -DisplayName 'QureMed Local HTTPS' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'QureMed Local HTTPS' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 443 -RemoteAddress LocalSubnet -Profile Private | Out-Null
}
Write-Host "Local HTTPS configured: https://$ServerIp. PostgreSQL remains on this PC (127.0.0.1)." -ForegroundColor Green
