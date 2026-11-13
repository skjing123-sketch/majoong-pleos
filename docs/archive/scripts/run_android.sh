#!/bin/zsh
set -euo pipefail
MAJOONG_ROOT="${0:A:h:h}"
MAJOONG_CACHE="$HOME/.cache/majoong-dev"
MAJOONG_FLAVOR="${1:-pleos}"
if [[ "$MAJOONG_FLAVOR" != pleos && "$MAJOONG_FLAVOR" != demo ]]; then echo 'Usage: run_android.sh [pleos|demo]'; exit 1; fi
export ANDROID_SDK_ROOT="$MAJOONG_CACHE/android-sdk"
export ANDROID_AVD_HOME="$MAJOONG_CACHE/avd"
MAJOONG_ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
# Reuse the project's running test AVD instead of booting a second 4 GB emulator.
MAJOONG_SERIAL=emulator-5560
for MAJOONG_CANDIDATE in emulator-5560 emulator-5562; do
  if "$MAJOONG_ADB" -s "$MAJOONG_CANDIDATE" get-state >/dev/null 2>&1; then
    MAJOONG_AVD_NAME="$("$MAJOONG_ADB" -s "$MAJOONG_CANDIDATE" emu avd name 2>/dev/null | head -n 1 | tr -d '\r')"
    if [[ "$MAJOONG_AVD_NAME" == Majoong_Pleos || "$MAJOONG_AVD_NAME" == Majoong_Pleos_ArrivalTest ]]; then
      MAJOONG_SERIAL="$MAJOONG_CANDIDATE"
      break
    fi
  fi
done
if ! "$MAJOONG_ADB" -s "$MAJOONG_SERIAL" get-state >/dev/null 2>&1; then
  MAJOONG_PYTHON="$HOME/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3"
  if [[ ! -x "$MAJOONG_PYTHON" ]]; then MAJOONG_PYTHON="$(command -v python3)"; fi
  "$MAJOONG_PYTHON" "$MAJOONG_ROOT/scripts/start_emulator.py"
fi
echo 'Pleos 에뮬레이터 부팅을 기다립니다…'
for MAJOONG_STEP in {1..120}; do
  if [[ "$("$MAJOONG_ADB" -s "$MAJOONG_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then break; fi
  sleep 2
done
if [[ "$("$MAJOONG_ADB" -s "$MAJOONG_SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != 1 ]]; then echo '부팅이 완료되지 않았습니다. .run/emulator.log를 확인하세요.'; exit 1; fi
MAJOONG_APK="$MAJOONG_ROOT/artifacts/majoong-$MAJOONG_FLAVOR-debug.apk"
if [[ ! -f "$MAJOONG_APK" ]]; then "$MAJOONG_ROOT/scripts/build_android.sh"; fi
"$MAJOONG_ADB" -s "$MAJOONG_SERIAL" install -r "$MAJOONG_APK"
MAJOONG_PACKAGE=ai.nearby.reunion
if [[ "$MAJOONG_FLAVOR" == demo ]]; then MAJOONG_PACKAGE=ai.nearby.reunion.demo; fi
MAJOONG_START_RESULT="$("$MAJOONG_ADB" -s "$MAJOONG_SERIAL" shell am start -W --user current -n "$MAJOONG_PACKAGE/ai.nearby.reunion.MainActivity" 2>&1)" || { echo '마중 실행 명령이 실패했습니다.'; exit 1; }
echo "$MAJOONG_START_RESULT"
if [[ "$MAJOONG_START_RESULT" != *'Status: ok'* ]]; then
  echo 'Android가 앱 실행 성공을 확인하지 못했습니다.'
  exit 1
fi
MAJOONG_APP_PID="$("$MAJOONG_ADB" -s "$MAJOONG_SERIAL" shell pidof "$MAJOONG_PACKAGE" | tr -d '\r')" || MAJOONG_APP_PID=''
if [[ -z "$MAJOONG_APP_PID" ]]; then
  echo '앱 프로세스가 확인되지 않았습니다. 실행 결과와 Android 오류 로그를 확인하세요.'
  exit 1
fi
echo "마중 앱 프로세스 확인: $MAJOONG_APP_PID. 실제 공고를 보려면 start.command로 로컬 서버도 켜 주세요."
