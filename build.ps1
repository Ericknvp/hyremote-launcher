# Build script for HyRemote (Phase 0)
# Uses the Android SDK binaries directly - no Gradle, no dependency downloads.

$ErrorActionPreference = "Stop"

$SDK = "$env:LOCALAPPDATA\Android\Sdk"
$BT  = "$SDK\build-tools\35.0.0"
$JAR = "$SDK\platforms\android-34\android.jar"
$JDK = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"

# d8.bat and apksigner.bat need JAVA_HOME in the environment.
$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$env:PATH"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$OUT  = Join-Path $ROOT "build"
$SRC  = Join-Path $ROOT "src"
$RES  = Join-Path $ROOT "res"
$AST  = Join-Path $ROOT "assets"
$GEN  = Join-Path $OUT "gen"

# The boot receiver ships the bridge scripts inside the APK and writes them
# out on first boot.
if (-not (Test-Path $AST)) { New-Item -ItemType Directory -Path $AST -Force | Out-Null }
foreach ($s in @("serve.sh", "boot.sh", "handler.sh", "listapps.sh", "buildapps.sh", "launch.sh")) {
    # Force LF endings. Android's shell chokes on CRLF: "\r" ends up inside
    # tokens, which silently breaks `case` patterns and "inaccessible" errors.
    $text = [System.IO.File]::ReadAllText((Join-Path $ROOT $s))
    $text = $text -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText((Join-Path $AST $s), $text,
        (New-Object System.Text.UTF8Encoding $false))
}

Remove-Item $OUT -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Path $OUT, $GEN, "$OUT\classes", "$OUT\dex" -Force | Out-Null

Write-Host "[1/6] Compiling resources..." -ForegroundColor Cyan
$flat = Join-Path $OUT "res.zip"
& "$BT\aapt2.exe" compile --dir $RES -o $flat
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/6] Linking resources..." -ForegroundColor Cyan
$apk = Join-Path $OUT "unsigned.apk"
& "$BT\aapt2.exe" link `
    -o $apk `
    -I $JAR `
    --manifest (Join-Path $ROOT "AndroidManifest.xml") `
    $flat `
    --java $GEN `
    -A $AST `
    --min-sdk-version 24 `
    --target-sdk-version 33
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/6] Compiling java..." -ForegroundColor Cyan
$javaFiles = @()
$javaFiles += Get-ChildItem $SRC -Filter *.java -Recurse | ForEach-Object { $_.FullName }
$javaFiles += Get-ChildItem $GEN -Filter *.java -Recurse | ForEach-Object { $_.FullName }

& "$JDK\bin\javac.exe" -source 8 -target 8 `
    -bootclasspath $JAR `
    -classpath $JAR `
    -d "$OUT\classes" `
    -encoding UTF-8 `
    -nowarn `
    $javaFiles
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/6] Converting to dex..." -ForegroundColor Cyan
# The class list is passed through a jar: with the QR generator in the tree the
# list of .class files no longer fits in a Windows command line.
$classesJar = Join-Path $OUT "classes.jar"
& "$JDK\bin\jar.exe" cf $classesJar -C "$OUT\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
& "$BT\d8.bat" --lib $JAR --min-api 24 --output "$OUT\dex" $classesJar
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/6] Packaging..." -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($apk, "Update")
$dexEntry = $zip.CreateEntry("classes.dex")
$stream = $dexEntry.Open()
$dexBytes = [System.IO.File]::ReadAllBytes((Join-Path "$OUT\dex" "classes.dex"))
$stream.Write($dexBytes, 0, $dexBytes.Length)
$stream.Close()
$zip.Dispose()

Write-Host "[6/6] Signing..." -ForegroundColor Cyan
$ks = Join-Path $ROOT "hyremote.keystore"
if (-not (Test-Path $ks)) {
    & "$JDK\bin\keytool.exe" -genkeypair -v `
        -keystore $ks `
        -storepass hyremote -keypass hyremote `
        -alias hyremote `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=HyRemote, OU=Local, O=HyRemote, L=NA, S=NA, C=NA"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

$aligned = Join-Path $OUT "aligned.apk"
& "$BT\zipalign.exe" -f 4 $apk $aligned

$final = Join-Path $ROOT "hyremote-fase0.apk"
& "$BT\apksigner.bat" sign `
    --ks $ks `
    --ks-pass pass:hyremote `
    --key-pass pass:hyremote `
    --ks-key-alias hyremote `
    --out $final `
    $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& "$BT\apksigner.bat" verify --verbose $final

Write-Host ""
Write-Host "BUILD OK -> $final" -ForegroundColor Green
