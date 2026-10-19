<#
    Puts the projector back the way it came from the factory.

    Removes everything HyRemote installed: the root bridge in /system, the
    init service that starts it, and the app itself. The stock launcher comes
    back and the home app goes back to it.

    .\uninstall.ps1                 # asks before touching anything
    .\uninstall.ps1 -Force          # no questions
    .\uninstall.ps1 -KeepApp        # leaves the APK installed, just disabled
    .\uninstall.ps1 -Device 192.168.40.14:5555

    To put HyRemote back afterwards: setup.ps1, reinstall the APK and open it
    once ("Poner como launcher").
#>
[CmdletBinding()]
param(
    [string]$Device = "192.168.40.14:5555",
    [switch]$Force,
    [switch]$KeepApp
)

$ErrorActionPreference = "Stop"
$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$ADB = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

$OURS = "com.hyremote.ctrl"
$OEM  = "com.htc.htclauncherhighenglishd08"
$OEM_HOME = "com.htc.htclauncherhighenglishd08/com.mao.launcher.activity.BarrelActivity"

function Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
    $out = & $ADB -s $Device @Rest 2>&1
    return $out
}

function AdbRoot {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
    $out = & $ADB -s $Device shell @Rest 2>&1
    return $out
}

Write-Host "Quitando HyRemote de $Device" -ForegroundColor Cyan

Adb connect $Device | Out-Null
Adb wait-for-device | Out-Null

if (-not $Force) {
    Write-Host ""
    Write-Host "  Se va a quitar:" -ForegroundColor Yellow
    Write-Host "   - el bridge root de /system (hyremote.rc y /system/etc/hyremote)"
    Write-Host "   - HyRemote como launcher, y se devuelve el del fabricante"
    if ($KeepApp) {
        Write-Host "   - la app queda instalada pero desactivada"
    } else {
        Write-Host "   - la app (com.hyremote.ctrl)"
    }
    Write-Host ""
    $answer = Read-Host "  Continuar? (s/N)"
    if ($answer -ne "s" -and $answer -ne "S") {
        Write-Host "Cancelado. No se tocó nada." -ForegroundColor Green
        exit 0
    }
}

# ---------------------------------------------------------------- 1. el bridge
Write-Host "[1/4] Parando el bridge" -ForegroundColor Cyan
AdbRoot "stop hyremote" | Out-Null
AdbRoot "stop hyremote-launcher" | Out-Null
Start-Sleep -Seconds 1
AdbRoot "pkill -9 -f 'nc .*hyremote/handler.sh'" | Out-Null
AdbRoot "rm -rf /data/local/tmp/hyremote" | Out-Null
Write-Host "      bridge parado" -ForegroundColor Green

Write-Host "[2/4] Borrando el bridge de /system" -ForegroundColor Cyan
$remount = AdbRoot "remount" 2>&1
if ("$remount" -match "remount succeeded") {
    AdbRoot "rm -f /system/etc/init/hyremote.rc" | Out-Null
    AdbRoot "rm -rf /system/etc/hyremote" | Out-Null
    Write-Host "      /system limpio (survive a los reinicios)" -ForegroundColor Green
} else {
    Write-Host "      no se pudo escribir en /system:" -ForegroundColor Yellow
    Write-Host "      $remount" -ForegroundColor Yellow
    Write-Host "      el bridge se va igual al reiniciar, pero sus archivos siguen en /system" -ForegroundColor Yellow
}

# ------------------------------------------------------- 3. el launcher original
Write-Host "[3/4] Devolviendo el launcher del fabricante" -ForegroundColor Cyan
AdbRoot "pm enable --user 0 $OEM" | Out-Null
AdbRoot "cmd package set-home-activity $OEM_HOME" | Out-Null
AdbRoot "cmd role add-role-holder --user 0 android.app.role.HOME $OEM" | Out-Null
$homeApp = AdbRoot "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"
Write-Host "      launcher ahora: $homeApp" -ForegroundColor Green

# ------------------------------------------------------------------ 4. la app
Write-Host "[4/4] Quitando la app" -ForegroundColor Cyan
if ($KeepApp) {
    AdbRoot "pm disable-user --user 0 $OURS" | Out-Null
    Write-Host "      HyRemote queda instalada pero desactivada" -ForegroundColor Green
} else {
    AdbRoot "pm uninstall $OURS" | Out-Null
    Write-Host "      HyRemote desinstalada" -ForegroundColor Green
}

Write-Host ""
Write-Host "Listo. El proyector vuelve al estado de fabrica." -ForegroundColor Green
Write-Host "Para dejar el puente de red como estaba:" -ForegroundColor Yellow
Write-Host "  Ajustes > Red > WiFi: connectate a la red que usaba antes." -ForegroundColor Yellow
