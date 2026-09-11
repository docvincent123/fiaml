$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$certificate = Join-Path $projectRoot 'QureMed-Local-CA.crt'
$currentNoDockerCa = Join-Path $projectRoot '.local\caddy-data\pki\authorities\local\root.crt'

# If the no-Docker HTTPS service has regenerated its CA, always trust the current
# one instead of an older exported copy that may still be lying in the project root.
if (Test-Path $currentNoDockerCa) {
    Copy-Item $currentNoDockerCa $certificate -Force
}
if (-not (Test-Path $certificate)) { throw 'Run Setup-Local.ps1 / Enable-LanHttps.ps1 and start the HTTPS server, or copy QureMed-Local-CA.crt from the server first.' }

$cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2($certificate)
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
$isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)

# WebView2 validates against the Windows trust store of the user running RehaFlow.
Import-Certificate -FilePath $certificate -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
$stores = 'CurrentUser\\Root'
if ($isAdmin) {
    Import-Certificate -FilePath $certificate -CertStoreLocation Cert:\LocalMachine\Root | Out-Null
    $stores += ' + LocalMachine\\Root'
}

$trusted = Get-ChildItem Cert:\CurrentUser\Root | Where-Object Thumbprint -eq $cert.Thumbprint
if (-not $trusted) { throw 'Certificate import did not appear in the CurrentUser trusted root store.' }

Write-Host ('QureMed local CA is trusted. Thumbprint: ' + $cert.Thumbprint) -ForegroundColor Green
Write-Host ('Installed into: ' + $stores) -ForegroundColor Green
if (-not $isAdmin) {
    Write-Host 'If RehaFlow is started from another Windows account, run this script from that account too. Run PowerShell as Administrator if you also want LocalMachine trust.' -ForegroundColor Yellow
}
Write-Host 'Close every running RehaFlow / Edge WebView2 window and start RehaFlow again so the new trust is re-read.' -ForegroundColor Cyan
