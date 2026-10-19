# Latencia real por socket (sin el overhead de Invoke-WebRequest).
$ip = "192.168.40.14"
$port = 8080

function Invoke-Raw {
    param([string]$Method, [string]$Path, [string]$Body = $null, [int]$N = 20)
    $times = @()
    for ($i = 0; $i -lt $N; $i++) {
        $client = New-Object System.Net.Sockets.TcpClient
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $client.Connect($ip, $port)
            $stream = $client.GetStream()
            $req = "$Method $Path HTTP/1.1`r`nHost: $ip`r`nConnection: close`r`n"
            if ($Body -ne $null) {
                $req += "Content-Type: text/plain`r`nContent-Length: $($Body.Length)`r`n"
            }
            $req += "`r`n"
            $bytes = [Text.Encoding]::ASCII.GetBytes($req)
            $stream.Write($bytes, 0, $bytes.Length)
            if ($Body -ne $null) {
                $b = [Text.Encoding]::ASCII.GetBytes($Body)
                $stream.Write($b, 0, $b.Length)
            }
            $stream.Flush()
            $buf = New-Object byte[] 4096
            $null = $stream.Read($buf, 0, $buf.Length)
        } catch { }
        $sw.Stop()
        $times += $sw.Elapsed.TotalMilliseconds
        $client.Close()
    }
    $s = $times | Sort-Object
    [pscustomobject]@{
        prueba = $Path
        min = [math]::Round($s[0], 1)
        media = [math]::Round(($times | Measure-Object -Average).Average, 1)
        p95 = [math]::Round($s[[int]($s.Count * 0.95)], 1)
    }
}

Write-Host "Latencia por socket (sin inyeccion):" -ForegroundColor Cyan
Invoke-Raw -Method GET -Path "/ping" | Format-Table -AutoSize
Write-Host "Latencia por socket (con inyeccion de tecla):" -ForegroundColor Cyan
Invoke-Raw -Method POST -Path "/key" -Body "0" | Format-Table -AutoSize
