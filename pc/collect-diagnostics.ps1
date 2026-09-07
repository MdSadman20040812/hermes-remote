#requires -Version 5.1
<#
.SYNOPSIS
  Collects everything needed to diagnose the Hermes Mobile build + auth failure
  into a single readable file: D:\HermesMobile\DIAGNOSTICS.md

  Run it, then tell Claude it's done. Nothing here modifies the project.

.NOTES
  Read-only except for writing DIAGNOSTICS.md.
#>

$ErrorActionPreference = 'Continue'
$Root   = 'D:\HermesMobile'
$Out    = Join-Path $Root 'DIAGNOSTICS.md'
$Python = 'D:\.hermes\hermes-agent\venv\Scripts\python.exe'

Remove-Item $Out -ErrorAction SilentlyContinue
function W([string]$s) { Add-Content -LiteralPath $Out -Value $s -Encoding UTF8 }

W "# Hermes Mobile — Diagnostics Dump"
W ""
W ("Generated: " + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
W ""

# ---------------------------------------------------------------- 1. compile
W "---"
W ""
W "## 1. Gradle compile output"
W ""
W '```'
Push-Location $Root
try {
    $compile = & cmd /c ".\gradlew.bat compileDebugKotlin --console=plain --no-daemon 2>&1"
    $compile | ForEach-Object { W $_ }
} catch {
    W "gradlew invocation threw: $_"
} finally {
    Pop-Location
}
W '```'
W ""

# ------------------------------------------------------------ 2. dashboard
W "---"
W ""
W "## 2. Dashboard state"
W ""
W '```'
try { (& $Python -m hermes_cli.main dashboard --status 2>&1) | ForEach-Object { W $_ } }
catch { W "dashboard --status failed: $_" }
W '```'
W ""
W "### /api/status (loopback probe)"
W ""
W '```json'
try {
    $s = Invoke-WebRequest -Uri 'http://127.0.0.1:9119/api/status' -TimeoutSec 6 -UseBasicParsing
    W $s.Content
} catch {
    W "loopback probe failed: $($_.Exception.Message)"
}
W '```'
W ""
W "### /api/auth/providers"
W ""
W '```json'
try {
    $p = Invoke-WebRequest -Uri 'http://127.0.0.1:9119/api/auth/providers' -TimeoutSec 6 -UseBasicParsing
    W $p.Content
} catch {
    W "providers probe failed: $($_.Exception.Message)"
}
W '```'
W ""

# ------------------------------------------------------------ 3. networking
W "---"
W ""
W "## 3. Networking"
W ""
W '```'
$ts = "$env:ProgramFiles\Tailscale\tailscale.exe"
if (Test-Path $ts) {
    W "tailscale: PRESENT"
    (& $ts ip -4 2>&1)  | ForEach-Object { W "  ip4: $_" }
    (& $ts status 2>&1) | Select-Object -First 12 | ForEach-Object { W "  $_" }
} else {
    W "tailscale: NOT INSTALLED at $ts"
}
W ""
W "Listening on 9119:"
(netstat -ano | Select-String ':9119') | ForEach-Object { W "  $_" }
W '```'
W ""

# ------------------------------------------------------------ 4. adb
W "---"
W ""
W "## 4. ADB devices"
W ""
W '```'
$adb = "$env:USERPROFILE\Android\sdk\platform-tools\adb.exe"
if (Test-Path $adb) { (& $adb devices -l 2>&1) | ForEach-Object { W $_ } }
else { W "adb not found at $adb" }
W '```'
W ""

# ------------------------------------------------------- 5. logcat (if any)
W "---"
W ""
W "## 5. Recent app logcat"
W ""
W '```'
if (Test-Path $adb) {
    try { (& $adb logcat -d -t 200 2>&1 | Select-String -Pattern 'hermes|Hermes|AndroidRuntime|FATAL') | ForEach-Object { W $_ } }
    catch { W "logcat failed: $_" }
} else { W "(adb unavailable)" }
W '```'
W ""

# --------------------------------------------------------- 6. kotlin source
W "---"
W ""
W "## 6. Kotlin source (auth + transport + DI + connect UI)"
W ""

$dirs = @(
    "$Root\app\src\main\java\com\hermes\mobile\core\connection",
    "$Root\app\src\main\java\com\hermes\mobile\core\transport",
    "$Root\app\src\main\java\com\hermes\mobile\ui\connect"
)
$files = @()
foreach ($d in $dirs) {
    if (Test-Path $d) { $files += Get-ChildItem -LiteralPath $d -Recurse -Filter *.kt }
}
foreach ($extra in @(
    "$Root\app\src\main\java\com\hermes\mobile\di\AppModule.kt",
    "$Root\app\src\main\java\com\hermes\mobile\HermesApplication.kt",
    "$Root\app\src\main\java\com\hermes\mobile\ui\MainActivity.kt"
)) {
    if (Test-Path $extra) { $files += Get-Item -LiteralPath $extra }
}

foreach ($f in ($files | Sort-Object FullName -Unique)) {
    W ""
    W ("### " + $f.FullName.Replace($Root + '\', ''))
    W ""
    W '```kotlin'
    (Get-Content -LiteralPath $f.FullName -Raw) -split "`r?`n" | ForEach-Object { W $_ }
    W '```'
}

# ------------------------------------------------------------ 7. manifest
W ""
W "---"
W ""
W "## 7. AndroidManifest.xml"
W ""
W '```xml'
$mf = "$Root\app\src\main\AndroidManifest.xml"
if (Test-Path $mf) { (Get-Content -LiteralPath $mf -Raw) -split "`r?`n" | ForEach-Object { W $_ } }
W '```'

Write-Host ""
Write-Host "Done. Wrote $Out" -ForegroundColor Green
Write-Host ("Size: {0:N0} bytes" -f (Get-Item $Out).Length)
Write-Host "Tell Claude the diagnostics dump is ready."
