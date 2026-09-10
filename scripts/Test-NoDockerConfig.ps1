$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$source = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Setup-NoDocker.ps1'))
$block = [regex]::Match($source, '(?s)\$lines = @\(.*?\r?\n\)').Value
if (-not $block) { throw 'Installer configuration block not found.' }
$databasePassword = 'a' * 48
$adminPassword = 'Test = password with spaces'
$serverIp = '192.168.1.106'
function New-HexSecret { return ('b' * 64) }
Invoke-Expression $block
if ($lines.Count -ne 9) { throw 'Installer must write nine separate settings.' }
$expected = @($lines)
# Reproduce the exact old expression and verify recovery after HTTPS overrides.
$legacy = @(
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
$testDir = Join-Path ([IO.Path]::GetTempPath()) ('quremed-config-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $testDir | Out-Null
try {
    $path = Join-Path $testDir 'test.env'
    [IO.File]::WriteAllLines($path, $legacy)
    & (Join-Path $PSScriptRoot 'Repair-NoDockerConfig.ps1') -ConfigPath $path
    $actual = [IO.File]::ReadAllLines($path)
    if (Compare-Object $expected $actual) { throw 'Recovery did not preserve settings.' }
    $before = [IO.File]::ReadAllText($path)
    & (Join-Path $PSScriptRoot 'Repair-NoDockerConfig.ps1') -ConfigPath $path
    if ([IO.File]::ReadAllText($path) -cne $before) { throw 'Valid configuration changed.' }
    [IO.File]::WriteAllLines($path, @($legacy) + @('PUBLIC_URL=https://192.168.1.106','HOST=127.0.0.1','NODE_ENV=production'))
    & (Join-Path $PSScriptRoot 'Repair-NoDockerConfig.ps1') -ConfigPath $path
    $actual = [IO.File]::ReadAllLines($path)
    if ($actual -notcontains 'PUBLIC_URL=https://192.168.1.106' -or $actual -notcontains 'HOST=127.0.0.1' -or $actual -notcontains 'NODE_ENV=production') { throw 'HTTPS overrides lost.' }
    Write-Host 'PASS: configuration generation, recovery, password preservation and HTTPS overrides.'
} finally { Remove-Item -LiteralPath $testDir -Recurse -Force }
