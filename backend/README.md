# 로컬 개발 서버

프로젝트 루트에서 `python3 -m backend.server`를 실행하면 `http://127.0.0.1:8787`에서 `web/`을 제공합니다. `--port` 또는 `PORT` 환경변수로 포트를 바꿀 수 있습니다. 서버는 항상 loopback에만 바인딩하며 Android 에뮬레이터의 `http://10.0.2.2:8787` 접근도 허용합니다.

실제 공고는 로컬 프로세스의 `DATA_GO_KR_SERVICE_KEY` 환경변수만 사용합니다. 키가 없으면 실제 조회는 503을 반환하며 자동으로 데모를 보여주지 않습니다. 키를 소스, Git, URL, 터미널 명령 기록에 적지 마세요. 별도의 키 등록 HTTP 엔드포인트는 없습니다.

## API

- `GET /api/health`: `ok`, `keyConfigured`, `uiRevision`. `uiRevision`은 시작 시점 `web/`의 제공 가능한 정적 UI 파일과 로드된 백엔드 코드(`server.py`·`breed_catalog.py`)로 만든 SHA-256이다. 서버 생성 때 고정하므로 실행 중 파일을 바꿔도 새 버전이라고 주장하지 않는다. 경로·키·실공고는 해시에 넣거나 응답하지 않는다. 런처는 값이 없거나 현재 checkout과 다르면 기존 서버를 재사용하지 않고 다른 포트 실행을 안내한다.
- `GET /api/cases?mode=demo&region=경기도`: 가상의 성남 공고 3건. 모든 데모 번호·사진 URL은 비어 있습니다.
- `GET /api/cases?mode=live&region=경기도`: 최근 30일 실제 공고. 시도명은 공식 `lossInfoSido`에서 코드를 조회하여 `upr_cd`로 전달합니다. 시도 코드가 일치하지 않는 지역명은 주소와 관할지의 문자열로만 필터링합니다.
- `region=성남시` 또는 성남시의 구 이름까지 주어진 경우에는 경기도 힌트로 시도 코드를 먼저 조회하고, 공식 `lossInfoSigungu`에 이름이 정확히 일치하는 시군구가 있으면 해당 `org_cd`로 범위를 좁힌 뒤 조회합니다. 정확한 코드가 없으면 경기도 범위 안에서 주소 문자열을 필터링합니다.
- `GET /api/cases?mode=live&sido=...&sigungu=...&page=1`: 명시적인 시도/시군구 코드. `sido`와 `sigungu`는 공식 `upr_cd`, `org_cd` 계약에 대응하며 시군구에는 시도도 필요합니다.
- `GET /api/photo?id=...`: 조회 응답이 제공한 임시 식별자로 공식 사진을 같은 출처에서 제공합니다. 실제 이미지 주소를 클라이언트에 보내지 않습니다.

공고 응답은 `{dataMode, cases, meta}`입니다. 공고마다 `id`, `species` (`dog`, `cat`, `other`), `breed`, `sex`, `age`, `color`, `features`, `reportedDate`, `area`, `photoUrl`, `contact: {number, masked, available, label}`, `status: "unknown"`, `sourceUrl`을 제공합니다. `lostDate`는 초기 화면 계약 호환을 위한 `reportedDate` 별칭입니다. 공식 `happenDt`의 설명은 접수일이므로 화면에는 `신고일`로 표시해야 합니다.

`meta`에는 조회 시각, 기간, 반환 건수, 제공기관 전체 건수, 필터 방식, 범위 제한, 완료 상태 미확인 안내가 있습니다. 한 번에 50건, 페이지는 1~3으로 제한합니다. 공식 시도 또는 시군구 코드로 요청 지역을 정확히 해결했으면 주소를 다시 문자열로 필터링하지 않습니다. 약식 주소도 공식 지역 조회 결과에 포함하기 위해서입니다. 시도 힌트만 해결했거나 코드가 없는 지역의 문자열 필터는 **해당 페이지를 받은 뒤** 적용하므로 결과가 없더라도 해당 지역의 공고가 전혀 없다는 뜻이 아닙니다. `textFilterApplied`로 해당 여부를 확인할 수 있습니다. 이 구현은 목적지 좌표·반경 검색을 하지 않습니다.

품종에 축종 접두사가 없는 경우에는 공식 `lossInfoKind`에 `up_kind_cd=417000` 및 `422400`을 각각 전달해 개·고양이 품종 목록을 조회합니다. 공식 `kindNm` 및 `kindCd`와 정확히 비교하고 결과는 메모리에 24시간 보관합니다. 두 축종에 중복된 품종명, 목록에 없는 값, 조회 실패는 `other`로 남기며 임의로 개로 분류하지 않습니다. `speciesLookupStatus`는 `ready`, `partial`, `temporarily_unavailable`, `not_loaded` 중 하나입니다. 최초 실제 공고가 있을 때 목록 조회 2회가 추가되며, 이후 24시간 동안 다시 조회하지 않습니다.

## 메모리와 사진

응답·사진·키·전화·주소를 파일 또는 접속 로그에 기록하지 않습니다. 공고 응답은 메모리에 5분, 사진 참조는 30분 유지하며 만료 항목은 서버가 실행되는 동안 정리합니다. 사진 바이트 캐시는 최대 32 MiB입니다. 모든 HTTP 응답에 `Cache-Control: no-store`를 적용합니다.

사진은 `animal.go.kr`, `epis.or.kr` 및 그 하위 도메인만 HTTPS로 가져옵니다. 외부 URL 입력이나 리다이렉트, 인증키 쿼리, 쿠키·인증 헤더 전달은 허용하지 않습니다. 사진 하나의 크기는 8 MiB 이하이며 실제 파일 시그니처로 MIME을 판단합니다. 사진 기능에는 Pillow가 필요하며, `verify()`와 `load()`를 수행하고 2천만 픽셀로 제한합니다. Pillow가 없으면 검사 없이 원본을 통과시키지 않고 사진 기능에503을 반환합니다. GIF/WebP 애니메이션은 첫 프레임만 디코딩합니다.

이 API에는 완료·삭제 상태와 공고 고유 ID가 명세에 없습니다. 새 조회에서 사라진 항목은 목록에서 제외하지만 이를 발견 완료로 단정하지 않습니다. 제공되는 `id`는 서버 실행 세션 안에서만 사용하는 임시 식별자입니다.

## 검증

프로젝트 루트에서 `python3 -m unittest discover -s tests/backend -v`를 실행합니다. 테스트는 가상 데이터만 사용하며 실제 API를 호출하지 않습니다. Pillow가 설치된 런타임으로 모든 사진 디코딩 테스트를 실행해야 합니다. 디코더 부재에 대한503도 별도로 검사합니다.

공식 계약 확인 출처: [농림축산검역본부 분실동물 조회 서비스](https://www.data.go.kr/data/15141910/openapi.do)의 공개 Swagger. `lossInfo`의 `upr_cd`/`org_cd`, `lossInfoSido`의 `orgCd`/`orgdownNm`, `lossInfoSigungu`의 `uprCd`/`orgCd`/`orgdownNm`, `lossInfoKind`의 `up_kind_cd` 및 `kindCd`/`kindNm`을 확인했습니다.
