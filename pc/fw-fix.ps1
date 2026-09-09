# Fix the firewall so the phone can actually reach the dashboard.
#
# ROOT CAUSE (2026-09-09): the "Hermes Remote dashboard" rule is scoped to
# RemoteAddress = LocalSubnet. Tailscale's CGNAT range (100.64.0.0/10) is NOT
# the local subnet, so every packet from the phone over the tailnet was dropped
# by Windows Firewall - silently, with no log the user would ever see. The
# dashboard was listening correctly the whole time.
#
# Fix: allow LocalSubnet (home Wi-Fi) AND 100.64.0.0/10 (tailnet). Both are
# private ranges; this does NOT expose the dashboard to the public internet.

$ErrorActionPreference = 'Stop'
$log = 'D:\Temp\fw-fix.log'
function L($m) { Add-Content -LiteralPath $log -Value $m; Write-Host $m }
Set-Content -LiteralPath $log -Value "=== firewall fix $(Get-Date) ==="

$name = 'Hermes Remote dashboard'
$allow = @('LocalSubnet', '100.64.0.0/10')

$rule = Get-NetFirewallRule -DisplayName $name -ErrorAction SilentlyContinue
if ($rule) {
    L "updating existing rule '$name'"
    Set-NetFirewallRule -DisplayName $name -RemoteAddress $allow -Profile Domain,Private -Enabled True
} else {
    L "creating rule '$name'"
    New-NetFirewallRule -DisplayName $name -Direction Inbound -Action Allow `
        -Protocol TCP -LocalPort 9119 -RemoteAddress $allow `
        -Profile Domain, Private | Out-Null
}

# Verify from the firewall's own view, not from our intent.
$r = Get-NetFirewallRule -DisplayName $name
$af = $r | Get-NetFirewallAddressFilter
$pf = $r | Get-NetFirewallPortFilter
L ""
L ("VERIFY port      : {0}" -f ($pf.LocalPort -join ','))
L ("VERIFY remoteAddr: {0}" -f ($af.RemoteAddress -join ','))
L ("VERIFY profile   : {0}  enabled={1}  action={2}" -f $r.Profile, $r.Enabled, $r.Action)

$ok = ($af.RemoteAddress -join ',') -match '100\.64\.0\.0'
L ""
L $(if ($ok) { "OK - tailnet range is now allowed" } else { "FAILED - tailnet still not allowed" })
L "done"
