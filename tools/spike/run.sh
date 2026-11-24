#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
SDK=~/Android/Sdk
JAVA=/opt/android-studio/jbr/bin
OUT=/tmp/opencode/echora-spike
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex"
"$JAVA/javac" -source 17 -target 17 -cp "$SDK/platforms/android-36/android.jar" \
  -d "$OUT/classes" tools/spike/Spike.java
"$SDK/build-tools/36.1.0/d8" --lib "$SDK/platforms/android-36/android.jar" --release \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
(cd "$OUT/dex" && zip -q "$OUT/spike.jar" classes.dex)
adb push "$OUT/spike.jar" /data/local/tmp/spike.jar
adb shell rm -f '/data/local/tmp/spike-*.pcm'
adb shell su -c 'app_process -Djava.class.path=/data/local/tmp/spike.jar /system/bin com.echora.spike.Spike'
