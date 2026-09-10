param([string]$ConfigPath = (Join-Path (Split-Path $PSScriptRoot -Parent) '.env.nodocker'))
$ErrorActionPreference = 'Stop'
$lines = [IO.File]::ReadAllLines($ConfigPath)
$settings = [ordered]@{}
$changed = $false
foreach ($line in $lines) {
    if ($line.StartsWith('DATABASE_URL=') -and $line.Contains(' JWT_SECRET=')) {
        $parts = @($line -split ' (?=(?:JWT_SECRET|ADMIN_LOGIN|ADMIN_PASSWORD|PUBLIC_URL|ALLOWED_ORIGINS|HOST|PORT|NODE_ENV)=)')
        if ($parts.Count -ne 9) { throw 'Unexpected configuration format; original file was not changed.' }
        $changed = $true
    } else { $parts = @($line) }
    foreach ($part in $parts) {
        if ([string]::IsNullOrWhiteSpace($part) -or $part.TrimStart().StartsWith('#')) { continue }
        $separator = $part.IndexOf('=')
        if ($separator -lt 1) { throw 'Invalid configuration line; original file was not changed.' }
        $settings[$part.Substring(0,$separator)] = $part.Substring($separator+1)
    }
}
if (-not $changed) { Write-Host 'No joined configuration lines found.'; return }
if ($settings['DATABASE_URL'] -notmatch '^postgresql://quremed:[a-f0-9]{48}@127\.0\.0\.1:5432/quremed$' -or
    $settings['JWT_SECRET'] -notmatch '^[a-f0-9]{64}$' -or
    $settings['ADMIN_LOGIN'] -ne 'admin' -or
    $settings['ADMIN_PASSWORD'].Length -lt 12) {
    throw 'Configuration validation failed; original file was not changed.'
}
$backup = $ConfigPath + '.backup-' + [Guid]::NewGuid().ToString('N')
Copy-Item -LiteralPath $ConfigPath -Destination $backup
# Preserve the restricted ACL on the backup, which contains credentials.
Set-Acl -LiteralPath $backup -AclObject (Get-Acl -LiteralPath $ConfigPath)
$outputLines = @($settings.GetEnumerator() | ForEach-Object { $_.Key + '=' + $_.Value })
[IO.File]::WriteAllLines($ConfigPath, $outputLines, (New-Object Text.UTF8Encoding($false)))
Write-Host 'Configuration repaired. Existing passwords and JWT key preserved.' -ForegroundColor Green
