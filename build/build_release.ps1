# Build a release APK signed with your own keystore.
#
#   $env:ANDROID_SDK    = "C:\path\to\Android\Sdk"
#   $env:KEYSTORE       = "C:\path\to\your.keystore"
#   $env:KEYSTORE_PASS  = "******"
#   $env:KEY_ALIAS      = "youralias"
#   # optional: $env:KEY_PASS (defaults to KEYSTORE_PASS)
#
# Never commit the keystore or its password.

$ErrorActionPreference = "Stop"

if (-not $env:KEYSTORE -or -not $env:KEYSTORE_PASS -or -not $env:KEY_ALIAS) {
    throw "Set KEYSTORE, KEYSTORE_PASS and KEY_ALIAS environment variables first."
}
$keyPass = $env:KEY_PASS
if (-not $keyPass) { $keyPass = $env:KEYSTORE_PASS }

& (Join-Path $PSScriptRoot "build.ps1")

$root = Split-Path -Parent $PSScriptRoot
$sdk  = $env:ANDROID_SDK
if (-not $sdk) { $sdk = "$env:LOCALAPPDATA\Android\Sdk" }
$bt = (Get-ChildItem (Join-Path $sdk "build-tools") -Directory | Sort-Object Name -Descending | Select-Object -First 1).FullName

$src = Join-Path $root "build\out\aligned.apk"
$dst = Join-Path $root "build\4117Camera-release.apk"

Write-Host "== sign (release) =="
& "$bt\apksigner.bat" sign --ks $env:KEYSTORE --ks-key-alias $env:KEY_ALIAS `
    --ks-pass "pass:$env:KEYSTORE_PASS" --key-pass "pass:$keyPass" --out $dst $src
if ($LASTEXITCODE -ne 0) { throw "sign failed" }

& "$bt\apksigner.bat" verify --print-certs $dst
Write-Host ""
Write-Host "APK: $dst"
