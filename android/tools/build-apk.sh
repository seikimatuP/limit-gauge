#!/usr/bin/env bash
# Builds the APK without Gradle or the Android SDK build-tools:
#   aapt2 (resources) -> javac (Java 8 bytecode) -> dx (dex) -> apk_finish.py (align + v2 sign)
# This is how dist/LimitGauge-*.apk was produced. With Android Studio you can just use Gradle instead.
#
# Required environment:
#   ANDROID_JAR  path to an SDK android.jar for API 36 (contains resources.arsc)
#   AAPT2        path to an aapt2 binary
#   DX_JAR       path to dx.jar (built from AOSP platform/dalvik/dx)
#   PYTHON       python with the `cryptography` package (default: python3)
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
APP="$HERE/app/src/main"
OUT="$HERE/build/manual"
PACKAGE="com.ynozue.limitgauge"
MIN_SDK=31
TARGET_SDK=36
VERSION_CODE="${VERSION_CODE:-2}"
VERSION_NAME="${VERSION_NAME:-1.1.0}"
KEYSTORE="${KEYSTORE:-$HERE/keystore/limitgauge.p12}"
PROPS="$HERE/keystore/keystore.properties"
PYTHON="${PYTHON:-python3}"
: "${ANDROID_JAR:?set ANDROID_JAR}" "${AAPT2:?set AAPT2}" "${DX_JAR:?set DX_JAR}"

STOREPASS="${STOREPASS:-$(sed -n 's/^storePassword=//p' "$PROPS" 2>/dev/null || true)}"
[ -n "$STOREPASS" ] || { echo "no keystore password (set STOREPASS or keystore/keystore.properties)" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes"

# The source manifest has no package attribute (Gradle supplies the namespace); add it here.
sed "s#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">#<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"$PACKAGE\">#" \
  "$APP/AndroidManifest.xml" > "$OUT/AndroidManifest.xml"
grep -q "package=\"$PACKAGE\"" "$OUT/AndroidManifest.xml"

echo "== aapt2 compile"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/compiled/"

echo "== aapt2 link"
"$AAPT2" link -o "$OUT/resources.apk" -I "$ANDROID_JAR" \
  --manifest "$OUT/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version "$MIN_SDK" --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  "$OUT"/compiled/*.flat

echo "== javac"
find "$APP/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -encoding UTF-8 -Xlint:-options \
  -d "$OUT/classes" @"$OUT/sources.txt"

# dx cannot desugar lambdas/indy; make sure none slipped in.
if find "$OUT/classes" -name '*.class' -print0 | xargs -0 javap -c -p 2>/dev/null | grep -q invokedynamic; then
  echo "invokedynamic found (lambda or method reference?) - dx cannot handle it" >&2
  exit 1
fi

echo "== dx"
java -jar "$DX_JAR" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"

echo "== align + sign (APK Signature Scheme v2)"
mkdir -p "$HERE/../dist"
# The password goes through the environment, not argv, so other users cannot see it in ps.
APK_STOREPASS="$STOREPASS" "$PYTHON" "$HERE/tools/apk_finish.py" "$OUT/resources.apk" "$OUT/classes.dex" "$KEYSTORE" \
  "$HERE/../dist/LimitGauge-$VERSION_NAME.apk"
