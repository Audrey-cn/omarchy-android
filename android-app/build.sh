#!/usr/bin/env bash
# Zero-Gradle ultra-fast build script for OmarchyDeck.apk (aapt2 + javac + d8 + zipalign + apksigner)
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="${ANDROID_BUILD_TOOLS_ROOT:-$HOME/.local/android-build}"
export JAVA_HOME="${JAVA_HOME:-$TOOLS/jdk17/Contents/Home}"
SDK="${ANDROID_HOME:-$TOOLS/sdk}"
BT="$SDK/build-tools/34.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"

BUILD="$APP_DIR/build"
rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/obj" "$BUILD/dex"

echo "[1/5] Linking AndroidManifest & resources with aapt2..."
"$BT/aapt2" link -o "$BUILD/unaligned.apk" \
  -I "$PLATFORM" \
  --manifest "$APP_DIR/AndroidManifest.xml" \
  --java "$BUILD/gen"

echo "[2/5] Compiling Java sources..."
"$JAVA_HOME/bin/javac" --release 11 \
  -classpath "$PLATFORM" \
  -d "$BUILD/obj" \
  "$APP_DIR"/src/com/omarchy/deck/*.java

echo "[3/5] Converting bytecode to DEX with d8..."
"$BT/d8" --lib "$PLATFORM" --output "$BUILD/dex" "$BUILD"/obj/com/omarchy/deck/*.class

echo "[4/5] Packaging & aligning APK..."
(cd "$BUILD/dex" && zip -q "$BUILD/unaligned.apk" classes.dex)
"$BT/zipalign" -f 4 "$BUILD/unaligned.apk" "$BUILD/aligned.apk"

echo "[5/5] Signing debug APK..."
KEYSTORE="$APP_DIR/build/debug.keystore"
"$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$KEYSTORE" \
  -storepass android -alias omarchy -keypass android \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=OmarchyDeck, O=OmarchyAndroid, C=US" >/dev/null 2>&1
"$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
  --out "$APP_DIR/OmarchyDeck.apk" "$BUILD/aligned.apk"

ls -lh "$APP_DIR/OmarchyDeck.apk"
echo "BUILD_OK: $APP_DIR/OmarchyDeck.apk"
