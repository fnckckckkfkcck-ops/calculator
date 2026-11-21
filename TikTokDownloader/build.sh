#!/usr/bin/env bash
# Build TikSaver APK locally without Gradle.
# Requirements: JDK 17, Android build-tools 34 (aapt2, d8, apksigner, zipalign), platform android-34.
# Overridable via env: JAVA_HOME, BUILD_TOOLS_DIR, PLATFORM_JAR, OUT_APK
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT_APK="${OUT_APK:-$ROOT/dist/TikSaver.apk}"
WORK="${WORK:-$(mktemp -d)}"
KEYSTORE="${KEYSTORE:-$ROOT/debug.keystore}"
KS_PASS="${KS_PASS:-android}"

java_bin() { echo "${JAVA_HOME:+$JAVA_HOME/bin/}java"; }

BT="${BUILD_TOOLS_DIR:-}"
JARX="${PLATFORM_JAR:-}"
if [ -z "$BT" ] || [ -z "$JARX" ]; then
  TOOLS_DIR="$ROOT/../../_build/tools"
  if [ -d "$TOOLS_DIR/android-14" ] && [ -f "$TOOLS_DIR/android-34/android.jar" ]; then
    BT="${BT:-$TOOLS_DIR/android-14}"
    JARX="${JARX:-$TOOLS_DIR/android-34/android.jar}"
  else
    TOOLS_DIR="$ROOT/.android-tools"
    if [ ! -f "$TOOLS_DIR/android-34/android.jar" ]; then
      echo "Downloading Android build tools..."
      mkdir -p "$TOOLS_DIR"
      curl -fsSL -o "$TOOLS_DIR/bt.zip" "https://dl.google.com/android/repository/build-tools_r34-linux.zip"
      curl -fsSL -o "$TOOLS_DIR/plat.zip" "https://dl.google.com/android/repository/platform-34-ext8_r01.zip"
      unzip -q "$TOOLS_DIR/bt.zip" -d "$TOOLS_DIR"
      unzip -q "$TOOLS_DIR/plat.zip" -d "$TOOLS_DIR"
    fi
    BT="${BT:-$(find "$TOOLS_DIR" -maxdepth 2 -name aapt2 | head -1 | xargs dirname)}"
    JARX="${JARX:-$TOOLS_DIR/android-34/android.jar}"
  fi
fi

echo "build-tools: $BT"
echo "platform:    $JARX"

rm -rf "$WORK"; mkdir -p "$WORK/classes" "$WORK/dex" "$(dirname "$OUT_APK")"

# 1) resources + manifest -> base apk
"$BT/aapt2" compile --dir "$ROOT/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/base.apk" -I "$JARX" --manifest "$ROOT/AndroidManifest.xml" \
  --min-sdk-version 24 --target-sdk-version 34 --auto-add-overlay "$WORK/res.zip"

# 2) compile java
javac -source 8 -target 8 -bootclasspath "$JARX:$BT/core-lambda-stubs.jar" -classpath "$JARX" \
  -d "$WORK/classes" $(find "$ROOT/src" -name '*.java') 2>&1 | grep -v -e "warning" -e "deprecat" || true
test -f "$WORK/classes/com/tiksaver/app/MainActivity.class"

# 3) dex
"$(java_bin)" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 --release \
  --lib "$JARX" --min-api 24 --output "$WORK/dex" $(find "$WORK/classes" -name '*.class')

# 4) package + align
cp "$WORK/base.apk" "$WORK/out.apk"
cd "$WORK/dex" && zip -q -X "$WORK/out.apk" classes.dex
"$BT/zipalign" -f 4 "$WORK/out.apk" "$WORK/aligned.apk"

# 5) sign
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
fi
"$(java_bin)" -jar "$BT/lib/apksigner.jar" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --min-sdk-version 24 \
  --out "$OUT_APK" "$WORK/aligned.apk"

# verify
"$(java_bin)" -jar "$BT/lib/apksigner.jar" verify --print-certs "$OUT_APK" >/dev/null
"$BT/aapt2" dump badging "$OUT_APK" | head -3
echo "APK ready: $OUT_APK ($(du -h "$OUT_APK" | cut -f1))"
