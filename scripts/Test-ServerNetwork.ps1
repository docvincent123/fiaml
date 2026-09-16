$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Server-Network.ps1')
function Candidate($ip,$gateway,$metric,$index) { return [pscustomobject]@{IPAddress=$ip;HasGateway=$gateway;Metric=$metric;InterfaceIndex=$index} }
$items = @((Candidate '192.168.1.106' $false 1 2),(Candidate '10.0.0.24' $true 40 5),(Candidate '192.168.0.20' $true 10 7),(Candidate '169.254.1.2' $true 0 1))
if ((Select-QureMedServerAddress $items) -ne '192.168.0.20') { throw 'Default route priority failed' }
if ((Select-QureMedServerAddress @((Candidate '10.20.0.5' $false 50 2))) -ne '10.20.0.5') { throw 'LAN without internet gateway failed' }
$failed=$false
try { Select-QureMedServerAddress @((Candidate '127.0.0.1' $true 0 1)) | Out-Null } catch { $failed=$true }
if (-not $failed) { throw 'Loopback must not be selected' }
# Test acquisition: only physical, up adapters and their preferred IPv4 addresses.
function Get-NetAdapter { param([switch]$Physical) if (-not $Physical) { throw 'Physical adapters required' }; [pscustomobject]@{Status='Up';ifIndex=5}; [pscustomobject]@{Status='Disconnected';ifIndex=8} }
function Get-NetIPInterface { param($InterfaceIndex,$AddressFamily) [pscustomobject]@{InterfaceMetric=20} }
function Get-NetRoute { param($InterfaceIndex,$AddressFamily,$DestinationPrefix) [pscustomobject]@{RouteMetric=0} }
function Get-NetIPAddress { param($InterfaceIndex,$AddressFamily) if ($InterfaceIndex -ne 5) { throw 'Disconnected adapter selected' };[pscustomobject]@{IPAddress='10.44.0.8';AddressState='Preferred';SkipAsSource=$false};[pscustomobject]@{IPAddress='10.44.0.9';AddressState='Tentative';SkipAsSource=$false} }
if ((Get-QureMedServerIp) -ne '10.44.0.8') { throw 'Connected adapter detection failed' }
Write-Host 'Server network selection checks passed.' -ForegroundColor Green
