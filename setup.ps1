<#
    One-time (and re-runnable) deployment of the HyRemote bridge onto the HY320.

    The bridge needs root. It shells out to `input keyevent`, `dumpsys` and
    `monkey`, and Android 13 refuses all three for an ordinary app uid - the app
    can bind port 8080 and happily answer {"ok":1} while nothing happens on
    screen. Since adbd on this firmware already runs as root, the fix is to let
    init own the bridge: this script writes it into the system image and adds a
    service that starts it, as root, early in boot.

    Run it once. After that the bridge comes up on its own at every power-on and
    re-running the script is how you ship bridge changes.

    .\setup.ps1                 # deploy, then reboot if this is the first run
    .\setup.ps1 -Reboot         # deploy and reboot unconditionally
    .\setup.ps1 -Device 192.168.40.14:5555
#>
[CmdletBinding()]
param(
    [string]$Device = "192.168.40.14:5555",
    [switch]$Reboot
)

$ErrorActionPreference = "Stop"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$SYS = "/system/etc/hyremote"

$SCRIPTS = @("boot.sh", "serve.sh", "handler.sh", "listapps.sh", "buildapps.sh", "launch.sh")

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
    & $ADB -s $Device @Rest
    if ($LASTEXITCODE -ne 0) { throw "adb $Rest failed ($LASTEXITCODE)" }
}

# Push a text file with LF endings. CRLF silently breaks Android's shell parser
# - the \r becomes part of every token, `case` patterns stop matching - and it
# surfaces as endpoints returning 404 for no visible reason.
function Push-Lf {
    param([string]$Local, [string]$Remote)
    $text = [IO.File]::ReadAllText($Local) -replace "`r`n", "`n"
    $bytes = (New-Object System.Text.UTF8Encoding $false).GetBytes($text)
    $tmp = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllBytes($tmp, $bytes)
        Push-Quiet $tmp $Remote
    }
    finally { Remove-Item $tmp -Force -ErrorAction SilentlyContinue }
}

function Push-Quiet {
    param([string]$Local, [string]$Remote)
    # adb writes its progress to stderr. With $ErrorActionPreference = "Stop"
    # PowerShell turns that into a terminating NativeCommandError, so relax it
    # for the call and trust the exit code instead.
    $prev = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $out = & $ADB -s $Device push $Local $Remote 2>&1
    } finally {
        $ErrorActionPreference = $prev
    }
    if ($LASTEXITCODE -ne 0) { throw "push $Local -> $Remote failed: $out" }
}

Write-Host "[1/5] connecting to $Device" -ForegroundColor Cyan
Adb connect $Device | Out-Null
Adb root | Out-Null
Start-Sleep -Seconds 2
Adb wait-for-device

Write-Host "[2/5] haciendo /system escribible" -ForegroundColor Cyan
# OJO: en este firmware `adb remount` RECREA el overlay de /system y borra
# todo lo que se hubiera escrito ahi (el bridge, el .rc de init...). Por eso
# solo se hace remount si /system todavia no es escribible: si ya lo es, el
# remount solo serviria para tirar el bridge a la basura.
$sysMount = (& $ADB -s $Device shell "mount | grep ' /system '") -join ""
if ($sysMount -match "\(rw") {
    Write-Host "      /system ya es escribible: no hace falta remount" -ForegroundColor DarkGray
} else {
    $out = Adb remount 2>&1
    if ("$out" -notmatch "remount succeeded") {
        throw "adb remount failed: $out`nRequires a userdebug build (this one is: ro.build.type=$(Adb shell getprop ro.build.type))"
    }
}
Adb shell "mkdir -p $SYS/www"

Write-Host "[3/5] shipping bridge scripts to $SYS" -ForegroundColor Cyan
foreach ($s in $SCRIPTS) { Push-Lf (Join-Path $ROOT $s) "$SYS/$s" }
Push-Lf (Join-Path $ROOT "www\index.html") "$SYS/www/index.html"
Push-Lf (Join-Path $ROOT "hyremote.rc") "/system/etc/init/hyremote.rc"
Adb shell "chmod 755 $SYS/*.sh"

Write-Host "[4/5] activating" -ForegroundColor Cyan
$svc = (Adb shell getprop init.svc.hyremote).Trim()
if ($svc -eq "" -or $Reboot) {
    # init only parses .rc files during boot, so the first deploy needs one.
    Write-Host "      init service not loaded yet - rebooting" -ForegroundColor Yellow
    Adb reboot | Out-Null
    Write-Host "      rebooted. Run .\verify.ps1 once the projector is back." -ForegroundColor Yellow
    return
}

# Already loaded: apply the new scripts without making the user wait a reboot.
Adb shell "stop hyremote" | Out-Null
Start-Sleep -Seconds 1
Adb shell "start hyremote" | Out-Null
Write-Host "      bridge restarted (no reboot needed)" -ForegroundColor Green

$check = Adb shell "ls $SYS/handler.sh /system/etc/init/hyremote.rc 2>&1"
if ("$check" -match "No such file") {
    Write-Host "AVISO: el bridge no quedo en /system. Vuelve a ejecutar setup.ps1." -ForegroundColor Yellow
} else {
    Write-Host "`nDone. Bridge is served by init as root on port 8080." -ForegroundColor Green
}
