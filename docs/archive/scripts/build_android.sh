#!/bin/zsh
set -euo pipefail
MAJOONG_ROOT="${0:A:h:h}"
MAJOONG_CACHE="$HOME/.cache/majoong-dev"
export JAVA_HOME="${JAVA_HOME:-$MAJOONG_CACHE/jdk/amazon-corretto-17.jdk/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$MAJOONG_CACHE/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
cd "$MAJOONG_ROOT/apps/android"
# Release build resources before running the memory-heavy vehicle emulator.
./gradlew --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :app:testDemoDebugUnitTest :app:testPleosDebugUnitTest \
  :app:assembleDemoDebug :app:assemblePleosDebug
mkdir -p "$MAJOONG_ROOT/artifacts"
cp app/build/outputs/apk/demo/debug/app-demo-debug.apk "$MAJOONG_ROOT/artifacts/majoong-demo-debug.apk"
cp app/build/outputs/apk/pleos/debug/app-pleos-debug.apk "$MAJOONG_ROOT/artifacts/majoong-pleos-debug.apk"
echo "APK: $MAJOONG_ROOT/artifacts"
