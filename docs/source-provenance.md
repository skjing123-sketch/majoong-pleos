# 소스 복구 및 검증 기준

2026년 11월 13일 기준으로 분산돼 있던 마중 프로젝트를 이 저장소에 모았다. Google Drive의 이전 작업 폴더에는 내용이 내려받아지지 않은 파일이 많아, 최신 빌드에 실제 사용한 로컬 소스를 기준으로 복구했다. 이전 작업 폴더의 파일을 덮어쓰거나 원격 Mac의 작업을 종료하지 않았다.

| 저장소 내용 | 복구 기준 |
| --- | --- |
| `apps/android/` | 11월 12일 20:38 시험용 release 빌드에 전송한 전체 Android 소스. 도착 증거 기록기, 선택적 차량 상태 진단, 새 주행 시작 시 이전 도착 상태 취소를 포함한다. |
| `web/` | 같은 빌드의 웹 자산 17개. 당시 실제 진입점은 `app-contest.js`이며 `model-contest.js`, `arrival-banner-contest.js`를 사용했다. |
| `backend/` | 11월 10일 복구본의 서버·품종 분류 모듈·안내 문서. API 인증 정보와 실행 중인 서버 설정은 포함하지 않았다. |
| `tests/web/`, `tests/backend/`, `tests/tools/` | 원래 프로젝트의 Google Drive 폴더를 확인한 뒤 연결 도구로 원본 8개를 내려받았다. 공급자가 알려준 크기와 다운로드 크기를 대조했고 별도 SHA-256 목록을 작성했다. |
| 기타 새 웹·Android 테스트 | 11월 12일 시험 소스에 포함된 연결 패널·도착 취소·진단 기록 테스트. |
| `scripts/`의 GNSS·내비 시험 보조 도구 | 원래 Drive 소스 4개를 복구했다. ADB·SDK 경로를 명시적으로 지정하거나 환경변수에서 읽을 수 있게 정리했다. |
| `docs/archive/scripts/` | 이전 환경용 Android 빌드·실행 스크립트. 현행 실행 안내와 구분해 보관한다. |

복구 직후 최신 시험 소스의 68개 파일이 당시 `source-manifest.json`의 SHA-256과 모두 일치했다. 11월 12일 서명 시험 APK 내부의 웹 자산 17개도 해당 소스와 바이트 단위로 같았다. 이 저장소에서 이후 UI나 기능을 수정한 파일은 그 APK와 달라지므로, 기존 APK로 새 변경사항까지 검증됐다고 주장하지 않는다. 수정본은 새 빌드와 별도 실행 검증이 필요하다.

## 공식 의존성

- `ai.pleos.playground:NaviHelper:3.0.5`: [Pleos 공식 Maven 저장소의 POM](https://nexus-playground.pleos.ai/repository/maven-releases/ai/pleos/playground/NaviHelper/3.0.5/NaviHelper-3.0.5.pom). 11월 13일 HTTP 200과 group/artifact/version을 확인했다. POM에 별도 라이선스 선언은 없었다. 이 저장소는 의존성 좌표를 참조하며 Pleos SDK·에뮬레이터 이미지를 재배포하지 않는다.
- Android Automotive `android.car`: Android SDK의 공식 플랫폼 라이브러리다. 가짜 차량 API 구현을 제품 flavor에 넣지 않는다.
- Android Gradle Plugin 8.8.2, Kotlin 2.0.0, Gradle 8.10.2, Java 17, compile/target SDK 35가 복구 기준이다. Gradle wrapper의 배포 ZIP SHA-256 검증 설정도 포함돼 있다.
- `demo`와 `pleos` flavor를 구분한다. 체험 모드나 합성 GNSS 결과를 실제 Pleos 도착 증거로 사용하지 않는다.

## 화면 크기와 실행 증거의 한계

기존 Galaxy 시험의 실제 Android 화면 캡처 PNG는 **2560×1440**이다. 영상은 `screenrecord`에 **1920×1080**을 명시해 인코딩했으므로 영상 해상도가 장치 화면 해상도를 뜻하지 않는다. 현재 보존된 로컬 증거에는 `wm density` 결과가 없어 density/DPR·WebView의 실제 CSS viewport 크기는 확정하지 않았다. 다음 기기 시험에서 다시 읽어야 한다.

11월 12일 정지 GNSS 진단에서 실제 observer가 체크섬이 유효한 GPGGA 19개·GPRMC 19개를 받았다. 내비와 앱을 두 pane에 표시하는 화면도 관측했다. 이 결과는 실제 `onDestinationArrived` 콜백, 도착 뒤 신선한 P/0/UX 조건, 실공고 사진 decode 및 작은 하단 넛지가 모두 연결됐다는 증거가 아니다. 해당 연결 시험은 계속 미완료다.

원래 웹 테스트 중 일부는 이전 `model.js`·`arrival-banner.js`를 대상으로 한다. 현행 `*-contest.js` 경로의 검사를 함께 수행해야 하며, 이전 경로 테스트 통과만으로 현행 UI를 검증했다고 보고하지 않는다. 최신 기능 점검 결과는 [기능 점검 보고서](functional-audit-2026-11-13.md)를 참조한다.

복구한 Python 보조 스크립트는 수동 개발용이다. 원격 시험 실행기의 자원·PID 신원·연결 유지 검사와 종료 처리를 대신하지 않으며, ADB 서버도 명시적으로 구성해야 한다. 기존 작업을 보존해야 하는 원격 Mac에서 이 단순 launcher를 자동 실행하지 않는다.

## 제외한 항목

API 키·SSH 개인키·서명 키/암호·로컬 환경 파일·실행 PID/로그인 설정, 공고 연락처·원문 API 응답, 빌드 캐시·가상 장치 데이터·SDK 바이너리는 소스 저장소에서 제외한다. 이전 GNSS observer는 현행의 최대 5분 진단 도구와 분리하며 자동으로 실행하지 않는다.
