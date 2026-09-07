#requires -Version 5.1
<#
.SYNOPSIS
  Hermes Remote - PC-side launcher (LAN-first).

  1. Resolves the bind address: your Wi-Fi/LAN IPv4 by default
  2. Opens the Windows Firewall for that port on PRIVATE networks only
  3. Verifies the auth posture that bind actually requires
  4. Starts `hermes dashboard` bound to that address
  5. Renders the pairing QR the phone scans

.DESCRIPTION
  Design note - why LAN and not a VPN:
  the phone and the PC sit on the same home router, so the router already is
  the private network. A tailnet adds a second identity system and a second
  thing that can be "not logged in" for no reachability the LAN does not
  already give. Tailscale is still supported (-UseTailscale) for reaching the
  PC from outside the house, but it is no longer the default and never a
  requirement.

  A non-loopback bind is ALWAYS gated: `web_server.py` refuses to bind
  off-loopback unless an auth provider is registered, and it rejects `?token=`
  in that mode. So the QR carries the basic-auth credential; the session token
  only means anything on a loopback bind.

  Firewall: a fresh Windows install silently drops inbound 9119. That failure
  looks exactly like "the app can't find my PC", so the rule is created here
  rather than left as a documented manual step. It is scoped to the Private
  profile and to the local subnet - it does not expose the dashboard to a cafe
  Wi-Fi that Windows has classified as Public.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File D:\HermesMobile\pc\hermes-remote.ps1
.EXAMPLE
  # reachable from outside the house, over the tailnet instead
  powershell -ExecutionPolicy Bypass -File .\hermes-remote.ps1 -UseTailscale
#>
param(
    [switch]$Register,
    [switch]$Stop,
    [switch]$Status,
    [switch]$UseTailscale,
    [switch]$NoFirewall,
    [int]$Port = 9119,
    [string]$Username,
    [string]$Password,
    [string]$BindHost
)

$ErrorActionPreference = 'Stop'

$HermesHome   = 'D:\.hermes'
$ConfigFile   = Join-Path $HermesHome 'config.yaml'
$EnvFile      = Join-Path $HermesHome '.env'
$Python       = Join-Path $HermesHome 'hermes-agent\venv\Scripts\python.exe'
$AgentDir     = Join-Path $HermesHome 'hermes-agent'
$RenderQr     = Join-Path $PSScriptRoot 'render_qr.py'
$PasswordFile = Join-Path $PSScriptRoot '.dashboard-password'
$Tailscale    = Join-Path $env:ProgramFiles 'Tailscale\tailscale.exe'
$FwRuleName   = 'Hermes Remote dashboard'

function Write-Step($msg) { Write-Host "  $msg" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  $msg" -ForegroundColor Green }
function Write-Warn($msg) { Write-Host "  $msg" -ForegroundColor Yellow }
function Write-Bad($msg)  { Write-Host "  $msg" -ForegroundColor Red }

function Get-Sha256Hex([string]$text) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($text)
        ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join ''
    } finally {
        $sha.Dispose()
    }
}

function New-UrlSafeToken([int]$bytes = 36) {
    $buf = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buf)
    [Convert]::ToBase64String($buf).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

function Get-OrCreateSessionToken {
    if (Test-Path $EnvFile) {
        $line = Select-String -Path $EnvFile -Pattern '^HERMES_DASHBOARD_SESSION_TOKEN=(.+)$' -ErrorAction SilentlyContinue
        if ($line) { return $line.Matches[0].Groups[1].Value.Trim() }
    }
    $token = New-UrlSafeToken
    Add-Content -Path $EnvFile -Value ""
    Add-Content -Path $EnvFile -Value "# Stable dashboard session token - added by hermes-remote.ps1"
    Add-Content -Path $EnvFile -Value "HERMES_DASHBOARD_SESSION_TOKEN=$token"
    Write-Ok "Generated a stable dashboard session token in $EnvFile"
    return $token
}

# Pull dashboard.basic_auth.<key> out of config.yaml without a YAML parser:
# find the basic_auth block, then the first matching key inside it.
function Get-BasicAuthValue([string]$key) {
    if (-not (Test-Path $ConfigFile)) { return $null }
    $lines = Get-Content -LiteralPath $ConfigFile
    $inDashboard = $false
    $inBasic = $false
    foreach ($line in $lines) {
        if ($line -match '^dashboard:\s*$')       { $inDashboard = $true;  $inBasic = $false; continue }
        if ($line -match '^\S')                   { $inDashboard = $false; $inBasic = $false; continue }
        if (-not $inDashboard)                    { continue }
        if ($line -match '^\s{2}basic_auth:\s*$') { $inBasic = $true; continue }
        if ($line -match '^\s{2}\S')              { $inBasic = $false; continue }
        if ($inBasic -and $line -match ("^\s{4}" + [regex]::Escape($key) + ":\s*(.+?)\s*$")) {
            return $Matches[1].Trim().Trim("'").Trim('"')
        }
    }
    return $null
}

function Get-TailscaleIp {
    if (-not (Test-Path $Tailscale)) { return $null }
    $ip = & $Tailscale ip -4 2>$null | Select-Object -First 1
    if ([string]::IsNullOrWhiteSpace($ip)) { return $null }
    $ip = $ip.Trim()
    if ($ip -notmatch '^\d{1,3}(\.\d{1,3}){3}$') { return $null }
    return $ip
}

<#
  The LAN IPv4 the phone can actually route to.

  Picked by lowest interface metric among UP, non-loopback, non-virtual
  adapters holding a private address - i.e. the interface Windows itself would
  use to reach the router. Naively taking the first IPv4 lands on a Hyper-V,
  WSL, or VPN adapter (172.x / 192.168.56.x), and a QR carrying an address that
  only exists inside the PC is a pairing that fails with no visible cause.
#>
function Get-LanIp {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notmatch '^(127\.|169\.254\.|100\.(6[4-9]|[7-9]\d|1[01]\d|12[0-7])\.)' -and
            $_.PrefixOrigin -ne 'WellKnown' -and
            ($_.IPAddress -match '^192\.168\.' -or $_.IPAddress -match '^10\.' -or
             $_.IPAddress -match '^172\.(1[6-9]|2\d|3[01])\.')
        }
    if (-not $candidates) { return $null }

    $best = $null
    $bestMetric = [int]::MaxValue
    foreach ($addr in $candidates) {
        $iface = Get-NetIPInterface -InterfaceIndex $addr.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue
        if (-not $iface -or $iface.ConnectionState -ne 'Connected') { continue }
        $adapter = Get-NetAdapter -InterfaceIndex $addr.InterfaceIndex -ErrorAction SilentlyContinue
        if (-not $adapter -or $adapter.Status -ne 'Up') { continue }
        # Virtual switches and loopback-ish adapters are never the route to the phone.
        if ($adapter.InterfaceDescription -match 'Hyper-V|Virtual|VMware|VirtualBox|Loopback|TAP-|Tailscale|WireGuard') { continue }
        $metric = [int]$iface.InterfaceMetric
        if ($metric -lt $bestMetric) { $bestMetric = $metric; $best = $addr.IPAddress }
    }
    return $best
}

function Test-Admin {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
}

<#
  Allow inbound TCP $Port from the local subnet on Private/Domain profiles.

  RemoteAddress=LocalSubnet plus Profile=Private,Domain is the narrowest rule
  that still lets the phone in: it does not apply at all on a network Windows
  has classified Public, so taking the laptop to a cafe does not carry the
  opening along with it.
#>
function Set-FirewallRule([int]$port) {
    $existing = Get-NetFirewallRule -DisplayName $FwRuleName -ErrorAction SilentlyContinue
    if ($existing) {
        $filter = $existing | Get-NetFirewallPortFilter
        if ($filter.LocalPort -eq "$port") {
            Write-Ok "Firewall: '$FwRuleName' already allows TCP $port on private networks"
            return $true
        }
        if (-not (Test-Admin)) { return $false }
        Remove-NetFirewallRule -DisplayName $FwRuleName -ErrorAction SilentlyContinue
    }
    if (-not (Test-Admin)) { return $false }
    New-NetFirewallRule -DisplayName $FwRuleName `
        -Description 'Lets the Hermes Remote phone app reach the dashboard on this LAN.' `
        -Direction Inbound -Action Allow -Protocol TCP -LocalPort $port `
        -Profile Private,Domain -RemoteAddress LocalSubnet -Enabled True | Out-Null
    Write-Ok "Firewall: allowed inbound TCP $port from the local subnet (private networks only)"
    return $true
}

# The network the PC is on must be Private, or the rule above never applies.
function Test-PrivateProfile {
    $profiles = Get-NetConnectionProfile -ErrorAction SilentlyContinue |
        Where-Object { $_.IPv4Connectivity -ne 'Disconnected' }
    if (-not $profiles) { return $true }
    $public = $profiles | Where-Object { $_.NetworkCategory -eq 'Public' }
    if ($public) {
        Write-Warn "Network '$($public[0].Name)' is set to Public - Windows blocks incoming"
        Write-Warn "connections there. Settings > Network > Properties > Private network."
        return $false
    }
    return $true
}

# ---------------------------------------------------------------- subcommands

if ($Stop) {
    Push-Location $AgentDir
    try { & $Python -m hermes_cli.main dashboard --stop } finally { Pop-Location }
    exit 0
}

if ($Status) {
    Push-Location $AgentDir
    try { & $Python -m hermes_cli.main dashboard --status } finally { Pop-Location }
    exit 0
}

# --------------------------------------------------------------------- bind

Write-Host ""
Write-Host "Hermes Remote - starting dashboard" -ForegroundColor White
Write-Host ""

if ($BindHost) {
    $bind = $BindHost
    Write-Step "Bind: $bind (explicit)"
} elseif ($UseTailscale) {
    $bind = Get-TailscaleIp
    if ($bind) { Write-Step "Bind: $bind (tailnet)" }
    else { Write-Bad "Tailscale is not installed or not logged in." }
} else {
    $bind = Get-LanIp
    if ($bind) { Write-Step "Bind: $bind (this PC on your Wi-Fi/LAN)" }
}

if (-not $bind) {
    $bind = '127.0.0.1'
    Write-Bad "No LAN address found - is this PC connected to Wi-Fi or Ethernet?"
    Write-Bad "Binding LOOPBACK; your phone cannot reach this."
    Write-Host ""
}

if ($bind -eq '0.0.0.0') { throw 'Refusing to bind 0.0.0.0 - unsandboxed execution must not face every network.' }

$isLoopback = ($bind -eq '127.0.0.1' -or $bind -eq 'localhost' -or $bind -eq '::1')

# ------------------------------------------------------------------ firewall

if (-not $isLoopback -and -not $NoFirewall) {
    Test-PrivateProfile | Out-Null
    if (-not (Set-FirewallRule $Port)) {
        Write-Warn "Firewall rule needs admin rights and was not created."
        Write-Warn "If the phone cannot see this PC, run once in an elevated PowerShell:"
        Write-Host "    New-NetFirewallRule -DisplayName '$FwRuleName' -Direction Inbound ``" -ForegroundColor DarkGray
        Write-Host "      -Action Allow -Protocol TCP -LocalPort $Port -Profile Private,Domain ``" -ForegroundColor DarkGray
        Write-Host "      -RemoteAddress LocalSubnet" -ForegroundColor DarkGray
        Write-Host ""
    }
}

# ---------------------------------------------------- auth posture preflight

$cfgUser = Get-BasicAuthValue 'username'
$cfgHash = Get-BasicAuthValue 'password_hash'
$cfgPass = Get-BasicAuthValue 'password'

if (-not $Username) { $Username = $cfgUser }
if (-not $Password) {
    if (Test-Path $PasswordFile) { $Password = (Get-Content -LiteralPath $PasswordFile -Raw).Trim() }
    elseif ($cfgPass)            { $Password = $cfgPass }
}

if (-not $isLoopback) {
    # The dashboard REFUSES to start on a public bind with no auth provider.
    # Catch that here with a readable message instead of a SystemExit traceback.
    if (-not $Username -or -not ($cfgHash -or $cfgPass)) {
        Write-Bad "Binding $bind engages the auth gate, but dashboard.basic_auth is not configured."
        Write-Host ""
        Write-Host "  Fix it with:" -ForegroundColor Yellow
        Write-Host "    hermes config set dashboard.basic_auth.username <name>"
        Write-Host "    # then hash a password and set dashboard.basic_auth.password_hash"
        Write-Host ""
        exit 1
    }
    if (-not $Password) {
        $secure = Read-Host "Dashboard password for '$Username'" -AsSecureString
        $Password = [Runtime.InteropServices.Marshal]::PtrToStringAuto(
            [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure))
    }
    Write-Ok "Auth: gated (basic) as '$Username'"
} else {
    Write-Step "Auth: loopback (session token)"
}

$token = Get-OrCreateSessionToken
$env:HERMES_DASHBOARD_SESSION_TOKEN = $token

# -------------------------------------------------------------------- launch

# Already up on this address? Reuse it rather than starting a second server
# that will die on a port conflict and look like a failure.
$status = $null
try { $status = Invoke-RestMethod -Uri "http://${bind}:$Port/api/status" -TimeoutSec 2 } catch { }

if ($status) {
    Write-Ok "Dashboard already running on http://${bind}:$Port"
} else {
    Write-Step "Starting dashboard on http://${bind}:$Port ..."

    $stdout = Join-Path $env:TEMP 'hermes-dashboard.out.log'
    $stderr = Join-Path $env:TEMP 'hermes-dashboard.err.log'

    $proc = Start-Process -FilePath $Python -PassThru -WindowStyle Hidden `
        -ArgumentList @('-m', 'hermes_cli.main', 'dashboard',
                        '--host', $bind, '--port', "$Port", '--no-open', '--skip-build') `
        -WorkingDirectory $AgentDir `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr

    for ($i = 0; $i -lt 30; $i++) {
        Start-Sleep -Milliseconds 700
        if ($proc.HasExited) { break }
        try {
            $status = Invoke-RestMethod -Uri "http://${bind}:$Port/api/status" -TimeoutSec 3
            break
        } catch { }
    }

    if ($proc.HasExited -or -not $status) {
        Write-Bad "Dashboard did not come up."
        if (Test-Path $stderr) {
            $tail = Get-Content -LiteralPath $stderr -Tail 25 -ErrorAction SilentlyContinue
            if ($tail) { Write-Host ""; $tail | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray } }
        }
        exit 1
    }
}

Write-Ok ("Dashboard up: Hermes v{0}, gateway={1}, auth_required={2}" -f `
    $status.version, $status.gateway_state, $status.auth_required)

# The bind and the auth mode must agree, or the phone will fail at connect
# time with a confusing error instead of here with a clear one.
if ($status.auth_required -and -not $Password) {
    Write-Bad "Server reports auth_required=true but no password is available for the QR."
    exit 1
}

# ----------------------------------------------------------------------- QR

if ($status.auth_required) {
    $payload = [ordered]@{
        v = 1; name = $env:COMPUTERNAME; host = $bind; port = $Port
        auth = 'gated'; provider = 'basic'
        username = $Username; password = $Password
        fingerprint = (Get-Sha256Hex "$Username`:$Password")
    }
} else {
    $payload = [ordered]@{
        v = 1; name = $env:COMPUTERNAME; host = $bind; port = $Port
        auth = 'token'; token = $token
        fingerprint = (Get-Sha256Hex $token)
    }
}

$json = ($payload | ConvertTo-Json -Compress)

Write-Host ""
# Piped on stdin - render_qr.py already supports it, and this keeps a payload
# full of braces and quotes out of PowerShell's native-argument handling.
$json | & $Python $RenderQr
Write-Host ""
Write-Ok "Scan the QR with Hermes Remote (phone on the same Wi-Fi)."
Write-Host "  Address:  http://${bind}:$Port"
Write-Host "  Stop later with: powershell -File `"$PSCommandPath`" -Stop"
Write-Host ""

if ($Register) {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    Register-ScheduledTask -TaskName 'Hermes Remote Dashboard' -Action $action -Trigger $trigger `
        -Description 'Starts the Hermes dashboard on the LAN address and prints the pairing QR.' -Force | Out-Null
    Write-Ok 'Scheduled task "Hermes Remote Dashboard" registered (runs at logon).'
}
