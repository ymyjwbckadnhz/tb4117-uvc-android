#!/usr/bin/env bash
# Build the APK on Linux/macOS CI (no Gradle).
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -d /usr/local/lib/android/sdk ]; then
  SDK=/usr/local/lib/android/sdk
fi
[ -n "$SDK" ] || { echo "ANDROID_SDK_ROOT / ANDROID_HOME not set" >&2; exit 1; }

BUILD_TOOLS="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
PLATFORM="$SDK/platforms/android-30/android.jar"
[ -f "$PLATFORM" ] || { echo "android-30 platform missing" >&2; exit 1; }

OUT="$ROOT/build/out"
APK="$ROOT/build/tb4117-uvc-debug.apk"
KS="$ROOT/build/.dev.keystore"

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "== javac =="
javac -nowarn --release 8 -cp "$PLATFORM" -encoding UTF-8 \
      -d "$OUT/classes" "$ROOT/app/src/com/dsh/uvctest/MainActivity.java"

echo "== d8 =="
(cd "$OUT/classes" && jar cf "$OUT/classes.jar" .)
"$BUILD_TOOLS/d8" --lib "$PLATFORM" --min-api 26 --release --output "$OUT/dex" "$OUT/classes.jar"

echo "== aapt2 =="
"$BUILD_TOOLS/aapt2" link -o "$OUT/base.apk" -I "$PLATFORM" \
      --manifest "$ROOT/app/AndroidManifest.xml" \
      --min-sdk-version 26 --target-sdk-version 30

echo "== dex into apk =="
(cd "$OUT/dex" && jar uf "$OUT/base.apk" classes.dex)

echo "== zipalign =="
"$BUILD_TOOLS/zipalign" -f 4 "$OUT/base.apk" "$OUT/aligned.apk"

echo "== sign (debug keystore) =="
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
          -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
          -dname "CN=Android Debug,O=Android,C=US" >/dev/null 2>&1
fi
"$BUILD_TOOLS/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"

echo "APK: $APK"
ls -l "$APK"
