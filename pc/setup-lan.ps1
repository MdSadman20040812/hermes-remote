#requires -Version 5.1
<#
.SYNOPSIS
  One-time elevated setup so the phone can reach this PC over your Wi-Fi.

.DESCRIPTION
  Two things Windows does by default silently break LAN pairing, and both look
  identical from the phone ("can't find your PC"):

    1. A new Wi-Fi is classified PUBLIC. On a Public network Windows blocks
       incoming connections wholesale, before any firewall rule is consulted.
    2. Nothing allows inbound TCP 9119.

  This script fixes both, narrowly: the network you name is set to Private, and
  the firewall opening is scoped to Private/Domain profiles AND the local
  subnet, so it does not follow the machine onto a cafe network.

  Run once, per home network. Requires admin.

.EXAMPLE
  # right-click PowerShell > Run as administrator, then:
  powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\setup-lan.ps1
#>
param(
    [int]$Port = 9119,
    [switch]$Revert
)

$ErrorActionPreference = 'Stop'
$FwRuleName = 'Hermes Remote dashboard'

function Write-Ok($m)   { Write-Host "  $m" -ForegroundColor Green }
function Write-Bad($m)  { Write-Host "  $m" -ForegroundColor Red }
function Write-Step($m) { Write-Host "  $m" -ForegroundColor Cyan }
function Write-Warn($m) { Write-Host "  $m" -ForegroundColor Yellow }

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
if (-not (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Bad "This needs administrator rights."
    Write-Host "  Right-click PowerShell, 'Run as administrator', then run this again."
    exit 1
}

Write-Host ""
Write-Host "Hermes Remote - LAN setup" -ForegroundColor White
Write-Host ""

if ($Revert) {
    Remove-NetFirewallRule -DisplayName $FwRuleName -ErrorAction SilentlyContinue
    Write-Ok "Removed firewall rule '$FwRuleName'. Network category left unchanged."
    exit 0
}

# ------------------------------------------------- 1. network category

# Only the interface that actually carries LAN traffic matters. Tailscale and
# virtual adapters are already Private and are not the route to the phone.
$profiles = Get-NetConnectionProfile | Where-Object {
    $_.IPv4Connectivity -ne 'Disconnected' -and $_.InterfaceAlias -notmatch 'Tailscale|Virtual|VMware|Loopback'
}

if (-not $profiles) {
    Write-Bad "No connected network found. Join your Wi-Fi first."
    exit 1
}

foreach ($p in $profiles) {
    if ($p.NetworkCategory -eq 'Public') {
        Set-NetConnectionProfile -InterfaceIndex $p.InterfaceIndex -NetworkCategory Private
        Write-Ok "'$($p.Name)' switched Public -> Private (incoming connections now possible)"
    } else {
        Write-Step "'$($p.Name)' is already $($p.NetworkCategory)"
    }
}

# ------------------------------------------------- 2. firewall opening

Remove-NetFirewallRule -DisplayName $FwRuleName -ErrorAction SilentlyContinue
New-NetFirewallRule -DisplayName $FwRuleName `
    -Description 'Lets the Hermes Remote phone app reach the dashboard on this LAN.' `
    -Direction Inbound -Action Allow -Protocol TCP -LocalPort $Port `
    -Profile Private,Domain -RemoteAddress LocalSubnet -Enabled True | Out-Null
Write-Ok "Firewall: inbound TCP $Port allowed from the local subnet, private networks only"

# ------------------------------------------------- 3. report the address

$lan = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)' } |
    Where-Object { (Get-NetAdapter -InterfaceIndex $_.InterfaceIndex -ErrorAction SilentlyContinue).InterfaceDescription -notmatch 'Hyper-V|Virtual|VMware|Tailscale|Loopback' } |
    Select-Object -First 1).IPAddress

Write-Host ""
if ($lan) {
    Write-Ok "This PC is http://${lan}:$Port on your Wi-Fi"
    Write-Host "  The phone app finds this automatically with 'Find my PC'."
} else {
    Write-Warn "Could not determine a LAN address - is Wi-Fi connected?"
}
Write-Host ""
Write-Host "  Next: powershell -ExecutionPolicy Bypass -File `"$PSScriptRoot\hermes-remote.ps1`""
Write-Host ""
