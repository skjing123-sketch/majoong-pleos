# 실제 공고 적용 자료

2026년 11월 15일 23:07 KST, 실제 분실동물 조회 API에서 받은 서울특별시 공고 중 **마포구 한국 고양이 1건과 공고 사진**을 저장했습니다. `sample.json`은 이때의 조회 기록입니다. 실시간 자동 갱신 화면이나 현재도 미발견 상태라는 확인을 뜻하지 않습니다. 실종일은 API의 `happenDt` 값에서 날짜를 확인한 **2026-10-21**입니다. 시간은 이 자료에서 표시하지 않습니다.

저장소의 웹·Android 앱에는 실제 API 연동 코드가 구현돼 있습니다. 실시간 조회에는 인증키가 설정된 로컬 백엔드가 필요하며, 공개 HTTPS 체험 서버는 배포하지 않았습니다. 인증키 없이도 이 폴더의 공개용 조회 기록과 사진은 확인할 수 있습니다. 키·연락처·상세 주소·원문 응답은 포함하지 않았습니다.

## 실제 조회와 배치 시안의 구분

- 공고·사진: 실제 API 응답을 적용했습니다. 서울특별시 조회에서 반환된 18건 중 1건을 선택한 자료이며 지역 전체 공고 목록이 아닙니다.
- 조회 범위: 최근 30일, 2026-10-17~2026-11-15의 페이지 조회입니다. 이번 응답에서는 발견 완료 상태를 확인할 수 없습니다.
- 내비 배경: 현대자동차그룹이 공개한 Pleos 홍보 화면을 활용한 배치 시안입니다. 실제 시험 기기의 SDK 캡처가 아닙니다. 배경의 주행 표시를 주차 상황의 P·0 표시로 바꿔 구성했습니다.
- 지역: 내비 배경은 **서울특별시 강남구**, 적용 공고는 **서울특별시 마포구**입니다. 같은 시도 조회 범위의 사례이며 같은 구·근거리·실제 목적지 일치를 검증한 결과가 아닙니다.
- 구현: 내비 전체 화면 위 사진 팝업은 아직 구현되지 않은 **제안 UI**입니다. 현재 사진 안내는 마중 앱 영역이나 분할 화면 안에 표시합니다. 실제 SDK 도착·P단 주차·사진 표시까지 이어지는 통합 실행은 미검증입니다.

[실제 공고를 적용한 배치 시안](assets/majoong-pleos-live-case-placement.png)은 1920×1080입니다. 우측 하단 팝업은 576×360으로 전체 화면 면적의 10%입니다. `sample.json`의 `mockup`에 제안 UI임을 뜻하는 `mode: layout_concept`와 규격을 기록했습니다.

[출처·사진 해시·조회 범위](provenance.json)에 실제 API 조회와 시안 구성의 증빙을 기록했습니다.

[공식 공고 게시판](https://www.animal.go.kr/front/awtis/loss/lossList.do?menuNo=1000100000) · [Pleos 홍보 화면 출처](https://www.hyundaimotorgroup.com/ko/news/hyundai-motor-group-redefines-in-vehicle-experience-with-pleos-connect-next-generation-infotainment-system)

## 정적 안내 화면 열기

저장소 루트에서 실행합니다. 인증키나 기존 API 서버를 사용하지 않는 별도 정적 서버입니다.

```sh
python3 -m http.server 18791 --bind 127.0.0.1 --directory review
```

`http://127.0.0.1:18791/`을 엽니다. 종료는 이 명령을 실행한 창에서 Ctrl+C입니다. GitHub의 HTML 파일 미리보기는 앱 실행 화면이 아닙니다. 실제 API를 다시 조회하는 실행 방법은 저장소 루트의 README를 참고하세요.

## 공개용 기록 형식

`sample.json`은 `validateSnapshot()`을 통과한 필드만 표시합니다. `dataMode: api_snapshot`은 이 파일이 시각을 기록한 조회 자료임을 뜻합니다. 실제 API 요청을 대신하지 않습니다.

| 필드 | 내용 |
| --- | --- |
| `capturedAt` | 실제 조회 시각, 시간대를 포함한 ISO8601 |
| `region` | 조회한 행정구역 |
| `cases[].reference` | 원본 식별자를 해시한 공개용 참조값 |
| `cases[].species` / `breed` | 실제 제공된 동물 종류·품종 |
| `cases[].reportedDate` | API `happenDt`의 실종일 `YYYY-MM-DD`, 미제공이면 `null` |
| `cases[].region` | 조회 지역 안에 속하는 공고 행정구역 |
| `cases[].features` | 공개 가능한 구조화된 털색·성별만 사용 |
| `cases[].photo` / `photoSha256` | 로컬 사진 경로와 공개 파일의 SHA-256 |
| `mockup` | 시안 파일·규격·면적비·지도 지역. 준비되지 않았으면 `null` |

사진은 API에서 받은 실제 파일이며 EXIF/GPS 등 메타데이터를 제거한 공개 사본입니다. 픽셀 내용은 바꾸지 않았습니다. 새 자료를 넣을 때에도 실제 `mode=live` 응답인지 확인하고 허용 필드만 기록해야 합니다. 연락처·정확한 주소·인증키·원본 사진 URL·원문 API 응답을 넣지 않습니다. 형식 검증은 사진과 문구의 개인정보 확인을 대신하지 않습니다.

확인되지 않은 자료는 `state: unavailable`, `capturedAt: null`, `region: null`, `cases: []`, `mockup: null`로 둡니다. 가상 공고나 사진을 실제 자료처럼 채우지 않습니다.

`web/`과 Android APK 자산은 수정하지 않았습니다. 이 폴더는 심사용 조회 기록과 제안 UI를 구분해 보여 주는 제출 보조 자료입니다.
