#!/usr/bin/env bash
# 哔哩喵扫码 3.1 构建脚本（无需 Gradle）
set -e

JDK=/tmp/build/jdk-17.0.21+9
BT=/tmp/build/bt/android-14
ANDROID_JAR=$HOME/android-sdk/platforms/android-35/android.jar
PROJ="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "$PROJ/libs"
if [ ! -f "$PROJ/libs/zxing-core.jar" ]; then
  echo "下载 ZXing core…"
  curl -sL -o "$PROJ/libs/zxing-core.jar" \
    https://repo1.maven.org/maven2/com/google/zxing/core/3.5.3/core-3.5.3.jar
fi

export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"

rm -rf "$PROJ/build"
mkdir -p "$PROJ/build/classes" "$PROJ/build/dex" "$PROJ/build/apk" "$PROJ/build/res"

echo "[1/7] javac"
find "$PROJ/src" "$PROJ/stubs" -name '*.java' > "$PROJ/build/sources.txt"
javac --release 8 -encoding UTF-8 -cp "$ANDROID_JAR:$PROJ/libs/zxing-core.jar" \
      -d "$PROJ/build/classes" @"$PROJ/build/sources.txt"

echo "[2/7] 桩类打 lib jar（运行时由框架提供，不进 dex）"
jar cf "$PROJ/build/stubs.jar" -C "$PROJ/build/classes" de
rm -rf "$PROJ/build/classes/de"

echo "[3/7] d8（含 ZXing core）"
jar cf "$PROJ/build/classes.jar" -C "$PROJ/build/classes" .
"$BT/d8" --min-api 21 \
   --lib "$ANDROID_JAR" \
   --lib "$PROJ/build/stubs.jar" \
   --output "$PROJ/build/dex" \
   "$PROJ/build/classes.jar" "$PROJ/libs/zxing-core.jar"

echo "[4/7] aapt2 compile res"
"$BT/aapt2" compile --dir "$PROJ/res" -o "$PROJ/build/res/compiled.zip"

echo "[5/7] aapt2 link"
"$BT/aapt2" link -o "$PROJ/build/apk/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    -R "$PROJ/build/res/compiled.zip" \
    --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 30 \
    --version-code 4 --version-name 1.0

echo "[6/7] 打包 dex / assets / module.prop"
python3 - "$PROJ" <<'PY'
import sys, os, zipfile
proj = sys.argv[1]
base = os.path.join(proj, 'build/apk/base.apk')
tmp  = os.path.join(proj, 'build/apk/unsigned.apk')
src = zipfile.ZipFile(base, 'r')
out = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
for item in src.infolist():
    out.writestr(item, src.read(item.filename))
src.close()
out.write(os.path.join(proj, 'build/dex/classes.dex'), 'classes.dex')
out.write(os.path.join(proj, 'assets/xposed_init'), 'assets/xposed_init')
out.write(os.path.join(proj, 'META-INF/xposed/module.prop'), 'META-INF/xposed/module.prop')
out.close()
PY

echo "[7/7] zipalign + 签名"
"$BT/zipalign" -f 4 "$PROJ/build/apk/unsigned.apk" "$PROJ/build/apk/aligned.apk"

KS="$PROJ/build/debug.keystore"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias bilimiao_scan10 -keyalg RSA -keysize 2048 -validity 3650 \
    -dname "CN=ScanMerged, OU=mod, O=mod, L=CN, ST=CN, C=CN" >/dev/null 2>&1
fi

"$BT/apksigner" sign --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --out "$PROJ/哔哩喵扫码-1.0.apk" "$PROJ/build/apk/aligned.apk"

"$BT/apksigner" verify --min-sdk-version 21 "$PROJ/哔哩喵扫码-1.0.apk"
ls -la "$PROJ/哔哩喵扫码-1.0.apk"
