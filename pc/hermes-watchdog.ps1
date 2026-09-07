#requires -Version 5.1
<#
.SYNOPSIS
  Keep the Hermes dashboard alive: check, and start it if it is not answering.

.DESCRIPTION
  Designed to be run repeatedly by Task Scheduler (every 2 minutes) rather than
  to loop forever itself. A supervisor that loops is a supervisor that can die
  silently; a scheduled check is stateless, so if THIS script crashes the next
  run two minutes later simply fixes things. Nothing to restart, nothing to
  monitor, no PID files to go stale.

  Idempotent by construction: if `/api/status` answers, it exits immediately and
  touches nothing. Only a dead endpoint causes a start.

  It re-resolves the LAN IP on every run, so a DHCP lease change moves the bind
  automatically instead of leaving the dashboard listening on an address the PC
  no longer holds.

  Writes one line per state change to pc\watchdog.log (a line every 2 minutes
  would be noise, so a healthy check is silent).

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\hermes-watchdog.ps1
#>
param(
    [int]$Port = 9119,
    [switch]$Verbose
)

$ErrorActionPreference = 'Stop'

$Root       = Split-Path -Parent $PSCommandPath
$HermesHome = 'D:\.hermes'
$Python     = Join-Path $HermesHome 'hermes-agent\venv\Scripts\python.exe'
$AgentDir   = Join-Path $HermesHome 'hermes-agent'
$LogFile    = Join-Path $Root 'watchdog.log'
$StateFile  = Join-Path $Root '.watchdog-state'

function Write-Log([string]$msg) {
    $line = "{0}  {1}" -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
    Add-Content -LiteralPath $LogFile -Value $line -ErrorAction SilentlyContinue
    if ($Verbose) { Write-Host $line }
    # Keep the log from growing without bound (checked cheaply, rarely trimmed).
    if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 512KB) {
        $keep = Get-Content -LiteralPath $LogFile -Tail 500
        Set-Content -LiteralPath $LogFile -Value $keep
    }
}

# Only log a transition, not every healthy poll.
function Set-State([string]$state) {
    $previous = if (Test-Path $StateFile) { (Get-Content -LiteralPath $StateFile -Raw).Trim() } else { '' }
    if ($previous -ne $state) {
        Set-Content -LiteralPath $StateFile -Value $state
        return $true
    }
    return $false
}

function Get-LanIp {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -match '^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)' -and
            $_.IPAddress -notmatch '^169\.254\.'
        }
    $best = $null; $bestMetric = [int]::MaxValue
    foreach ($addr in $candidates) {
        $iface = Get-NetIPInterface -InterfaceIndex $addr.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue
        if (-not $iface -or $iface.ConnectionState -ne 'Connected') { continue }
        $adapter = Get-NetAdapter -InterfaceIndex $addr.InterfaceIndex -ErrorAction SilentlyContinue
        if (-not $adapter -or $adapter.Status -ne 'Up') { continue }
        if ($adapter.InterfaceDescription -match 'Hyper-V|Virtual|VMware|VirtualBox|Loopback|TAP-|Tailscale|WireGuard') { continue }
        if ([int]$iface.InterfaceMetric -lt $bestMetric) {
            $bestMetric = [int]$iface.InterfaceMetric; $best = $addr.IPAddress
        }
    }
    return $best
}

function Test-Dashboard([string]$bindIp) {
    # NB: not $host - that is a PowerShell automatic variable and assigning to
    # it in a foreach throws at runtime (a parse check will not catch it).
    foreach ($candidate in @($bindIp, '127.0.0.1')) {
        if (-not $candidate) { continue }
        try {
            $r = Invoke-RestMethod -Uri "http://${candidate}:$Port/api/status" -TimeoutSec 4
            if ($r.version) { return $candidate }
        } catch { }
    }
    return $null
}

# ---------------------------------------------------------------------- run

$lan = Get-LanIp
if (-not $lan) {
    if (Set-State 'no-network') { Write-Log "No LAN address; nothing to bind. Waiting for a network." }
    exit 0
}

$alive = Test-Dashboard $lan
if ($alive) {
    if (Set-State "up:$lan") { Write-Log "Dashboard healthy on ${lan}:$Port" }
    exit 0
}

# Not answering. If something is squatting the port, killing it is the caller's
# call, not ours - report and let the next cycle retry rather than fighting it.
$busy = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($busy) {
    Write-Log "Port $Port is held by PID $($busy[0].OwningProcess) but not answering; leaving it alone."
    Set-State 'port-busy' | Out-Null
    exit 0
}

Set-State 'starting' | Out-Null
Write-Log "Dashboard not answering. Starting on ${lan}:$Port ..."

$password = $null
$pwFile = Join-Path $Root '.dashboard-password'
if (Test-Path $pwFile) { $password = (Get-Content -LiteralPath $pwFile -Raw).Trim() }

$stdout = Join-Path $env:TEMP 'hermes-dashboard.out.log'
$stderr = Join-Path $env:TEMP 'hermes-dashboard.err.log'

<#
  HERMES_HOME must be set EXPLICITLY, not inherited.

  It is defined as a per-USER environment variable, so a task running as SYSTEM
  does not see it. Hermes then resolves a different home, finds no
  dashboard.basic_auth there, and refuses to bind off-loopback with
  "Configure an auth provider before exposing the dashboard" - which reads like
  a config problem and is actually an environment problem. Setting it here
  makes the watchdog behave identically under SYSTEM, under your login, and
  from an interactive shell.
#>
$env:HERMES_HOME = $HermesHome

$proc = Start-Process -FilePath $Python -PassThru -WindowStyle Hidden `
    -ArgumentList @('-m', 'hermes_cli.main', 'dashboard',
                    '--host', $lan, '--port', "$Port", '--no-open', '--skip-build') `
    -WorkingDirectory $AgentDir `
    -RedirectStandardOutput $stdout -RedirectStandardError $stderr

for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 750
    if ($proc.HasExited) { break }
    if (Test-Dashboard $lan) {
        Write-Log "Dashboard back up on ${lan}:$Port (pid $($proc.Id))"
        Set-State "up:$lan" | Out-Null
        exit 0
    }
}

$tail = if (Test-Path $stderr) { (Get-Content -LiteralPath $stderr -Tail 5 -ErrorAction SilentlyContinue) -join ' | ' } else { '' }
Write-Log "Dashboard failed to come up. stderr: $tail"
Set-State 'failed' | Out-Null
exit 1
