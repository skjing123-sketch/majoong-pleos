# 외장 저장장치에서의 경량 빌드 절차

Gradle을 시작하거나 기존 프로젝트를 덮어쓰지 않고 Java·aapt2·D8로 만든다. 아래는 실행 전에 검토할 명령 형태다. **이 문서는 명령 형태를 설명한다. 이 게시용 사본에서는 빌드·설치를 실행하지 않았다.**

## 준비할 도구·경로

- 공식 JDK 17의 실제 설치본. macOS `/usr/bin/java`·`/usr/bin/python3` stub이나 개발자 도구 자동 설치 요청에 의존하지 않는다.
- 공식 Android SDK Platform 35의 `android.jar`.
- 공식 Android build-tools의 `aapt2`, `lib/d8.jar`, `zipalign`, `apksigner`.
- 위 도구·소스 사본·작업 출력·JVM 임시 폴더는 모두 시험용 Mac 외장 APFS의 별도 시험 루트 아래에 둔다. `ANDROID_USER_HOME`, `ANDROID_EMULATOR_HOME`, SDK/AVD 기본 경로를 전역으로 바꾸지 않는다.
- 공식 다운로드 URL·크기·체크섬 검증 뒤 사용할 도구 버전을 기록한다. 공간이 확보돼도 메모리 경고나 기존 작업 영향이 있으면 중단한다.

아래 변수는 **실제로 존재하고 검증된 외장 경로**로 지정한다. `JAVA_HOME` 등 공용 세션 환경을 덮어쓰는 대신 절대 실행파일과 시험용 변수만 사용한다.

```sh
# Example shape only; resolve actual paths before executing.
trial_root=${MAJOONG_TRIAL_BASE}/nmea-probe
trial_java="$trial_root/toolchain/jdk-17/Contents/Home"
trial_sdk="$trial_root/toolchain/android-sdk"
trial_tools="$trial_sdk/build-tools/35.0.0"
trial_jar="$trial_sdk/platforms/android-35/android.jar"
trial_src="$trial_root/source"
trial_out="$trial_root/build"
```

## 1. 순수 Java 체크섬 검사

출력과 Java 임시 경로를 먼저 외장에 만든다. 아래 두 명령은 앱을 빌드하지 않는 단위 검사지만 자원 경고 상태에서는 단위 검사도 실행하지 않는다. 이 게시용 사본에서는 Java 검사를 실행하지 않았다.

```sh
mkdir -p "$trial_out/unit" "$trial_out/tmp"
"$trial_java/bin/javac" -J-Xmx128m -J-Djava.io.tmpdir="$trial_out/tmp" \
  -source 8 -target 8 -d "$trial_out/unit" \
  "$trial_src/src/ai/nearby/reunion/nmeaprobe/NmeaSummary.java" \
  "$trial_src/tests/ai/nearby/reunion/nmeaprobe/NmeaSummaryTest.java"
"$trial_java/bin/java" -Xmx64m -Djava.io.tmpdir="$trial_out/tmp" \
  -cp "$trial_out/unit" ai.nearby.reunion.nmeaprobe.NmeaSummaryTest
```

성공 출력은 `16 NMEA metadata checks passed`여야 한다. 출력에는 raw fixture를 넣지 않는다. 이 검사가 통과해도 Android 서비스가 컴파일되거나 기기에서 작동한다는 뜻은 아니다.

## 2. Android 클래스·DEX·APK

이 앱은 자체 리소스와 외부 라이브러리를 사용하지 않는다. UI/알림 문자열은 Java와 manifest에 있고 작은 아이콘은 플랫폼 리소스를 사용하므로 `aapt2 link`에 별도 resource archive가 필요 없다. Android 소스 세 파일을 명시적으로 컴파일한다.

```sh
mkdir -p "$trial_out/classes" "$trial_out/dex" "$trial_out/generated"
"$trial_tools/aapt2" link -I "$trial_jar" \
  --manifest "$trial_src/AndroidManifest.xml" --java "$trial_out/generated" \
  --min-sdk-version 28 --target-sdk-version 35 -o "$trial_out/resources.apk"
"$trial_java/bin/javac" -J-Xmx256m -J-Djava.io.tmpdir="$trial_out/tmp" \
  -source 8 -target 8 -classpath "$trial_jar" -d "$trial_out/classes" \
  "$trial_src/src/ai/nearby/reunion/nmeaprobe/NmeaSummary.java" \
  "$trial_src/src/ai/nearby/reunion/nmeaprobe/MainActivity.java" \
  "$trial_src/src/ai/nearby/reunion/nmeaprobe/NmeaProbeService.java"
find "$trial_out/classes" -name '*.class' -print > "$trial_out/classes.list"
"$trial_java/bin/java" -Xmx256m -Djava.io.tmpdir="$trial_out/tmp" \
  -cp "$trial_tools/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$trial_jar" --min-api 28 --output "$trial_out/dex" \
  @"$trial_out/classes.list"
cp "$trial_out/resources.apk" "$trial_out/unsigned.apk"
(cd "$trial_out/dex" && /usr/bin/zip -q -j "$trial_out/unsigned.apk" classes.dex)
"$trial_tools/zipalign" -f 4 "$trial_out/unsigned.apk" "$trial_out/aligned.apk"
```

D8의 응답 파일 형식과 build-tools 배포본 경로는 도구의 도움말로 확인한 뒤 실행한다. 디렉터리나 출력 파일이 이미 있으면 기존 시험 결과를 덮어쓰지 말고 새 build 폴더를 택한다. 주행 시험이나 에뮬레이터와 무리한 동시 빌드는 하지 않는다.

## 3. 독립 진단용 서명과 확인

전용 진단 keystore를 소유자 전용 디렉터리에 만들고 출시용 키는 사용하지 않는다. 암호를 명령 인수나 보고서에 넣지 않는다. `apksigner --ks-pass file:<보호된 경로>` 및 `--key-pass file:<보호된 경로>`와 같이 도구가 지원하는 입력으로 전달한다. 서명 과정에서도 Java 임시 경로는 외장으로 지정한다.

최종 APK는 별도 파일로 만들고 다음을 확인한다.

- `apksigner verify --verbose`와 `zipalign -c 4` 성공.
- `aapt2 dump badging`의 package가 정확히 `ai.nearby.reunion.nmeaprobe`이며 release와 다름.
- manifest의 인터넷·차량·NaviHelper 권한 부재, service exported=false 및 location type.
- APK 크기·SHA-256·도구 버전과 source manifest를 기록.
- 설치 전 `adb -P 15037 -s emulator-5568 shell am get-current-user`로 user 10과 전용 emulator를 재확인.

설치와 서비스 시작은 저장공간·메모리 조건을 충족한 다음 별도로 승인된 시험 단계에서만 진행한다. 이 문서는 게시용 사본의 빌드 증거가 아니다. 과거 실제 실행 범위는 README.md에 별도로 기록했다.
