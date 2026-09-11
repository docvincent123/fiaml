$ErrorActionPreference = 'Stop'
try {
    $entered=Read-Host 'Server PC HTTPS address (example https://192.168.1.106)'
    if($entered -notmatch '^https://'){$entered='https://'+$entered}
    $uri=$null
    if(-not [Uri]::TryCreate($entered,[UriKind]::Absolute,[ref]$uri) -or $uri.Scheme -ne 'https' -or $uri.UserInfo -or $uri.AbsolutePath -ne '/' -or $uri.Query -or $uri.Fragment){throw 'Enter the server PC address, not the router gateway.'}
    $certificate=Read-Host 'Path to QureMed-Local-CA.crt copied from the server PC (blank to install later)'
    if($certificate){
        $cert=New-Object Security.Cryptography.X509Certificates.X509Certificate2($certificate.Trim('"'))
        Write-Host ('Certificate: '+$cert.Subject)
        Write-Host ('Thumbprint: '+$cert.Thumbprint)
        if((Read-Host 'Trust this centre certificate for your Windows account? Type YES') -ceq 'YES'){
            Import-Certificate -FilePath $certificate.Trim('"') -CertStoreLocation Cert:\CurrentUser\Root | Out-Null
        }
    }
    $folder=Join-Path $env:LOCALAPPDATA 'QureMed'
    New-Item -ItemType Directory -Force $folder | Out-Null
    [IO.File]::WriteAllText((Join-Path $folder 'server.txt'),$uri.GetLeftPart([UriPartial]::Authority))
    Write-Host 'Client configured. Patient data stays on the server PC.' -ForegroundColor Green
} catch {Write-Host $_.Exception.Message -ForegroundColor Red}
Read-Host 'Press Enter to close'
