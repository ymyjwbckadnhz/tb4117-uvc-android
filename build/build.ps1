# Build the APK without Gradle (debug-signed with a local, git-ignored keystore).
# Requires: JDK 17+ and Android SDK build-tools + platforms/android-30.
#   $env:ANDROID_SDK = "C:\path\to\Android\Sdk"

$ErrorActionPreference = "Continue"

$root = Split-Path -Parent $PSScriptRoot
$sdk  = $env:ANDROID_SDK
if (-not $sdk) {
    $sdk = "$env:LOCALAPPDATA\Android\Sdk"
}
if (-not (Test-Path $sdk)) { throw "Android SDK not found. Set `$env:ANDROID_SDK" }

$bt = Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1
if (-not $bt) { throw "No build-tools in $sdk" }
$bt = $bt.FullName
$jar = Join-Path $sdk "platforms\android-30\android.jar"
if (-not (Test-Path $jar)) { throw "android-30 platform missing (needed for compileSdk 30)" }

$jdk = $env:JAVA_HOME
if (-not $jdk) { $jdk = Split-Path -Parent (Split-Path -Parent (Get-Command javac -ErrorAction SilentlyContinue).Source) }
if (-not $jdk) { throw "JDK not found. Set `$env:JAVA_HOME" }

$out = Join-Path $root "build\out"
$apk = Join-Path $root "build\4117Camera-debug.apk"
$ks  = Join-Path $root "build\.dev.keystore"     # git-ignored

Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$out\classes", "$out\dex" | Out-Null

Write-Host "== javac =="
& "$jdk\bin\javac.exe" -nowarn --release 8 -cp $jar -encoding UTF-8 -d "$out\classes" `
    (Join-Path $root "app\src\com\dsh\uvctest\MainActivity.java")
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "== d8 =="
& "$jdk\bin\jar.exe" cf "$out\classes.jar" -C "$out\classes" .
& "$bt\d8.bat" --lib $jar --min-api 26 --release --output "$out\dex" "$out\classes.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "== aapt2 =="
& "$bt\aapt2.exe" link -o "$out\base.apk" -I $jar `
    --manifest (Join-Path $root "app\AndroidManifest.xml") `
    --min-sdk-version 26 --target-sdk-version 30
if ($LASTEXITCODE -ne 0) { throw "aapt2 failed" }

Write-Host "== dex into apk =="
Push-Location "$out\dex"
& "$jdk\bin\jar.exe" uf "$out\base.apk" classes.dex
$rc = $LASTEXITCODE
Pop-Location
if ($rc -ne 0) { throw "jar update failed" }

Write-Host "== zipalign =="
& "$bt\zipalign.exe" -f 4 "$out\base.apk" "$out\aligned.apk"

Write-Host "== dev keystore =="
if (-not (Test-Path $ks)) {
    & "$jdk\bin\keytool.exe" -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-Null
}

Write-Host "== sign =="
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --key-pass pass:android --out $apk "$out\aligned.apk" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

Write-Host ""
Write-Host "APK: $apk"
Get-Item $apk | Select-Object Name, Length
