$ErrorActionPreference = 'Stop'
$certificate = Join-Path (Split-Path $PSScriptRoot -Parent) 'QureMed-Local-CA.crt'
if (-not (Test-Path $certificate)) { throw 'Run Setup-Local.ps1 or copy the public CA certificate from the server first.' }
Import-Certificate -FilePath $certificate -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
Write-Host 'QureMed local CA trusted for the current Windows user.'
