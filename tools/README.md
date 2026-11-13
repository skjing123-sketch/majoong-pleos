# Pleos 진단 도구

출시 앱과 분리된 개발·검증 도구다. SDK·AVD·JDK·서명키·인증키·녹화물은 포함하지 않는다.

- [`pleos-trial`](pleos-trial/README.md): 외장 APFS에서 실행하는 제한 시간·실행 주체 검증·자원 감시·GNSS 입력·실제 화면 녹화 도구.
- [`nmea-observer`](nmea-observer/README.md): GPS 원문을 저장하지 않고 NMEA 종류·체크섬·사용 위성 수만 기록하는 독립 Android 앱.

도구가 실행됐다는 사실, 합성 GNSS 입력 성공, Android 관측 성공, 실제 내비 SDK 도착, 주차 후 사진 넛지는 서로 다른 증거다. 실제 도착·넛지 전체 흐름은 검증 완료되지 않았다. 운영자 없이 자동 실행하거나 사용자 기존 프로세스를 종료하는 데 사용하지 않는다.
