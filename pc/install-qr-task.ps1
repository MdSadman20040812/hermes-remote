# Make the pairing page permanent: start qr_server.py at logon and keep it up.
#
# Registered as a USER task (not SYSTEM) because the page binds 127.0.0.1 and is
# only ever used by the person sitting at this PC.

$ErrorActionPreference = 'Stop'
$log = 'D:\Temp\qr-task.log'
function L($m) { Add-Content -LiteralPath $log -Value $m; Write-Host $m }
Set-Content -LiteralPath $log -Value "=== qr task install $(Get-Date) ==="

$TaskName = 'Hermes Pairing Page'
$py = 'D:\.hermes\hermes-agent\venv\Scripts\pythonw.exe'
if (-not (Test-Path $py)) { $py = (Get-Command pythonw.exe -ErrorAction SilentlyContinue).Source }
if (-not $py) { $py = (Get-Command python.exe).Source }
L "interpreter: $py"

$action = New-ScheduledTaskAction -Execute $py `
    -Argument '"D:\HermesMobile\pc\qr_server.py"' `
    -WorkingDirectory 'D:\HermesMobile\pc'

$triggers = @(New-ScheduledTaskTrigger -AtLogOn)

$settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries -StartWhenAvailable `
    -RestartInterval (New-TimeSpan -Minutes 2) -RestartCount 999 `
    -ExecutionTimeLimit ([TimeSpan]::Zero)

Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false -ErrorAction SilentlyContinue

Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $triggers `
    -Settings $settings -Description 'Serves the Hermes phone-pairing QR on http://localhost:9120 (no login).' `
    -Force | Out-Null

$t = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($t) {
    L "registered: state=$($t.State)"
    Start-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 4
    $i = Get-ScheduledTaskInfo -TaskName $TaskName
    L "lastResult=$($i.LastTaskResult)"
} else {
    L "FAILED to register"
    exit 1
}

try {
    $r = Invoke-WebRequest -Uri 'http://localhost:9120/payload' -TimeoutSec 6 -UseBasicParsing
    L "VERIFY page http=$($r.StatusCode)"
} catch {
    L "VERIFY failed: $($_.Exception.Message)"
}
L "done"
