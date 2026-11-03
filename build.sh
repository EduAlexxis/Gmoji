#!/bin/bash
# Build and sign the module: ./build.sh [install]
# Output: build/Gmoji.apk. Needs the Android SDK (build-tools + a platform) and a JDK.
#
# Signing uses a throwaway key kept outside the project (GMOJI_KEYSTORE, default ~/.android/gmoji-debug.jks),
# created on first run. Keep it: an installed copy can only be updated by an APK signed with the same key.
set -e
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
BT=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)
JAR=$(ls -d "$SDK"/platforms/android-3*/android.jar | sort -V | tail -1)
KS=${GMOJI_KEYSTORE:-$HOME/.android/gmoji-debug.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -alias k -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=Gmoji" >/dev/null
  echo "created signing key $KS"
fi
rm -rf build && mkdir -p build/classes build/dex
javac -nowarn -source 8 -target 8 -cp "$JAR" -d build/classes $(find src stubs -name '*.java') > build/javac.log 2>&1 || { grep -v "^warning" build/javac.log; echo "COMPILE FAILED"; exit 1; }
rm -rf build/classes/de   # the framework provides the real Xposed API at runtime
"$BT/d8" --min-api 29 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml build/res.zip
(cd build/dex && zip -q ../base.apk classes.dex)
zip -q build/base.apk assets/xposed_init
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk
"$BT/apksigner" sign --ks "$KS" --ks-key-alias k --ks-pass pass:android --out build/Gmoji.apk build/aligned.apk
rm -f build/Gmoji.apk.idsig
echo "built build/Gmoji.apk"
[ "$1" = install ] && adb install -r build/Gmoji.apk
exit 0
