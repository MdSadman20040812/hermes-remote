#requires -Version 5.1
<#
.SYNOPSIS
  Hermes Remote — PC-side launcher (spec Part G).

  1. Ensures a stable HERMES_DASHBOARD_SESSION_TOKEN in D:\.hermes\.env
  2. Detects the Tailscale IPv4 (falls back to loopback with a warning)
  3. Starts `hermes dashboard` bound to that IP on port 9119
  4. Renders the pairing QR in the terminal
  5. -Register installs a Scheduled Task so it survives reboot

.NOTES
  Bind rule (brief §4.4): never 0.0.0.0. Tailscale IP if logged in, else
  127.0.0.1 — and says so loudly, because loopback means the phone can't pair.
#>
param(
    [switch]$Register,
    [switch]$Stop,
    [int]$Port = 9119
)

$ErrorActionPreference = 'Stop'
$HermesHome = 'D:\.hermes'
$EnvFile    = Join-Path $HermesHome '.env'
$Python     = Join-Path $HermesHome 'hermes-agent\venv\Scripts\python.exe'
$RenderQr   = Join-Path $PSScriptRoot 'render_qr.py'
$Tailscale  = "$env:ProgramFiles\Tailscale\tailscale.exe"

function Get-OrCreateToken {
    $line = Select-String -Path $EnvFile -Pattern '^HERMES_DASHBOARD_SESSION_TOKEN=(.+)$' -ErrorAction SilentlyContinue
    if ($line) { return $line.Matches[0].Groups[1].Value.Trim() }
    $token = & $Python -c "import secrets; print(secrets.token_urlsafe(48))"
    Add-Content -Path $EnvFile -Value "`n# Stable dashboard session token for Hermes Remote - added by hermes-remote.ps1`nHERMES_DASHBOARD_SESSION_TOKEN=$token"
    Write-Host "Generated a new dashboard session token and persisted it to $EnvFile"
    return $token
}

function Get-TailscaleIp {
    if (-not (Test-Path $Tailscale)) { return $null }
    $ip = (& $Tailscale ip -4 2>$null | Select-Object -First 1)
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($ip)) { return $null }
    return $ip.Trim()
}

if ($Stop) {
    & $Python -m hermes_cli.main dashboard --stop
    exit $LASTEXITCODE
}

$token = Get-OrCreateToken
$env:HERMES_DASHBOARD_SESSION_TOKEN = $token

$bind = Get-TailscaleIp
if ($null -eq $bind) {
    $bind = '127.0.0.1'
    Write-Warning 'Tailscale is not logged in — binding to LOOPBACK. The phone cannot pair until you run: tailscale up'
}

# Never bind all-interfaces (brief §4.4 — unsandboxed execution must not be
# exposed to every network the machine joins).
if ($bind -eq '0.0.0.0') { throw 'Refusing to bind 0.0.0.0' }

Write-Host "Starting Hermes dashboard on http://${bind}:$Port ..."
$proc = Start-Process -FilePath $Python -PassThru -WindowStyle Hidden `
    -ArgumentList @('-m','hermes_cli.main','dashboard','--host',$bind,'--port',"$Port",'--no-open','--skip-build') `
    -WorkingDirectory (Join-Path $HermesHome 'hermes-agent')

Start-Sleep -Seconds 4
try {
    $status = Invoke-RestMethod -Uri "http://${bind}:$Port/api/status" -TimeoutSec 5
    Write-Host "Dashboard up: Hermes v$($status.version), gateway=$($status.gateway_state), auth_required=$($status.auth_required)"
} catch {
    Write-Warning "Dashboard did not answer on http://${bind}:$Port — check the log. $_"
}

$payload = @{
    v = 1; name = $env:COMPUTERNAME; host = $bind; port = $Port
    token = $token; fingerprint = (& $Python -c "import hashlib,sys; print(hashlib.sha256(sys.argv[1].encode()).hexdigest())" $token)
} | ConvertTo-Json -Compress

& $Python $RenderQr $payload

if ($Register) {
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' `
        -Argument "-NoProfile -WindowStyle Hidden -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    Register-ScheduledTask -TaskName 'Hermes Remote Dashboard' -Action $action -Trigger $trigger `
        -Description 'Starts the Hermes dashboard bound to the Tailscale IP and prints the pairing QR.' -Force | Out-Null
    Write-Host 'Scheduled task "Hermes Remote Dashboard" registered (runs at logon).'
}
