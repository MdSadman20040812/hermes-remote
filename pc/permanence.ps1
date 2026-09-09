# Verify EVERY piece of the phone link is permanent (survives reboot/crash).
# A link that works today but not after a reboot is not "bound permanently".
$log = 'D:\Temp\permanence.log'
function L($m) { Add-Content -LiteralPath $log -Value $m; Write-Host $m }
Set-Content -LiteralPath $log -Value "=== permanence audit $(Get-Date) ==="

$fail = 0

# 1. Tailscale service must auto-start
$svc = Get-Service Tailscale -ErrorAction SilentlyContinue
if ($svc -and $svc.StartType -eq 'Automatic' -and $svc.Status -eq 'Running') {
    L "PASS  Tailscale service: Running / Automatic"
} else { L "FAIL  Tailscale service: $($svc.Status) / $($svc.StartType)"; $fail++ }

# 2. Dashboard watchdog task (revives the server after a crash or cold boot)
$t = Get-ScheduledTask -TaskName 'Hermes Always On' -ErrorAction SilentlyContinue
if ($t) {
    $i = Get-ScheduledTaskInfo -TaskName 'Hermes Always On'
    $trig = ($t.Triggers | ForEach-Object { $_.CimClass.CimClassName }) -join ','
    L "PASS  'Hermes Always On': $($t.State), lastResult=$($i.LastTaskResult), triggers=$trig"
    L "      principal=$($t.Principal.UserId) runlevel=$($t.Principal.RunLevel)"
} else { L "FAIL  'Hermes Always On' task missing - dashboard will not revive"; $fail++ }

# 3. Pairing-QR page task
$q = Get-ScheduledTask -TaskName 'Hermes Pairing Page' -ErrorAction SilentlyContinue
if ($q) { L "PASS  'Hermes Pairing Page': $($q.State)" }
else { L "WARN  pairing-page task missing (QR page won't auto-start)" }

# 4. Firewall must allow BOTH the LAN and the tailnet
$r = Get-NetFirewallRule -DisplayName 'Hermes Remote dashboard' -ErrorAction SilentlyContinue
if ($r) {
    $addr = ($r | Get-NetFirewallAddressFilter).RemoteAddress -join ','
    $port = ($r | Get-NetFirewallPortFilter).LocalPort -join ','
    if ($addr -match '100\.64\.0\.0') { L "PASS  firewall: port=$port remote=$addr" }
    else { L "FAIL  firewall missing tailnet range: $addr"; $fail++ }
} else { L "FAIL  firewall rule missing"; $fail++ }

# 5. Dashboard must bind 0.0.0.0, not a single interface
$listen = Get-NetTCPConnection -LocalPort 9119 -State Listen -ErrorAction SilentlyContinue
if ($listen) {
    $addrs = ($listen | ForEach-Object { $_.LocalAddress }) -join ','
    if ($addrs -match '0\.0\.0\.0') { L "PASS  dashboard bound $addrs (all interfaces)" }
    else { L "FAIL  dashboard bound only $addrs"; $fail++ }
} else { L "FAIL  nothing listening on 9119"; $fail++ }

# 6. Live reachability on every path the phone may use
foreach ($ip in @('127.0.0.1','192.168.1.102','100.88.18.123')) {
    try {
        $x = Invoke-RestMethod -Uri "http://${ip}:9119/api/status" -TimeoutSec 6
        L "PASS  reachable $ip (v$($x.version))"
    } catch { L "FAIL  unreachable $ip"; $fail++ }
}

L ""
L $(if ($fail -eq 0) { "ALL CHECKS PASSED - link is permanent" } else { "$fail CHECK(S) FAILED" })
