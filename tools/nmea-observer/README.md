# 독립 NMEA 관측 앱

출시 앱과 분리된 `ai.nearby.reunion.nmeaprobe`의 소스다. 원본 준비 시점은 2026-11-12이며, 게시를 위해 소스·단위 검사·빌드 안내만 포함했다. APK·SDK·JDK·서명키·실제 좌표·원문 로그는 포함하지 않는다.

## 역할

Android `LocationManager.addNmeaListener`와 `registerGnssStatusCallback`을 관측한다. GPS 구독을 유지하려고 위치 업데이트를 요청하지만 `Location` 값이나 NMEA 원문을 로그·파일에 저장하지 않는다. Pleos SDK·차량 제어·경로 요청·도착 이벤트 생성·인터넷 권한이 없다.

Activity가 보일 때 사용자가 정확한 위치·알림을 승인하고 시작한다. location Foreground Service는 알림/화면의 중지 버튼과 최대5분 자동 중지를 제공하며 `START_NOT_STICKY`, `exported=false`다. 주행 중 시작하거나 동작을 보장하는 앱이 아니다.

기록하는 정보는 문장 종류·문장 길이·체크섬 유효 여부·`usedInFix` 수와 준비/등록/종료 상태뿐이다. 좌표·원문 NMEA 시간·위성 ID·연락처·인증키·예외 본문은 기록하지 않는다.

```text
MajoongNmeaProbe: sentenceType=GPGGA length=<정수> checksumValid=<true/false>
MajoongNmeaProbe: usedInFix=<정수>
```

## 실제로 확인한 범위

2026-11-12 공식 Pleos 에뮬레이터의 별도 진단 APK에서 위치·알림 정상 승인과 FGS/NMEA 등록을 확인했다. 정지 합성 입력19쌍에 대해 GPGGA19개와 GPRMC19개 모두 유효한 체크섬으로 관측했다. 이 게시용 소스 사본을 새로 빌드하거나 설치한 결과는 아니다.

이는 **Android 진단 앱의 NMEA 수신** 증거다. 기본 내비의 파싱·지도 매칭·NaviHelper 도착·주차 후 사진 넛지는 별도이며 미검증이다. `usedInFix=0`만으로 원문 입력 실패를 단정하지 않는다. 소스의 초기 화면 문구는 준비 당시의 상태 설명이므로 실제 결과 판정에는 매 실행의 서비스·콜백 로그를 사용한다.

## 소스와 빌드

- `AndroidManifest.xml`: min28/target35, 위치·알림·location FGS 권한.
- `MainActivity.java`: 명시적 시작·권한 승인·중지.
- `NmeaProbeService.java`: 최대5분 관측과 자원 정리.
- `NmeaSummary.java`: Android 의존성 없는 메타데이터·체크섬 요약.
- `NmeaSummaryTest.java`: 공개 표준 fixture에 대한16개 순수 Java 검사.

[`BUILD_PLAN.md`](BUILD_PLAN.md)는 별도 외장 작업 폴더에서 공식 JDK17·Android35 도구를 사용하는 절차다. 자원 경고 상태에서는 빌드를 시작하지 않는다. 출시용 키와 별도의 진단 서명키를 사용하고 앱·기본 내비·사용자 작업을 중지하지 않는다. 이 게시 작업에서 Java 컴파일·APK 빌드·기기 실행은 하지 않았다.

공식 참고: [LocationManager](https://developer.android.com/reference/android/location/LocationManager), [location FGS](https://developer.android.com/develop/background-work/services/fgs/service-types#location), [FGS 시작](https://developer.android.com/develop/background-work/services/fgs/launch).
