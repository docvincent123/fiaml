# Keep selection separate from configuration so it can be tested without changing Windows.
function Select-QureMedServerAddress {
    param([object[]]$Candidates)
    $eligible = @($Candidates | Where-Object {
        $_.IPAddress -and $_.IPAddress -notmatch '^(127\.|169\.254\.|0\.)'
    } | Sort-Object @{Expression={ if ($_.HasGateway) { 0 } else { 1 } }}, Metric, InterfaceIndex, IPAddress)
    if (-not $eligible.Count) { throw 'No connected physical IPv4 network found. Connect this PC to the centre Wi-Fi or Ethernet and try again.' }
    return [string]$eligible[0].IPAddress
}
function Get-QureMedServerIp {
    $candidates = @()
    $adapters = @(Get-NetAdapter -Physical -ErrorAction Stop | Where-Object { $_.Status -eq 'Up' })
    foreach ($adapter in $adapters) {
        $index = $adapter.ifIndex
        $interface = Get-NetIPInterface -InterfaceIndex $index -AddressFamily IPv4 -ErrorAction SilentlyContinue | Select-Object -First 1
        $routes = @(Get-NetRoute -InterfaceIndex $index -AddressFamily IPv4 -DestinationPrefix '0.0.0.0/0' -ErrorAction SilentlyContinue | Sort-Object RouteMetric)
        $metric = if ($routes.Count) { [int]$routes[0].RouteMetric + [int]$interface.InterfaceMetric } else { [int]$interface.InterfaceMetric }
        foreach ($address in @(Get-NetIPAddress -InterfaceIndex $index -AddressFamily IPv4 -ErrorAction SilentlyContinue | Where-Object { $_.AddressState -eq 'Preferred' -and -not $_.SkipAsSource })) {
            $candidates += [pscustomobject]@{ IPAddress=$address.IPAddress; InterfaceIndex=$index; HasGateway=($routes.Count -gt 0); Metric=$metric }
        }
    }
    $selected = Select-QureMedServerAddress -Candidates $candidates
    Write-Host "Detected this computer's LAN address: $selected" -ForegroundColor Cyan
    return $selected
}
