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

Write-Host ''
Write-Host 'QureMed Industries - RehaFlow local server' -ForegroundColor Cyan
Write-Host ('Computer: http://localhost:' + $env:PORT) -ForegroundColor Green
Write-Host ('Phone:    ' + $env:PUBLIC_URL) -ForegroundColor Green
Write-Host 'Keep this window open. Press Ctrl+C to stop the server.' -ForegroundColor Yellow
Write-Host ''
& $npm.Source run start
if ($LASTEXITCODE -ne 0) { throw "Server stopped with code $LASTEXITCODE." }
