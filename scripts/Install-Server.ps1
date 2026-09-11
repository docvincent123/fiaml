$ErrorActionPreference = 'Stop'
try {
    $root=Split-Path $PSScriptRoot -Parent
    if(Test-Path (Join-Path $root '.env.nodocker')) {
        & (Join-Path $PSScriptRoot 'Update-NoDocker.ps1')
    }
    & (Join-Path $PSScriptRoot 'Setup-NoDocker.ps1')
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    Read-Host 'Press Enter to close'
    exit 1
}
