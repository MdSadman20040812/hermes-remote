# Restart the dashboard so it reloads the NEW password hash from config.yaml,
# then print the pairing QR. Elevated: some child processes are SYSTEM-owned.
$log = 'D:\Temp\pair-restart.log'
function L($m) {
    $line = "{0}  {1}" -f (Get-Date -Format 'HH:mm:ss'), $m
    Add-Content -LiteralPath $log -Value $line
    Write-Host $line
}
Set-Content -LiteralPath $log -Value "=== pairing restart $(Get-Date) ==="

$conns = Get-NetTCPConnection -LocalPort 9119 -State Listen -ErrorAction SilentlyContinue
foreach ($c in $conns) {
    $procId = [int]$c.OwningProcess
    L "killing PID $procId"
    & taskkill.exe /PID $procId /T /F 2>&1 | ForEach-Object { L "  $_" }
}
Start-Sleep -Seconds 4

if (Get-NetTCPConnection -LocalPort 9119 -State Listen -ErrorAction SilentlyContinue) {
    L "FAILED: port still held"
    exit 1
}
L "port 9119 free"

# Start via the launcher so the bind + QR logic lives in exactly one place.
L "starting dashboard via hermes-dashboard.py ..."
$out = & python 'D:\HermesMobile\pc\hermes-dashboard.py' 2>&1
$out | ForEach-Object { L "  $_" }

# Prove the NEW credential is accepted by the freshly loaded server.
$pw = (Get-Content 'D:\HermesMobile\pc\.dashboard-password' -Raw).Trim()
$body = @{ provider = 'basic'; username = 'sadman'; password = $pw } | ConvertTo-Json -Compress
try {
    $r = Invoke-WebRequest -Uri 'http://127.0.0.1:9119/auth/password-login' `
        -Method POST -ContentType 'application/json' -Body $body -TimeoutSec 10 `
        -UseBasicParsing
    L "LOGIN OK http=$($r.StatusCode) -> $($r.Content)"
} catch {
    L "LOGIN FAILED: $($_.Exception.Message)"
}
L "done"
