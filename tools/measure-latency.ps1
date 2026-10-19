# Mide el desglose de la latencia: red, bridge, inyeccion de tecla.
$ErrorActionPreference = "Continue"
$ip = "192.168.40.14"
$port = 8080

function Measure-Req {
    param([string]$Method, [string]$Path, [string]$Body = $null, [int]$N = 20)
    $times = @()
    for ($i = 0; $i -lt $N; $i++) {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            if ($Body) {
                Invoke-WebRequest "$ip`:$port$Path" -Method $Method -Body $Body -UseBasicParsing -TimeoutSec 5 | Out-Null
            } else {
                Invoke-WebRequest "$ip`:$port$Path" -Method $Method -UseBasicParsing -TimeoutSec 5 | Out-Null
            }
        } catch { }
        $sw.Stop()
        $times += $sw.Elapsed.TotalMilliseconds
    }
    $sorted = $times | Sort-Object
    [pscustomobject]@{
        prueba = $Path + $(if ($Body) { " ($Body)" } else { "" })
        min = [math]::Round($sorted[0], 1)
        media = [math]::Round(($times | Measure-Object -Average).Average, 1)
        p95 = [math]::Round($sorted[[int]($sorted.Count * 0.95)], 1)
        max = [math]::Round($sorted[-1], 1)
    }
}

Write-Host "Red + bridge (sin inyeccion):" -ForegroundColor Cyan
Measure-Req -Method GET -Path "/ping" | Format-Table -AutoSize

Write-Host "Red + bridge + inyeccion de tecla (keyevent 0, no-op):" -ForegroundColor Cyan
Measure-Req -Method POST -Path "/key" -Body "0" | Format-Table -AutoSize

Write-Host "Coste de 'input keyevent' solo, como root:" -ForegroundColor Cyan
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$t = @()
for ($i = 0; $i -lt 15; $i++) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $adb -s "${ip}:5555" shell "input keyevent 0" | Out-Null
    $sw.Stop()
    $t += $sw.Elapsed.TotalMilliseconds
}
$s = $t | Sort-Object
"  min={0} ms  media={1} ms  max={2} ms  (incluye el fork de adb, ~25 ms)" -f `
    [math]::Round($s[0], 1), [math]::Round(($t | Measure-Object -Average).Average, 1), [math]::Round($s[-1], 1)

Write-Host "Latencia de red pura (ping al proyector):" -ForegroundColor Cyan
& $adb -s "${ip}:5555" shell "ping -c 5 -i 0.2 192.168.40.14" 2>&1 | Select-Object -Last 3
