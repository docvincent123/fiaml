$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Set-Location $projectRoot

function Test-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}
function New-HexSecret([int]$byteCount = 32) {
    $bytes = New-Object byte[] $byteCount
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}
function Get-PlainText([Security.SecureString]$secure) {
    $credential = New-Object Management.Automation.PSCredential('value', $secure)
    return $credential.GetNetworkCredential().Password
}
function Refresh-Path {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = $machinePath + ';' + $userPath
}
function Find-PostgresBin {
    $command = Get-Command psql.exe -ErrorAction SilentlyContinue
    if ($command) { return Split-Path $command.Source }
    $roots = @(
        (Join-Path $env:ProgramFiles 'PostgreSQL'),
        (Join-Path ${env:ProgramFiles(x86)} 'PostgreSQL')
    )
    foreach ($root in $roots) {
        if (-not $root -or -not (Test-Path $root)) { continue }
        $bin = Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending |
            ForEach-Object { Join-Path $_.FullName 'bin' } |
            Where-Object { Test-Path (Join-Path $_ 'psql.exe') } |
            Select-Object -First 1
        if ($bin) { return $bin }
    }
    return $null
}
function Select-ServerIp {
    $addresses = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixOrigin -ne 'WellKnown' -and
            $_.InterfaceAlias -notmatch 'Docker|WSL|Virtual|Bluetooth|Loopback'
        } |
        Sort-Object InterfaceMetric |
        Select-Object -ExpandProperty IPAddress -Unique
    $suggested = $addresses | Select-Object -First 1
    if ($suggested) {
        $entered = Read-Host "IPv4 this computer uses on Wi-Fi [$suggested]"
        if ([string]::IsNullOrWhiteSpace($entered)) { $entered = $suggested }
    } else {
        $entered = Read-Host 'IPv4 this computer uses on Wi-Fi (example 192.168.1.100)'
    }
    $parsed = $null
    if (-not [Net.IPAddress]::TryParse($entered, [ref]$parsed) -or
        $parsed.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork -or
        $parsed.ToString() -like '127.*') {
        throw 'Enter a valid local IPv4 address from ipconfig.'
    }
    return $parsed.ToString()
}

if (-not (Test-Administrator)) { throw 'Run Start-QureMed-Without-Docker.cmd as administrator.' }
if (-not (Get-Command winget.exe -ErrorAction SilentlyContinue)) {
    throw 'Windows Package Manager (winget) is missing. Install App Installer from Microsoft Store and run this file again.'
}

$configPath = Join-Path $projectRoot '.env.nodocker'
if (Test-Path $configPath) {
    Write-Host 'QureMed is already installed. Starting the local server...' -ForegroundColor Green
    & (Join-Path $PSScriptRoot 'Run-NoDocker.ps1')
    exit $LASTEXITCODE
}

Write-Host 'QureMed setup without Docker Desktop' -ForegroundColor Cyan
Write-Host 'The first installation needs Internet access and can take several minutes.'
Write-Host ''

Refresh-Path
if (-not (Get-Command node.exe -ErrorAction SilentlyContinue)) {
    Write-Host 'Installing Node.js 22 LTS...'
    & winget.exe install --exact --id OpenJS.NodeJS.LTS --silent --accept-package-agreements --accept-source-agreements --disable-interactivity
    if ($LASTEXITCODE -ne 0) { throw "Node.js installation failed with code $LASTEXITCODE." }
    Refresh-Path
}
$nodeVersion = (& node.exe -p "Number(process.versions.node.split('.')[0])")
if ($LASTEXITCODE -ne 0 -or [int]$nodeVersion -lt 22) {
    throw 'Node.js 22 or newer is required. Update Node.js and run this file again.'
}

$postgresBin = Find-PostgresBin
$postgresWasInstalled = [bool]$postgresBin
$postgresAdminPassword = $null
if (-not $postgresBin) {
    Write-Host 'Installing PostgreSQL 17...'
    $postgresAdminPassword = New-HexSecret 24
    $override = '--mode unattended --unattendedmodeui none --superpassword ' + $postgresAdminPassword + ' --serverport 5432'
    & winget.exe install --exact --id PostgreSQL.PostgreSQL.17 --accept-package-agreements --accept-source-agreements --disable-interactivity --override $override
    if ($LASTEXITCODE -ne 0) { throw "PostgreSQL installation failed with code $LASTEXITCODE." }
    Refresh-Path
    $postgresBin = Find-PostgresBin
    if (-not $postgresBin) { throw 'PostgreSQL was installed but psql.exe could not be found. Restart Windows and run this file again.' }
} else {
    Write-Host 'PostgreSQL is already installed.' -ForegroundColor Green
    $securePg = Read-Host 'Enter the password of the existing PostgreSQL user postgres' -AsSecureString
    $postgresAdminPassword = Get-PlainText $securePg
}

$service = Get-Service -Name 'postgresql*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($service -and $service.Status -ne 'Running') {
    Start-Service $service.Name
    $service.WaitForStatus('Running', [TimeSpan]::FromSeconds(45))
}

$env:PGPASSWORD = $postgresAdminPassword
$psql = Join-Path $postgresBin 'psql.exe'
$createdb = Join-Path $postgresBin 'createdb.exe'
try {
    & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1 -tAc 'SELECT 1' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Cannot log in to PostgreSQL. Check the postgres password and run this file again.' }

    $databasePassword = New-HexSecret 24
    $roleExists = & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT 1 FROM pg_roles WHERE rolname='quremed'"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect PostgreSQL roles.' }
    if ([string]::IsNullOrWhiteSpace(($roleExists | Out-String))) {
        & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1 -c "CREATE ROLE quremed LOGIN PASSWORD '$databasePassword'" *> $null
    } else {
        & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -v ON_ERROR_STOP=1 -c "ALTER ROLE quremed WITH LOGIN PASSWORD '$databasePassword'" *> $null
    }
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the QureMed database user.' }

    $databaseExists = & $psql -h 127.0.0.1 -p 5432 -U postgres -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='quremed'"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect PostgreSQL databases.' }
    if ([string]::IsNullOrWhiteSpace(($databaseExists | Out-String))) {
        & $createdb -h 127.0.0.1 -p 5432 -U postgres -O quremed quremed
        if ($LASTEXITCODE -ne 0) { throw 'Could not create the QureMed database.' }
    }
} finally {
    Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
    $postgresAdminPassword = $null
}

$serverIp = Select-ServerIp
$adminSecure = Read-Host 'Create the initial RehaFlow admin password (12+ characters)' -AsSecureString
$adminPassword = Get-PlainText $adminSecure
if ($adminPassword.Length -lt 12 -or $adminPassword.Length -gt 128 -or $adminPassword -match "[\r\n]") {
    throw 'The administrator password must contain 12-128 characters.'
}

if (-not (Get-NetFirewallRule -DisplayName 'QureMed Local Server' -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName 'QureMed Local Server' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 3000 -Profile Private | Out-Null
}

Write-Host 'Installing RehaFlow packages...'
$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
& $npm ci
if ($LASTEXITCODE -ne 0) { throw "npm install failed with code $LASTEXITCODE." }
Write-Host 'Building the server and React interface...'
& $npm run build
if ($LASTEXITCODE -ne 0) { throw "RehaFlow build failed with code $LASTEXITCODE." }

$lines = @(
    'DATABASE_URL=postgresql://quremed:' + $databasePassword + '@127.0.0.1:5432/quremed',
    'JWT_SECRET=' + (New-HexSecret),
    'ADMIN_LOGIN=admin',
    'ADMIN_PASSWORD=' + $adminPassword,
    'PUBLIC_URL=http://' + $serverIp + ':3000',
    'ALLOWED_ORIGINS=http://' + $serverIp + ':3000,http://localhost:3000,http://127.0.0.1:3000',
    'HOST=0.0.0.0',
    'PORT=3000',
    'NODE_ENV=development'
)
[IO.File]::WriteAllLines($configPath, $lines, (New-Object Text.UTF8Encoding($false)))
$adminPassword = $null
$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().Name
& icacls.exe $configPath /inheritance:r /grant:r "${currentUser}:(F)" '*S-1-5-18:(F)' '*S-1-5-32-544:(F)' | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Could not protect the local configuration file.' }

Write-Host ''
Write-Host 'Installation completed.' -ForegroundColor Green
Write-Host ('Open on the phone: http://' + $serverIp + ':3000') -ForegroundColor Green
Write-Host 'Login: admin' -ForegroundColor Green
& (Join-Path $PSScriptRoot 'Run-NoDocker.ps1')
