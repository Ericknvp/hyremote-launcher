<#
    Checks that the bridge is alive, is running as root, and actually does
    something when you press a button.

    The last check is the point of the whole script. A bridge running as the
    app uid answers {"ok":1} to every request while nothing happens on screen,
    so "the endpoint returned 200" is not evidence that the control works.

    .\verify.ps1
    .\verify.ps1 -Device 192.168.40.14:5555
#>
[CmdletBinding()]
param([string]$Device = "192.168.40.14:5555")

$ErrorActionPreference = "Continue"
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

$ip = ($Device -split ":")[0]

# The bridge picks the first free port in 8080-8095 and records it. Read that
# instead of assuming 8080, or the checks fail on a projector where 8080 was
# already taken.
$portRaw = (& $ADB -s $Device shell "cat /data/local/tmp/hyremote/port 2>/dev/null") -join ""
$portRaw = $portRaw.Trim()
$port = if ($portRaw -match '^\d+$') { [int]$portRaw } else { 8080 }
$base = "http://${ip}:$port"
$fail = 0

function Ok($name, $cond, $detail) {
    if ($cond) { Write-Host "  [OK]   $name $detail" -ForegroundColor Green }
    else { Write-Host "  [FAIL] $name $detail" -ForegroundColor Red; $script:fail++ }
}

function Get-Text($url) {
    try {
        $r = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 15
        if ($r.Content -is [byte[]]) { return [Text.Encoding]::UTF8.GetString($r.Content) }
        return [string]$r.Content
    } catch { return "ERR: $($_.Exception.Message)" }
}

Write-Host "`nBridge health  ($base)" -ForegroundColor Cyan

# --- transport -------------------------------------------------------------
$ping = Get-Text "$base/ping"
Ok "GET /ping" ($ping -eq "pong") "'$ping'"

# --- ownership: this is the one that actually matters ----------------------
$listener = (& $ADB -s $Device shell "netstat -ltnp 2>/dev/null | grep ':$port '") -join ""
Ok "listener on $port" ($listener -ne "") ""

# netstat prints "<pid>/nc" without the owning user, so read the user from ps.
# Parsed here rather than with awk on the device: PowerShell mangles the quoting.
$ncLines = @(& $ADB -s $Device shell "ps -A -o USER,ARGS 2>/dev/null | grep nc" 2>$null)
$owner = "?"
foreach ($l in $ncLines) {
    $t = "$l".Trim()
    # Skip the grep itself; keep the first column of the real nc process.
    if ($t -and $t -notmatch "grep" -and $t -match "\bnc\b.*\b8080\b") {
        $owner = ($t -split '\s+')[0]
        break
    }
}
Ok "runs as root"    ($owner -eq "root") "(user: '$owner')"

$mode = (& $ADB -s $Device shell "ps -A -o ARGS 2>/dev/null | grep -w nc | grep -v grep") -join ""
Ok "nc -L (persistent listener)" ($mode -match "-L") ""

$svc = (& $ADB -s $Device shell getprop init.svc.hyremote).Trim()
Ok "init service" ($svc -eq "running") "(init.svc.hyremote=$svc)"

# --- endpoints that need root ---------------------------------------------
$vol = Get-Text "$base/volume"
Ok "GET /volume" ($vol -match "^\d+/\d+/[01]$") "$vol"

# /focus answers with the bare package name, not pkg/activity.
$focus = Get-Text "$base/focus"
Ok "GET /focus"  ($focus -match "^[a-z0-9._]+$") "$focus"

# --- the real test: does a key press do anything? --------------------------
Write-Host "`nBehaviour (the test that matters)" -ForegroundColor Cyan
# am start writes "Activity not started..." to stderr when the task is already
# up. That is expected here, not a problem, so keep it out of the report.
& $ADB -s $Device shell "am start -a android.settings.SETTINGS" 2>$null | Out-Null
Start-Sleep -Seconds 3
$before = (& $ADB -s $Device shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus") -join ""
$before = if ($before -match "mCurrentFocus=Window\{[^}]*\s+(\S+)") { $Matches[1] } else { "?" }

try {
    Invoke-WebRequest "$base/key" -Method POST -Body "3" -UseBasicParsing -TimeoutSec 15 | Out-Null
} catch { Write-Host "  (request failed: $($_.Exception.Message))" -ForegroundColor Red; $fail++ }
Start-Sleep -Seconds 2

$after = (& $ADB -s $Device shell "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus") -join ""
$after = if ($after -match "mCurrentFocus=Window\{[^}]*\s+(\S+)") { $Matches[1] } else { "?" }

Write-Host "  focus before HOME: $before"
Write-Host "  focus after  HOME: $after"
Ok "HOME key from the web moves the screen" ($after -match "hyremote") ""

& $ADB -s $Device shell "input keyevent 3" | Out-Null

Write-Host ""
if ($fail -eq 0) {
    Write-Host "All good - the control works." -ForegroundColor Green
} else {
    Write-Host "$fail check(s) failed. If /volume reads 0/50/1 or a key does nothing, the bridge is running as the app instead of root - rerun setup.ps1." -ForegroundColor Red
}
exit $fail
