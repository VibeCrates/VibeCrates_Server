# AWS 화면 부하 테스트 — 1단계와 2단계 실행 절차

1단계는 로컬 Docker에서 계약과 측정기를 검증한다. 이 문서를 작성하며 AWS 리소스 생성·기동, Terraform apply,
ECS 배포, 커밋·push는 하지 않았다. 기존 시나리오·라이브러리·시딩의 동작도 바꾸지 않았다.
로컬 실측은 [검증 기록](aws-validation.md), 개선 전 실험은 [diff 설명](../scratchpad/aws-before-recalculation.md)에 있다.

## 먼저 실행할 것

```bash
# 기존 compose·기존 시딩은 그대로 쓴다. 값이 찍히므로 compose config / env / set -x는 쓰지 않는다.
docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build
SEED_VERSION=2 ./load-test/prepare.sh 4.625 1 0.6 1.0 1.5417

# 시스템 k6는 덮어쓰지 않는다. Docker Redis가 켜져 있어야 설치 뒤 DEL 검사까지 끝난다.
REDIS_URL=redis://localhost:6379/0 ./load-test/install-aws-k6.sh
export K6_BIN="$PWD/load-test/results/tools/k6"

# 독립 준비 확인. 스윕도 같은 준비를 자동으로 하므로 필수 선행 명령은 아니다.
./load-test/prepare-aws.sh --new-users 20000 --rate 2 --seconds 45

# 실제 로컬 검증. 결과를 새 디렉터리에 남긴다.
REDIS_URL=redis://localhost:6379/0 ./load-test/run-aws-sweep.sh 2 4 \
  --seconds 45 --warmup-seconds 15 --refine-repeats 0
```

`crates_server.env`는 기존 로컬 스크립트처럼 프로세스 환경으로만 읽는다. 해당 파일의 비밀번호·키·토큰은
출력하거나 다른 파일에 복사하지 않는다. API로 새로 받은 테스트 로그인·서명 토큰은 `results/` 안에 권한 600으로
둔다. 이 디렉터리는 기존 `.gitignore`가 제외한다. 토큰을 포함한 stage/users/catalog 파일도 외부 공유하지 않는다.

## k6·Redis 버전과 TLS

2026-11-07 시스템 k6 v2.2.0에서 `import redis from 'k6/x/redis'`를 직접 실행했다.
자동 확장 해석이 **k6 v2.3.0 + xk6-redis v0.4.5**를 내려받았고 로컬 Redis에 SET → DEL(1) → EXISTS(0)가 성공했다.
처음 무제약 검사가 실행 버전까지 올렸으므로 실제 두 새 시나리오는 아래 지시문으로 둘 다 고정한다.

```javascript
"use k6 = 2.3.0";
"use k6 with k6/x/redis = 0.4.5";
import redis from 'k6/x/redis';
```

`install-aws-k6.sh`는 macOS arm64/amd64, Linux arm64/amd64에서 공식 v2.3.0 배포물과 공식 SHA256 목록을
받아 검증한다. 기본 설치 위치는 `results/tools/k6`다. 확장 바이너리는 최초 실행 때 자동 조달하여 캐시에 둔다.
EC2에서도 측정 전에 다음을 먼저 실행해 다운로드·확장 로딩·VPC 접속을 측정 시간 밖에서 끝낸다.

```bash
# 호스트 이름으로 인증서를 검증한다. IP 주소나 검증 생략 옵션을 쓰지 않는다.
export REDIS_URL='rediss://ELASTICACHE_PRIMARY_DNS:6379/0'
./load-test/install-aws-k6.sh
./load-test/results/tools/k6 deps --json load-test/scenarios/aws-redis-smoke.js
```

인증이 있는 Redis라면 URL의 username/password는 외부에서 환경 변수로 주입하고 명령 이력·결과에 남기지 않는다.
현재 Terraform ElastiCache는 단일 primary, TLS, Redis OSS 7.1이다. 클러스터 모드가 켜진 구성의 여러 샤드에는
이 단일 URL 경로를 그대로 쓰지 않는다. `rediss://`를 `redis.Client`에 그대로 전달하며 공개 CA 검증을 유지한다.
**로컬 Redis는 TLS가 아니므로 ElastiCache TLS·보안 그룹·인증 검증은 2단계에 남아 있다.**

자동 조달이 막힌 망에서는 Docker로 같은 버전을 교차 빌드한다. 이 대안은 로컬에서 실제 빌드하지 않았다.
Go가 없는 현재 맥에는 설치할 필요가 없었다. 대안은 호스트 Go 설치 대신 고정 Docker Go 1.26.5,
xk6 v1.4.5를 사용하므로 두 OS에서 툴체인도 맞춘다.

```bash
./load-test/build-aws-k6.sh darwin arm64
./load-test/build-aws-k6.sh linux arm64      # EC2 Graviton
./load-test/build-aws-k6.sh linux amd64      # EC2 x86
K6_AUTO_EXTENSION_RESOLUTION=false load-test/results/tools/k6-darwin-arm64 version
K6_AUTO_EXTENSION_RESOLUTION=false load-test/results/tools/k6-darwin-arm64 run load-test/scenarios/aws-redis-smoke.js
```

빌드 머신은 Go 모듈과 Docker 이미지에 닿아야 한다. 바이너리를 폐쇄망으로 옮긴 뒤에는 자동 조달을 끌 수 있다.
버전 변경은 지시문, 설치/빌드 스크립트, 실행 기록을 함께 바꾼다.
[공식 자동 조달 설명](https://grafana.com/docs/k6/latest/extensions/run/),
[확장 v0.4.5 소스](https://github.com/grafana/xk6-redis/tree/v0.4.5),
[Redis Client TLS 계약](https://grafana.com/docs/k6/latest/javascript-api/k6-x-redis/client/).

## 무엇을 보내나

비율의 단일 원본은 `aws/shares.json`이다. Python 준비기와 k6가 같은 파일을 읽는다.
화면은 홈 68%, 검색 결과 21%, 보관함 10%, 신규 취향 선택 1%다.

| 행동 | 화면 100회당 | 경로 / 배정 |
|---|---:|---|
| 포스터 상세 | 30 | 실제 id·contentType, 종류별 334개 풀 |
| 추천 좋아요 | 18.5 | POST /api/boards/likes, 준비한 서명 토큰 |
| 저장 보드 좋아요 | 27.75 | POST /api/boards/{id}/likes, 아직 안 누른 쌍 |
| 검색 좋아요 | 15.42 | POST /api/search/board/likes, 준비한 서명 토큰 |
| 취소 | 6.17 | 행동 사용자가 좋아요한 남의 보드, 수정 풀·삭제 위험 capture 대상 제외 |
| 남의·추천 보드 저장 | 10 | 저장된 원본 PUT / 저장 전 추천 POST를 절반씩 |
| 내 보드 수정 | 3.33 | 본인 USER_CUSTOM에 PUT, id 유지 확인 |
| 교체용 제목 검색 | 5.33 | 3글자 이상, page=0&size=20 |
| 사진 저장 기록 | 2 | POST /api/logs/board-capture, 실제 원본 id·제목·8개 id |
| 내 보드 삭제 | 0.5 | 본인 USER_CUSTOM에 DELETE |

홈은 기존 `screens.js`처럼 온보딩 상태 확인 후 `http.batch`로 추천·인기 8개·내 보드 8개를 동시에 요청한다.
검색 결과는 보드 1개, 보관함은 ALL/page=0/size=20이다. 신규 취향 선택은 프론트
`OnboardingPage.tsx`·`useOnboardingContents`처럼 BOOK → MOVIE → MUSIC 탭을 순차 방문하고 각 첫 후보를
하나씩 선택한 뒤 initial-contents를 보낸다. OAuth와 로그인은 화면 시간에 포함하지 않는다.

저장·수정은 반복 5번마다 배치 3 / 제목 1 / 콘텐츠 1이다. layout 8개에 x,y 0~100, rotation -180~180,
z 1~8을 한 번씩 쓴다. 콘텐츠 교체는 기존 8개에 없는 실제 콘텐츠 하나를 선택한다.
새 저장과 내 보드 수정 모두 제목 모드에서만 제목을 바꾼다. 배치만 저장은 BOARD_EDIT를 남기지 않는다.
중복 거절은 (행동 사용자, 원본의 제목·정렬한 구성) 예약으로 피한다. 기존 소유 보드와 예약된 저장 결과도 제외한다.
저장/수정의 교체용 제목 검색은 표의 비율대로 독립 도착하며, 교체 요청마다 검색 2번을 추가로 붙여 중복 계상하지 않는다.
콘텐츠 상세는 전체 183,136개에서 종류별 비율을 맞춰 고정 난수로 뽑은 6,000개를 순회한다.
capture는 준비 때 상세를 받은 공개 원본 256개를 순환한다. 두 읽기 커서도 준비 디렉터리에서 단계·재실행을 이어 간다.

## 계정·토큰·일감

기존 1,200명 중 짝수 번호 600명은 화면, 홀수 번호 600명은 행동이다. 짝/홀은 각 코호트에서 반씩 뽑는다.
좋아요 뒤 행동 사용자의 벡터 버전이 바뀌어도 화면 사용자의 캐시는 무효가 되지 않는다.
짧은 저부하 검증도 오래된 계정만 고르지 않게 각 풀의 순서를 고정 난수로 섞는다.

별도 `loadtest_new0`부터 **20,000명**을 SQL로 만든다. LOCAL이며 `user_vector`가 없고, 기존 seed 계정의
BCrypt 해시를 복사해 테스트 비밀번호를 맞춘다. 재실행으로 이미 취향을 고른 계정을 초기화하지 않는다.
스윕 전에 모든 지정 단계 + 워밍업 + 포화 정밀 반복 + 구간별 예비 일감의 최악 예산을 합쳐 잔량을 검사한다.
신규 계정뿐 아니라 unlike, stored_like, save_board 원본 쌍, 수정·삭제 대상, 추천·검색 토큰 쌍, 자연어 검색어를
`budget.json`에 기록한다. 사용자별 배정량도 검사하여 전체 잔량만 충분한 경우를 거른다. 부족하면 첫 부하 전에 멈춘다.
신규 20,000명이 모자라면 `--new-users`로 늘린다. 매 단계는 미선택·미예약 계정만 새로 로그인한다.
`--budget-only`는 토큰 후보를 추가로 만들지 않고 현재 DB·예약 장부·준비된 토큰으로 예산만 검사한다.
`--prepare-only`는 전체 예산에 필요한 서명 토큰까지 확보한 뒤 끝낸다. 워밍업과 k6를 실행하지 않는다.
실제 스윕도 이 전체 준비를 먼저 끝내므로 구간 준비에서 AI 토큰을 추가 수집하지 않는다.
`token-preparation.json`은 종류별 요청 수·후보 수·대기 간격·경과 시간,
`preparation.json`은 로그인·DB 검사·토큰을 포함한 전체 준비 시간을 기록한다.

로그인은 측정 밖에서 동시 4개로 준비한다. 로그인 토큰 수명은 1시간이므로 30분 지난 풀은 갱신하고, 한 구간은
최대 1,200초로 제한한다. 추천·검색 likeToken에는 만료 검사가 없다(`BoardLikeTokenService`). 서버 서명 키가 바뀌면
토큰을 다시 준비한다. 로그인 토큰과 서명 토큰을 혼동하지 않는다.

서명 토큰은 특정 사용자에 묶이지 않는다. 홈 추천은 섞인 행동 사용자 풀의 응답을 순회하며 필요한 예산을 채우고,
검색 응답도 필요한 보드 수만 받는다. 두 수집 모두 직전 응답 이후 기본 1초를 기다린다
(`RECOMMEND_PREP_INTERVAL`, `SEARCH_PREP_INTERVAL`). 한 보드의 토큰을 최대 600명의 서로 다른 사용자와 배정한다.
이미 준비된 후보가 사용자별 전체 예산에 충분하면 추가 요청은 하지 않는다.
기존 좋아요의 (사용자, 종류, 제목, 정렬한 콘텐츠 구성)을 DB로 확인하고 `reserved.json`에 배정을 기록해 같은 사용자·보드를
다시 좋아요하지 않는다. 응답이 liked=true인지도 검증한다. 빈 풀·부족한 쌍·권한 대상 부족은 측정 전에 멈춘다.
취소 후보는 행동 사용자의 실제 LIKE 중 그 사용자 자신의 보드가 아닌 모든 종류다.
행동 풀의 수정·삭제 대상 전체와, 기존 capture 파일 중 안정 원본이 아닌 보드를 보호해 취소 후보에서 뺀다.
화면 풀 소유의 살아 있는 USER_CUSTOM은 좋아요 0에서도 삭제되지 않는다. 좋아요 수가 바뀌어도
stored_like의 새 LIKE, 저장의 제목·구성·배치, capture의 접근 권한에는 영향이 없어 후보에 유지한다.
홈·검색 토큰은 제목·구성을 담으므로
원래 DB 보드가 삭제돼도 토큰 좋아요는 보드를 재생성하고, POST 저장도 DB 원본 id에 의존하지 않는다.
종류별 실제 후보 쌍 비중으로 가중 순회하고, 종류 안에서 섞인 사용자를 순회해 앞 사용자에게 몰리지 않게 한다.
`budget.json`의 `unlike_pool`에 종류별 잔량·사용자 수·보호한 보드 수를 기록한다.
`--verify-unlikes`는 로컬 계약 검증용으로 응답·BOARD_UNLIKE·피드백 제거를 대조하고,
마지막 좋아요 취소 시 board와 board_item의 실제 삭제를 구간 뒤에 검사한다.

원본 저장/캡처와 저장 보드 좋아요는 화면 풀 소유의 공개 USER_CUSTOM을 쓴다. 행동 풀의 수정·삭제 대상과 겹치지 않아
측정 중 원본이 사라지지 않는다. 수정과 삭제는 예약 장부로 단계·재실행을 가로질러 서로 다른 소유 보드를 배정한다.
추천·검색 좋아요, 저장된 보드 좋아요, 취소는 서로 다른 요청 경로로 따로 기록한다.
이 측정은 쓰기를 되돌리지 않으므로 캐시 on/off 비교는 같은 초기 DB·Qdrant 상태로 복원한 뒤 새 결과 디렉터리에서 한다.

## 캐시를 어떻게 맞추나

캐시 on은 화면 600명을 측정 전에 추천 API로 한 번씩 워밍업한다. 홈은 온보딩 상태를 조회한 뒤 같은 사용자의
`loadtest:home-lock:{userId}`(NX/PX 60초)를 잡고, 50% 확률로 `reco:v1:{userId}`만 DEL한다.
잠금 대기·DEL 시간은 `screen_duration` 계산에서 빼고 `cache_control_duration`에 별도로 남긴다.
같은 계정의 추천 구간을 직렬화하고 세 API를 동시에 호출한 뒤 잠금을 푼다. 잠금 대기 60초 초과와 Redis 제어 오류는
측정 무효다. SET NX 경합은 Lua로 0/1을 반환하며 단계 종료 때 해당 실행이 소유한 잠금을 정리한다.
이 잠금·DEL 자체의 Redis 부하는 실제 사용자 요청 외에 추가되므로 Redis가 포화 자원일 때 따로 해석한다.

검색은 기존 `search-keywords.js`의 상황·분위기·주제를 사용한다. 식별자·숫자 꼬리는 붙이지 않는다.
`search-cursor.json`에 다음 번호를 먼저 기록하며 측정·토큰 준비·재실행이 500,000개의 고유 조합을 나눠 쓴다.
조합이 모자라면 준비 중 실패한다. 앞부분의 꼬리는 없음:영화:노래:책=4:1:1:1이고, 빈 꼬리 125,000개가 소진되면
남은 종류 꼬리를 순환한다. 요청 직전 `emb:v1:{정규화된 검색어}`를 DEL하며 이 시간은 화면 측정 밖이다.
키는 앱의 NFC → Java strip → ASCII 공백 연속 축약과 맞춘다. `aws-embedding-probe.js`는 같은 검색어의
DEL 없는 miss/hit, 매 요청 전 DEL한 miss/miss를 실제 앱 카운터로 확인한다.

적중률은 응답 시간 추정 대신 앱 카운터의 단계 전후 차이로 계산한다. 추천은
`crates_recommendation_cache_total{result="hit|miss|error"}`, 검색은 `crates_ai_embedding_cache_total`이다.
성공한 캐시 조회 중 hit/(hit+miss)를 기록하고 error도 함께 남긴다. AWS는 모든 backend 작업의 차이를 합친다.
태스크 목록 변경·카운터 감소(재시작)는 무효다. 캐시 off는 앱 카운터가 늘지 않아 hit_rate=null로 표시하며 0% 실측으로
오해하지 않는다. 확률이므로 짧은 실행이 정확히 50%일 수는 없다. 외부 사용자가 같은 앱을 쓰면 카운터가 섞인다.

## 스윕·판정·결과

기본 RATE는 **10,20,40,80,160,320,640**, 각 단계 전 별도 **30초 워밍업**, **120초 측정**이다.
각 시나리오의 기존 최대 VU를 사전 할당해 측정 중 VU 초기화로 생기는 drop을 방지한다.
배수 증가는 포화 위치를 적은 단계로 찾고, 별도 워밍업은 지표에서 초반을 빼는 계산 오차를 없앤다.
짧은 구간의 정상 상태 검증이므로 긴 JVM 워밍업이 필요하면 `--warmup-seconds 120` 이상으로 늘린다.
120초는 희귀 행동도 표본을 남기면서 CloudWatch의 1분 표본을 두 개 이상 볼 수 있는 출발값이다.

도착률은 1만 초 단위의 정수로 표현한다. 낮은 RATE에서 0.5% 행동을 무조건 1/min으로 키우지 않는다.
구간별 실제 건수는 ceil이므로 희귀 행동은 짧은 검증에서 상대 비율 오차가 크다. 정상 비율 판단은 긴 실행으로 한다.
단계 경계에 맞물린 도착을 위해 시나리오별 예비 일감 1개도 준비하며, 이 예비 수를 처리량 분모로 더하지 않는다.
`RATE`는 초당 화면 진입이며, 모든 행동까지 합치면 반복은 약 2.19×RATE, API는 약 4.26×RATE다.
이는 OAuth·이미지·정적 자산 전송이 빠진 API 부하다.

- 목표: 화면·일반 API p95 <500ms, 검색 화면/API <1,500ms, API 실패 <1%. 넘겨도 계속 올린다.
- 포화: API 실패 **5% 이상**, **dropped_iterations >0**, 또는 구간 안 완료 반복/예정 반복이 **90% 미만**.
  30초 drain에서 끝난 요청을 구간 안 처리량으로 더하지 않는다.
- 포화가 나오면 앞 RATE / 중간 RATE / 포화 RATE를 기본 **각 3회** 반복한다.
  첫 단계가 포화면 절반 RATE를 아래 경계로 쓴다. 새 계정과 좋아요 쌍을 계속 소비하므로 각 반복도 다시 준비한다.
- 지정한 최대 RATE까지 포화가 없으면 그 사실을 출력한다. 관측하지 않은 상한을 찾았다고 주장하지 않는다.
- Redis 제어 오류, 일감 부족, 누락된 시나리오, 앱 카운터 초기화는 서버 포화와 구별한 **측정 무효**다.

인자: `--seconds`, `--warmup-seconds`, `--refine-repeats`, `--new-users`, `--output`, `--cache-mode on|off`,
`--resources local|aws|none`, `--skip-ai`. AI가 안 닿을 때는 검색 화면·검색 좋아요만 빼며 나머지 비율을 키우지 않는다.
그 경우 실제 화면 부하는 원래 RATE의 79%여서 완전한 스윕과 비교하면 안 된다.
`--verify-mutations`는 요청별 저장 id와 DB의 BOARD_EDIT를 비교해 제목·콘텐츠는 한 행, 배치는 0행임을 검사한다.
`--prepared-directory`로 같은 BASE_URL·같은 시딩 환경의 준비 디렉터리를 이어 쓸 수 있다.
로그인·서명 토큰과 중복 배정 장부를 재사용한다. 다른 환경이나 새 초기 데이터 비교에는 새 준비 디렉터리를 쓴다.

각 하위 디렉터리에 `summary.json`(API별 p50/p95/p99·건수·실패, 화면 시간, drop), `result.json`(목표·포화·처리량·캐시),
`k6.log`, `stage.json`, `cache-before/after.json`, `pgss.json`, `window.json`을 남긴다.
스윕 전체는 `results.json`, `config.json`으로 모으고, local은 기존 `sampler.sh`의 `resources.csv`와 별도 앱 `app.jsonl`,
`limits.txt`를 남긴다. 기존 샘플러의 최초 로그인도 측정 전에 끝낸다. 실패 API는 본문·JWT 없이 이름과 상태만 로그에 적는다.

## 2단계 AWS 실행 입력

부하 생성기는 같은 리전의 별도 **c7g.xlarge(4 vCPU/8GiB)**를 출발점으로 제안한다. 준비기의 대량 JSON과 다수 VU를
서버와 분리하고 T 계열 CPU 크레딧의 영향을 피하려는 선택이다. 아주 낮은 RATE 확인은 c7g.large(2/4)도 가능하다.
k6 CPU·메모리·네트워크와 drop을 확인하고 생성기가 먼저 차면 더 큰 머신에서 같은 단계를 다시 잰다.
인스턴스는 이번 단계에서 만들지 않았다.
[C7g 사양](https://aws.amazon.com/ec2/instance-types/c7g/).

| 출발 → 도착 | 포트 | 이유 |
|---|---|---|
| k6 → 서비스 CloudFront 도메인 | TCP 443 | BASE_URL, ALB 직접 우회 금지 |
| k6 SG → ElastiCache SG | TCP 6379 | rediss, 추천·검색 DEL·잠금 |
| k6 SG → RDS SG | TCP 5432 | 준비 읽기, pg_stat_statements reset/조회, TLS |
| k6 → AWS API/SSM·모듈 공급망 | TCP 443 | CloudWatch·ECS Exec·설치, NAT 또는 필요한 VPC endpoint |
| 관리 경로 → Qdrant | TCP 6333(임시) | 스냅샷·검증, SSM 포워딩으로 한정 가능 |

기존 ALB는 CloudFront 관리 prefix list만 받고 backend 8080도 ALB에서만 받는다. k6용 8080 ingress는 필요 없다.
Qdrant 6334와 AI 8000은 backend 경로이며 k6가 직접 AI를 부르지 않는다. SSH를 열기보다 SSM을 쓴다.
SG는 위 참조 규칙을 2단계에서 추가해야 한다. 현재 Terraform에는 k6 → Redis/RDS 허용이 없다.
CloudFront /api 캐시는 기존 disabled 정책을 유지한다. 403/429가 보이면 WAF·레이트 제한·네트워크를 먼저 구별한다.

```bash
# 값은 2단계에서 실제 출력으로 채운다. 이 파일에 자격 증명을 적지 않는다.
export RESOURCE_MODE=aws AWS_REGION=ap-northeast-2
export BASE_URL=https://SERVICE_DOMAIN
export REDIS_URL=rediss://ELASTICACHE_PRIMARY_DNS:6379/0
export PGHOST=RDS_DNS PGPORT=5432 PGDATABASE=crates PGUSER=crates_admin
export PGSSLMODE=verify-full PGSSLROOTCERT=/path/to/rds-ca-bundle.pem
# PGPASSWORD는 안전한 세션 환경으로 주입한다. 로컬 crates_server.env는 EC2로 복사하지 않는다.
export ECS_CLUSTER=CLUSTER ECS_SERVICE=backend ECS_CONTAINER=backend
export RDS_INSTANCE_ID=DB_ID REDIS_CLUSTER_ID=CACHE_CLUSTER_ID REDIS_NODE_ID=0001
export QDRANT_INSTANCE_ID=i-QDRANT
export K6_BIN="$PWD/load-test/results/tools/k6"
./load-test/run-aws-sweep.sh --resources aws

# CloudWatch가 아직 도착하지 않았다면 잠시 뒤 저장한 동일 시간 창을 다시 조회한다.
./load-test/run-aws-sweep.sh --collect-cloudwatch load-test/results/aws-RUN --resources aws
```

Redis replication group id와 CloudWatch `CacheClusterId`는 다를 수 있다. 실제 primary 노드의 cluster/node id를 넣는다.
CloudWatch 읽기 권한, ECS list-tasks/execute-command, SSM 세션 권한·Session Manager plugin이 필요하다.
수집은 리소스를 생성하지 않는다. RDS는 pg_stat_statements를 미리 활성화하고 reset 권한으로 사전 확인한다.

수집기는 AWS/ECS CPUUtilization·MemoryUtilization, AWS/RDS CPUUtilization·DatabaseConnections·ReadIOPS,
AWS/ElastiCache CPUUtilization·EngineCPUUtilization, AWS/EC2 Qdrant CPUUtilization을 aws cli get-metric-data로 받는다.
평균·최대, 정확한 dimension, 분 경계 조회 창을 함께 저장한다. AWS 기본 표본은 ECS 1분이며 EC2 기본은 5분일 수 있어
Period=60만 지정해도 고해상도가 생기지 않는다. CloudWatch 지연·빈 표본은 0으로 채우지 않는다.
[ECS 지표](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/cloudwatch-metrics.html),
[ElastiCache 지표](https://docs.aws.amazon.com/AmazonElastiCache/latest/dg/CacheMetrics.Redis.html).

Hikari 대기·획득·타임아웃과 캐시 카운터는 모든 backend 작업 안에서 ECS Exec로 localhost actuator를 읽는다.
`ecs-metrics.sh`는 컨테이너 안에서 테스트 계정으로 한 번 로그인하고 2초마다 수집하여 JWT가 명령·출력에 노출되지 않는다.
각 작업의 `.promlog`와 단계 경계 카운터를 남긴다. 로컬 backend 이미지에서 curl·sed·date 존재와 수집 스크립트 실행을 확인했다.
실제 ECS 이미지의 동일 도구와 ECS Exec 연결은 2단계에서 확인한다.
2초보다 짧은 피크는 놓칠 수 있다. 더 촘촘히 보려면 앱 scrape 1초, Qdrant 호스트 pidstat/docker stats 1초,
RDS Enhanced Monitoring 1초(추가 설정·비용), Redis INFO cpu 1~2초의 누적 CPU 차이를 별도로 쓰는 방법이 있다.
pg_stat_statements는 단계 전 reset 후 상위 20개를 저장한다. 운영 동시 트래픽이 있으면 reset과 앱 카운터 모두 섞인다.
이 AWS 수집 경로는 로컬에서 CLI 구문·로직을 검토했으며 실제 AWS 연결 검증은 2단계다.
[ECS Exec](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/ecs-exec.html).

## 2단계 데이터 이관

캐시 on/off마다 동일한 출발점이 필요하므로 로컬을 다시 시딩 v2·재계산한 뒤 **측정 전** 덤프를 만든다.
이번 검증에서 누적된 쓰기 데이터를 기준으로 사용하지 않는다. 신규 계정은 SQL 재실행으로 추가할 수 있다.

```bash
# 로컬에서만 env를 읽는다. -e PGPASSWORD는 이미 export한 값을 Docker에 전달한다.
set -a; . ./crates_server.env; set +a
export PGPASSWORD="$POSTGRESQL_PASSWORD"
docker exec -e PGPASSWORD postgres_server pg_dump -U "$POSTGRESQL_USERNAME" -d crates \
  -Fc --no-owner --no-acl > load-test/results/crates-seed-v2.dump
unset PGPASSWORD

# 전용 빈 RDS 테스트 DB에 복원한다. 접속 환경/CA는 앞 절의 2단계 값을 사용한다.
pg_restore --exit-on-error --no-owner --no-acl --jobs=2 --dbname=crates load-test/results/crates-seed-v2.dump
psql -X -v ON_ERROR_STOP=1 -c 'VACUUM ANALYZE;'
```

dump는 테이블·해시 등 테스트 데이터를 포함하므로 비공개 경로로 전달하고 체크섬을 비교한다. 확장·롤·슈퍼유저 권한은
RDS와 달라 복원 목록(`pg_restore --list`)을 먼저 확인한다. RDS parameter group에 pg_stat_statements preload가 있고
재부팅 적용됐는지 `SHOW shared_preload_libraries`·`CREATE EXTENSION IF NOT EXISTS pg_stat_statements`로 확인한다.
중간 실패를 무시한 복원을 측정에 쓰지 않는다. 기존 데이터가 있는 DB를 통째로 지우는 명령은 이 절차에 넣지 않았다.
[RDS PostgreSQL 가져오기](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Procedural.Importing.html),
[RDS SSL](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/PostgreSQL.Concepts.General.SSL.html).

Qdrant는 `content_vector`, `query_vector`를 같은 버전 Qdrant의 컬렉션 snapshot으로 옮기거나 동일 원본 CSV로 적재한다.
`content` id가 CSV id와 일치해야 한다. 개수만 같아서는 부족하다. `/collections/{name}`의 points_count·벡터 차원·거리·
인덱스 설정·상태 green을 비교하고, 고정 id 최소 종류별 10개를 `/points`(with_vector=true, with_payload=true)로 조회해
벡터·model_version·콘텐츠 id/제목 payload를 비교한다. query_vector도 고정 point id로 같은 검사를 한다.
CSV SHA256·PostgreSQL content의 (id,dtype,title) 정렬 해시·Flyway 버전을 이관 전후 비교해 id 재배정을 잡는다.
서버 시딩의 recreate 옵션이 복원 컬렉션을 덮어쓰지 않게 확인하고 Qdrant가 모두 green인 뒤 워밍업한다.
MinIO/S3 이미지는 이 API 부하의 다운로드 대상이 아니지만 URL 응답과 실제 이미지 E2E 검증은 별도로 남긴다.
[Qdrant snapshot 절차](https://qdrant.tech/documentation/tutorials-operations/create-snapshot/).

## 2단계 Redis 캐시 없음 배포

Redis 자체는 OAuth 임시 토큰에 쓰므로 남긴다. 바꾸는 것은 작업 정의의 두 환경 변수뿐이다.

```text
RECOMMENDATION_CACHE_ENABLED=false
AI_EMBEDDING_CACHE_ENABLED=false
```

`infra/terraform/prod/ecs.tf`는 task_definition 변경을 ignore하므로 새 revision 등록 + 서비스 업데이트 경로로 바꾼다.
아래는 2단계에서만 실행한다. 원래 task ARN을 보관해 같은 이미지·CPU·메모리·비밀 참조를 유지하고 되돌릴 수 있게 한다.

```bash
OLD_TASK=$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
  --query 'services[0].taskDefinition' --output text)
aws ecs describe-task-definition --task-definition "$OLD_TASK" --include TAGS \
  > load-test/results/task-before.json
python3 load-test/aws/cache-task-definition.py false < load-test/results/task-before.json \
  > load-test/results/task-cache-off.json
# 두 변수 외 차이가 없는지 검토한 뒤 등록·배포한다.
NEW_TASK=$(aws ecs register-task-definition --cli-input-json file://load-test/results/task-cache-off.json \
  --query 'taskDefinition.taskDefinitionArn' --output text)
aws ecs update-service --cluster "$ECS_CLUSTER" --service "$ECS_SERVICE" --task-definition "$NEW_TASK"
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE"
# 롤링 교체가 모두 끝났는지 실제 작업 정의와 두 false 값을 확인한다.
./load-test/run-aws-sweep.sh --resources aws --cache-mode off
# 실험 종료 뒤 필요하면 같은 서비스에 OLD_TASK를 지정해 복구한다.
```

캐시 on도 같은 생성기로 true를 쓰거나 원래 정의로 되돌린다. 배포 도중 on/off가 섞인 상태는 측정하지 않는다.
CI 배포를 멈춰 실험 중 revision이 바뀌지 않게 한다. 다음 Terraform/CI의 task 정의 생성이 두 값을 다시 기본으로
되돌릴 수 있으므로 두 값의 적용 위치를 2단계 배포 설정에도 반영한다.
[작업 정의 등록 API](https://docs.aws.amazon.com/cli/latest/reference/ecs/register-task-definition.html).
