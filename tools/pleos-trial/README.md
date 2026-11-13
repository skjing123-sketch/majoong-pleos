# 제한된 Pleos 에뮬레이터 시험 도구

2026-11-12 사용한 macOS ARM64 시험 실행기를 게시용으로 정리한 소스다. 개인 계정·외장 볼륨·시험 경로·기기명·경로 좌표를 명시적 환경 설정으로 바꿨다. SSH 주소·키·비밀번호·API 인증키는 이 도구에 없다. 설정 변경 뒤 **실제 기기 시험은 다시 수행하지 않았다**.

## 검증 범위

- 원본의 117개 단위·전송·파서·인자 검사에 구성 검사를 추가했다. 게시용 사본에서 **123/123 통과**했다(`test-results.json`). 이 검사는 실제 ADB/차량/GNSS 입력을 실행하지 않는다.
- 2026-11-12 실제 공식 Pleos 에뮬레이터에서 정지한 합성 GGA/RMC 입력 19쌍을 보내, 별도 관측 앱에서 각 19개와 유효한 체크섬을 확인했다. 실제 화면 녹화 18.9188초/1920×1080을 보존했다.
- 위 정지 관측은 실제 내비의 지도 매칭 이동·`onDestinationArrived`·주차 후 사진 표시를 입증하지 않는다. 그 전체 흐름은 미검증이다.
- 기본 경로는 없다. 실제 공고가 있는 행정구역의 **공개 시험 장소**를 정하고 실제 내비 안내 경로를 별도로 시작해야 한다. 직선 보간 입력은 도로 중심선이나 내비 안내 경로를 대신하지 않는다.

## 구성

`trial.env.example`을 **추적하지 않는** `trial.env.local`로 복사해 검토한 뒤 사용한다. 이 도구는 설정 파일을 자동으로 읽거나 SSH 연결을 만들지 않는다. 설정 파일에는 실제 위치·개인 경로를 커밋하지 않는다.

```sh
# 예시이며 아직 좌표가 비어 있어 실행할 수 없다.
# 각 값을 해당 시험 장치와 공개 시험 경로에 맞춰 먼저 편집한다.
set -a
. /보호된/경로/trial.env.local
set +a
```

필수 값: 외장 마운트 `MAJOONG_TRIAL_MOUNT`, 그 아래 시험 루트 `MAJOONG_TRIAL_BASE`, 계정 `MAJOONG_TARGET_USER`, AVD 이름 `MAJOONG_AVD_NAME`, 경로 시작·끝 위경도 4개. 독립 ADB 포트는 기본 15037, 에뮬레이터 포트는 기본 5568이다. 환경 설정은 `check`·신원 조회에서도 필수다. 과거 실행기의 `ps`·`df` 파서에 맞춰 공백 없는 `/Volumes/NAME`과 하위 경로만 허용한다. 외장 APFS·현재 계정·ARM64·GUI 계정 조건은 그대로 검사한다.

시험 루트에는 공식 출처에서 설치한 `sdk/`, `avd/`, `android-home/`, `tmp/`가 필요하다. 이 저장소는 SDK/이미지를 재배포하지 않는다. 기존 AVD의 RAM4096MiB/4core/hostGPU 설정을 바꾸지 않는다. 전역 SDK·전원 설정도 변경하지 않는다.

## 실행 순서와 경계

1. 사용자 기존 작업을 유지한 채 AC·배터리·메모리·내부 공간·외장 연결을 확인한다. **8GiB는 내부 중단 기준이며 권장 시작 여유량이 아니다.** 메모리 압력1, 외장 실제 APFS/1GiB 이상, 배터리30% 이상을 요구한다.
2. `launch-visible.sh --plan`은 설정과 실행 계획만 보여준다. `--validate-only`는 읽기 전용 점검이다. `--start-visible --build-complete`만 전용 에뮬레이터를 실행한다. SDK/AVD는 기존 보존본을 사용하고 userdata wipe를 하지 않는다.
3. 실행 뒤 `capture-qemu-identity.sh ACTUAL_PID`로 **새 PID·UID·정확한 시작 시각·QEMU 경로·AVD·포트**를 확인한다. 예전 PID를 재사용하지 않는다.
4. 다른 단계보다 먼저 독립 foreground SSH 세션에서 정확한 신원을 넣은 `target_resource_guard.sh`를 시작한다. `--shutdown-test-emulator`가 필수다. 한 가드의 최대 시간은 1800초다. 가드를 겹치지 않고 다음 단계의 종료·녹화 회수까지 남은 시간 안에 들어가는지 확인한다.
5. 관측 앱은 정차 상태에서 사용자가 정상 위치·알림 승인을 진행하고 시작한다. `foregroundStarted`, `nmeaRegistered=true`, `statusRegistered=true`, `active`를 확인한다. 승인이나 서비스 요청 성공만으로 실제 NMEA 수신으로 판정하지 않는다.
6. `galaxy_trial.pl`의 `observe/static/drive`는 `--lease-stdin`과 실시간 foreground 제어 스트림이 필수다. 형식은 매초 `LEASE <현재 Unix 초> <증가하는 순번>`이며 3초 이상 오래된 값·EOF·연결 상실에서 입력을 멈춘다. SSH 인증은 운영자의 기존 검증된 호스트 키와 키 파일로 별도 관리한다. 인증 우회나 자동 비밀번호 입력은 하지 않는다.
7. `observe`는 로그·선택적 녹화만, `static`은 정지 GNSS만, `drive`는 실제 Car SDK paired-read 속도에 따른 합성 GNSS를 사용한다. `drive` 전에 실제 raw 콜백·내비 지도 매칭 이동·공식 Console 조작·유효한 SDK 속도를 확인한다.

명령의 공통 형식은 다음과 같다. 예시의 신원·출력은 실제 최신 값으로 바꿔야 하며 복사 실행용 값이 아니다.

```text
perl galaxy_trial.pl --mode MODE --pid NEW_PID --uid ACTUAL_UID \
  --start-time 'EXACT_PS_LSTART' --seconds BOUNDED_SECONDS \
  --out "$MAJOONG_TRIAL_BASE/verification/galaxy-only-UNIQUE" [모드별 옵션]
```

- `check`: 상태 확인. ADB 조회는 지정 독립 서버를 시작할 수 있으므로 프로세스 생성이 전혀 없다고 주장하지 않는다.
- `guard`: `--shutdown-test-emulator` 필수, 10..1800초.
- `observe/static`: `--lease-stdin` 필수, 최대120초. SDK 속도 부재/종료는 진단 메타데이터로만 기록한다.
- `drive`: `--lease-stdin` 필수, 최대90초/lead0..10초. 새 SDK 측정이 없거나 3초 이상 오래됐거나 invalid/end 신호면 중단한다. Console의 DDS `web-set` 값은 입력 속도 소스가 아니다.
- `--record`: `observe/static/drive`에서만 실제 Android `screenrecord`를 최대180초/1920×1080으로 기록한다. 도착 이벤트를 합성하지 않는다.

## 주차·녹화·정리

경로 끝점에 닿아도 곧바로 성공 처리하거나 녹화를 끊지 않는다. 공식 Console에서 운영자가 브레이크·P단을 선택한 뒤 실제 SDK의 연속 P/0을 최소30초 관측한다. 주차 확인 대기는30초, 끝점 이후 전체 관측은60초 상한이다. 입력 끝점 도달은 SDK 도착 증거가 아니다.

가드는 자원 실패·시간 만료·종료 신호에서 **정확한 소유 시험 에뮬레이터만** 종료 요청한다. ADB 요청 수락 뒤 5초 내 PID 부재를 확인하지 못하면 `operator_recheck_required=true`다. 자동 TERM 대안은 없으므로 운영자가 같은 PID/UID/시작/명령을 다시 확인하고 필요한 경우 그 프로세스만 안전하게 종료해야 한다. 이 한계가 있으므로 **무인 주행 시험은 금지**한다.

녹화 정리는 에뮬레이터 신원과 정확한 guest `screenrecord` 명령을 재확인한다. 바뀐 PID/기기는 정리를 거부한다. 전역 `pkill`, 기본 ADB, 다른 사용자 앱·Java·브라우저는 종료하지 않는다. 기존 백엔드는 유지한다. 종료 후 단순 요청 수락과 실제 프로세스 부재를 따로 확인한다.

## 로컬 검사만 실행

```sh
perl tools/pleos-trial/mock_tests.pl
```

내장 설정은 공개 NMEA 문법용 합성 좌표와 존재하지 않는 시험 경로·계정을 사용한다. 테스트가 실제 에뮬레이터·ADB·위치 입력을 실행하지 않는다. 게시용 환경 설정 실패 검사도 순수 모듈 로딩만 수행한다.
