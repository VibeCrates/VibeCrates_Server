# 부하테스트

k6로 부하를 만들고, 앱과 DB가 스스로 내보내는 값으로 내부를 본다.

측정 결과와 발견한 문제는 [docs/load-test-and-metrics.md](../docs/load-test-and-metrics.md)에 있다.

## 세 층을 한 도구로 보려 하지 말 것

| 층 | 재는 것 | 도구 |
|---|---|---|
| ① | 사용자가 겪는 시간 | **k6** |
| ② | 앱 내부 (커넥션 풀 대기, 재계산 소요) | **`/actuator/prometheus`** |
| ③ | 어느 쿼리가 시간을 먹는가 | **`pg_stat_statements`** |

k6가 재는 건 ①뿐이다. DB 시간은 그 숫자 **안에 섞여** 있을 뿐 분리되지 않는다.
"느리다"는 알아도 "왜"는 ②③이 답한다.

## 전제

`k6` 설치(`brew install k6`)와 `crates_server.env`가 저장소 루트에 있을 것.

**앱은 컨테이너로, 측정용 덧씌우기(`docker-compose.loadtest.yml`)를 얹어 띄운다.**

```bash
docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d --build
```

덧씌우기가 하는 일:

| 서비스 | CPU | 메모리 | 그 외 |
|---|---:|---:|---|
| backend | 4 (`BACKEND_CPUS`) | 3g | `loadtest` 프로파일 — SQL 출력만 끈다 |
| postgres | 2 (`PG_CPUS`) | 2g | `shared_buffers=512MB`, `effective_cache_size=1536MB` |
| qdrant | 1 (`QDRANT_CPUS`) | 4g | query 벡터 37만 개를 함께 싣는다 |

상한은 "클라우드에서 자원을 얼마나 써야 하나"를 정하려고 건다. 코어 수 실험은 파일을 고치지 않고
`BACKEND_CPUS=2 docker compose ... up -d backend`로 한다. redis·minio에는 상한을 두지 않는다.

**캐시(Redis)** — 홈 추천 결과와 검색어 임베딩을 캐시한다. API 개별 측정(`api-*`)은 끄고, 혼합 측정(`mixed`)은 켠다.
`run-sweep.sh`가 시나리오와 맞는지 확인하고, 맞지 않으면 띄울 명령을 알려 주고 멈춘다.

```bash
CACHE_ENABLED=false docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d backend  # 개별
docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d backend                      # 혼합
```

개별 측정에서 켜 두면 같은 계정이 반복해 부를 때 첫 요청 뒤로는 캐시만 재게 되어 추천·검색 계산의 비용이 보이지 않는다.
혼합 측정은 운영처럼 캐시를 거친다 — 홈 추천은 좋아요를 눌러야 바뀐다. 혼합은 시작할 때 캐시를 비운다.
커넥션 풀은 측정용 값이 아니라 운영 값이라 `application.yaml`에 있다(10개).

> qdrant와 postgres는 이름 있는 볼륨에 저장한다. 덧씌우기를 적용하거나 상한을 바꿔 컨테이너가 재생성돼도
> 벡터와 시딩한 데이터가 남는다. 처음부터 다시 만들려면 `down -v`로 볼륨까지 지운다.

처음부터 띄울 때(`down -v` 후) 기동에 4~5분 걸린다(콘텐츠 18만 건 시딩 + 이미지 업로드 + Qdrant 적재).

> **query 벡터** — 홈 추천 보드의 제목 후보다. 취향 근처 query 중 서로 다른 4개를 먼저 고르고 그 제목에 맞는 콘텐츠를 담는다(`docs/board-schema.md` 4장).
> AI 개발자가 만든 query 벡터 CSV(book·movie·music) 셋을 기동 때 `query_vector` 한 컬렉션에 넣는다(`QueryVectorLoader`).
> 같은 영어 문장은 한 번만 넣어 372,667개다. 처음 적재는 몇 분 걸리고, 이후 기동은 개수·모델 버전을 확인해 건너뛴다.
> 컨테이너 메모리 합계가 9GB라 **Docker Desktop 메모리를 12GB 이상**으로 둔다.
`docker compose ps`로 `backend_server`가 뜬 것을 확인하고 시작한다.

> `--build`를 붙이는 이유: `postgres/init.sql`(pg_stat_statements 생성)이 이미지에 구워져 있어,
> 캐시된 이미지를 쓰면 확장이 안 만들어진다.

> `./gradlew bootRun`으로 띄우고 재지 말 것. `spring-boot-devtools`가 활성화돼
> 재시작 클래스로더가 끼고, 자원 상한도 없어서 **수치가 재현되지 않는다.**

## 데이터 준비와 부하 생성은 다른 도구다

```bash
./load-test/seed.sh [하루좋아요] [하루보드수정] [인기보드비율] [지프s] [하루검색좋아요]   # 기본 3 1 0.6 1.0 1
```

`seed.sh`가 **측정하려는 상태**를 만들고, k6는 **그 상태에 부하**를 건다. k6는 계정도 보드도
만들지 않는다 — 없으면 "seed.sh를 먼저 돌리라"며 바로 실패한다.

**6개월 동안 운영된 서비스**를 가정해 만든다. 가정과 근거는 `seed.sql` 머리말에 있다.

| 가정 | 값 |
|---|---|
| 가입 | 누적 100 → 200 → 500 → 800 → 1,000 → 1,200명(`seed.sql`의 cohort 표). 매달 1일 가입, 이후 매일 활동 |
| 좋아요 | 하루 3번. 60%는 이미 있는 보드(인기 보드), 40%는 새 AI 추천 보드(좋아요 1번 = AI 보드 1개) |
| 보드 수정 | 하루 1번(콘텐츠를 하나만 바꿔도 "내가 만든 보드"가 된다) |
| 검색 | 하루 2번, 그중 1번에 좋아요(좋아요 1번 = 검색 보드 1개). 검색 보드는 모두 다른 검색어라고 본다(상한) |
| 인기 분포 | 지프 분포 s=1.0. 한 사람은 한 보드에 한 번만 누르므로 보드당 최대 1,200 |

결과는 계정 1,200 / AI 보드 136,800 / 검색 보드 114,000 / 사용자 보드 114,000 / board_item 2,918,400 /
좋아요 456,000이고, 가장 오래 쓴 사용자(측정 계정)는 좋아요 720 / 만든 보드 180이다. 검색 보드가 없던 이전 환경은
마지막 인자를 0으로 준다(보드 250,800 / 좋아요 342,000 / 측정 계정 좋아요 540). 시딩은 30초 안쪽이다.

- **덧붙이지 않는다.** `loadtest_u*` 계정이 남아 있으면 멈춘다 — `cleanup.sql`부터.
- **같은 인자면 같은 데이터다.** 난수 씨앗을 고정했다.
- **보드 콘텐츠는 무작위 8개 조합이다.** 예전 슬라이딩 윈도우는 서로 다른 보드를 약 18만 개까지만 만들 수 있었다.
- **모든 행을 시간 순서대로 넣는다.** 한 사용자의 좋아요가 몇 페이지에 몰리면 힙을 찾아가는 쿼리가 실제보다 싸게 나온다
  (2026-09-26 실측: 1만 건이 84페이지에 몰렸다).
- **개수를 기대값과 비교하고, 하나라도 어긋나면 종료코드 1로 멈춘다.**

### 얼마나 크게 만들어야 하나

축마다 의미가 다르다. 전부 키우는 것이 아니라 **재려는 것에 맞는 축**을 키운다.

| 축 | 왜 필요한가 | 권장 |
|---|---|---:|
| `content` | 이미 191,239건 — 그대로 둔다 | 고정 |
| `board` | 수백 행에서는 플래너가 언제나 seq scan을 고른다. 운영 계획을 볼 수 없다 | 20,000+ |
| `board_feedback` | 보관함·재계산 비용이 여기 비례 | 수만~ |
| **1인당 좋아요** | **전체 행 수와 다른 축이다.** gRPC 4MB 버그는 1인당 170건에서 터졌다 | 150~200 |

보드 상한은 `콘텐츠 - 7×stride`다. 콘텐츠 191,239건 기준 약 184,000개까지 만들 수 있다.
보드 40,000개 + board_item 320,080건을 만드는 데 **3.7초** 걸린다.

> 크기로 해결되지 않는 것도 있다. `pg_stat_user_indexes.idx_scan`으로 확인하는 "이 인덱스가
> 실제로 쓰이는가"는 규모와 무관하고, SQL이 아닌 비용(Qdrant 왕복, 벡터 연산)은 데이터를
> 아무리 키워도 `pg_stat_statements`에 나타나지 않는다.

대부분을 SQL로 꽂는다. API로 좋아요 200건을 만들면 요청 200번 + 비동기 재계산 200번이라
수십 초가 걸리고, **준비 자체가 시스템을 데워 측정 조건을 바꾼다.** SQL이면 1초 안쪽이다.

> 기준 계정(`loadtest_seed`) 하나만 따로 만들고 나머지는 거기서 비밀번호 해시와 취향 벡터를 복사한다.
> 회원가입 API가 없어져(가입은 OAuth만) 계정 행은 SQL로 만든다. BCrypt 해시는 pgcrypto가 만든다.
> 취향 벡터는 콘텐츠 벡터가 Qdrant에 있어 SQL로 평균을 낼 수 없으므로, 가입 직후 콘텐츠 선택
> API(`POST /api/users/me/initial-contents`)로 만든다. 비밀번호는 전부 `Loadtest!234`.

**왜 나눴나** — 그동안 k6가 데이터 생성까지 겸했다. 그래서 실행마다 상태가 누적돼 출발점이
달라졌고, 그것이 "개선을 퇴행으로 잘못 읽은" 원인이었다(아래 4-9 참고).

## 실행

```bash
./load-test/prepare.sh                                        # 한 번만: 정리 → 시딩 → 벡터 재계산 → VACUUM ANALYZE
./load-test/run-measure.sh <시나리오> [측정횟수] [--vacuum]
```

`prepare.sh`는 레포지토리 측정과 같은 데이터(`seed.sh 3 1 0.6 1.0 1`, 6개월 누적)를
만든다. 측정마다 돌리지 않는다. backend가 8080에 떠 있어야 한다.

시딩 뒤에 **전원의 취향 벡터를 각자의 좋아요로 다시 계산한다**(`lib/recalc-vectors.sh`).
`seed.sh`는 기준 계정의 벡터를 전원에게 복사하는데, 그대로 재면 두 가지가 틀어진다.
1,200명이 같은 벡터로 추천을 검색해 결과와 캐시가 한곳에 몰리고(실제보다 좋게 나온다),
쓰기 측정의 좋아요가 재계산을 일으켜 벡터를 바꾸므로 측정 순서에 따라 결과가 달라진다.
앱의 재계산을 그대로 쓰려고 계정마다 좋아요 → 취소를 보내고 각 재계산이 끝나기를 기다린다.
끝나면 좋아요와 `like_count`는 그대로이고 벡터만 맞춰진다. 레포지토리 측정만 할 때는 `SKIP_RECALC=1`로 건너뛴다.

`run-measure.sh`는 **준비 확인 → 초기화 → 워밍업 1회(버림) → 측정 N회 → 중앙값**을 한다.
초기화는 `pg_stat_statements_reset()`뿐이고(`--vacuum`이면 `VACUUM ANALYZE`도), **데이터는 지우지 않는다.**
읽기 시나리오는 상태를 바꾸지 않고, 쓰기 시나리오는 좋아요→취소를 짝으로 보내 상태를 유지한다.

회차마다 `load-test/results/<시나리오>-<시각>/`에 k6 요약(`runN.json`)과 부하 중 자원 기록(`runN.csv`)이 남는다.
자원 기록은 `lib/sampler.sh`가 2초마다 backend·postgres·qdrant·k6의 CPU와 커넥션 풀 active/pending을 적은 것이고,
표에는 회차별 최대값이 함께 나온다. 처리율이 더 오르지 않을 때 어느 자원이 먼저 찼는지를 여기서 판정한다.
**k6 CPU가 높으면 서버가 아니라 부하 생성기의 한계다** — k6는 같은 맥에서 돈다.

환경변수: `BASE_URL`, `MAX_CONTENT_ID`(기본 183136).

### 왜 이런 모양인가

**워밍업** — 첫 실행은 정상 상태보다 **50% 느리다.** JVM JIT 때문이고 DB를 비워도 안 풀린다.
같은 조건 7회 반복 실측:

```
1회 104.0/s   2회 154.9   3회 173.2   4회 197.2   5회 197.3   6회 206.7   7회 209.7
```

4회차부터 수렴한다. **앱을 재시작하면 다시 식는다**(재시작 직후 113.3).

**반복과 중앙값** — 정상 상태에서도 실행 간 ±6%가 흔들린다. 1회 측정으로는 아무것도 말할 수
없다. 스크립트가 퍼짐을 함께 출력하니, **두 설정의 차이가 퍼짐보다 작으면 유의하지 않다.**

**처리율은 `iterations.rate`** — `http_reqs`에는 `setup()`의 회원가입·로그인이 섞여 약 10%
부풀려진다(실측 134.6 vs 122.4).

**준비 확인** — `/actuator/health` 응답은 측정 준비 완료가 **아니다.** Spring Boot는
`ApplicationRunner`를 "Started" 로그 이후에 돌리고, `seed completed` 이후에도 Qdrant가
HNSW를 백그라운드로 짓는다(실측 35초, CPU 815%). 스크립트가 Qdrant CPU가 20% 아래로
떨어질 때까지 기다린다.

## 느린 쿼리·메서드 찾기 (부하가 필요 없다)

**이건 부하테스트가 아니다.** 요청 몇 번이면 된다. `pg_stat_statements`는 누적 집계라
6요청만으로도 어느 쿼리가 비싼지 드러난다.

```bash
# 1. 상태 준비
./load-test/seed.sh            # 6개월 누적 기본값 (먼저 cleanup.sql)

# 2. 누적 초기화
docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  psql -U $POSTGRESQL_USERNAME -d crates -c "SELECT pg_stat_statements_reset();"

# 3. 재고 싶은 엔드포인트를 몇 번 호출   ← 이 단계가 빠지면 아무것도 안 잡힌다
TOKEN=$(curl -s -X POST localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"loginId":"loadtest_u0","pwd":"Loadtest!234"}' | python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])")
for i in 1 2 3; do curl -s -o /dev/null -H "Authorization: Bearer $TOKEN" \
  localhost:8080/api/boards/recommendation/user; done

# 4. 읽기 (아래 "읽는 법" ③)
```

**3단계를 빠뜨리기 쉽다.** `seed.sh`는 SQL을 직접 꽂으므로 레포지토리 메서드를 거치지 않는다.
시딩만 하고 조회하면 결과가 비어 있다.

### pg_stat_statements가 못 보는 것

레포지토리 메서드를 지정할 필요는 없다 — DB에 도달한 **모든 SQL**을 자동으로 잡는다.
다만 두 가지는 안 보인다.

**어느 Java 메서드가 날린 쿼리인지** 알려주지 않는다. 쿼리 텍스트를 보고 직접 찾아야 한다.

**SQL이 아닌 것은 전혀 안 보인다.** 이 프로젝트에서는 이게 결정적이다 — 취향 벡터 재계산
1건이 24.56ms인데 그중 SQL은 **0.17ms(0.7%)**뿐이고, 나머지는 Qdrant 왕복과 768차원 벡터
연산이다. `pg_stat_statements`만 보면 가장 비싼 곳을 통째로 놓친다.

| 도구 | 보는 것 | 잴 곳 지정 |
|---|---|---|
| `pg_stat_statements` | 모든 SQL | 불필요 |
| Micrometer Timer | 지정한 메서드 | **필요** |
| JFR (`jcmd 1 JFR.start`) | JVM 전체 핫스팟 | 불필요, 대신 해석이 필요 |

```bash
# 어디가 느린지 모를 때: JDK 내장 프로파일러
docker exec backend_server jcmd 1 JFR.start duration=60s filename=/tmp/rec.jfr
docker cp backend_server:/tmp/rec.jfr .     # JDK Mission Control으로 연다
```

## 읽는 법

**측정 전에 리셋한다.** 안 하면 초기 시딩 쿼리가 순위를 독식한다(실제로 전체의 69%를 차지한 적이 있다).

```sql
SELECT pg_stat_statements_reset();
```

**② 앱 내부는 부하가 도는 중에 읽는다.**

```bash
TOKEN=...   # loadtest_* 계정으로 로그인해 얻는다
curl -s -H "Authorization: Bearer $TOKEN" localhost:8080/actuator/prometheus \
  | grep -E 'crates_user_vector|hikaricp_connections_(pending|acquire|timeout)'
```

- `crates_user_vector_stale_users` — **0이 정상.** 0이 아닌 상태가 이어지면 재계산이 밀렸거나 유실된 것
- `crates_user_vector_recalculation_failures_total` — 재계산 예외
- `hikaricp_connections_pending` / `_timeout_total` — 커넥션 풀 포화

> **퍼센타일은 창(window) 기반이다.** 부하가 끝난 뒤 읽으면 전부 0으로 보인다.
> `count`/`sum`/`max`는 누적이라 남는다.

**③ 어느 쿼리가 범인인가.**

```sql
SELECT round(total_exec_time)::text||'ms' AS 총시간,
       round(100*total_exec_time/sum(total_exec_time) OVER ())::text||'%' AS 비중,
       calls, round(mean_exec_time::numeric,2) AS 평균ms,
       left(regexp_replace(query,'\s+',' ','g'), 70) AS 쿼리
FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%'
ORDER BY total_exec_time DESC LIMIT 10;
```

풀스캔이 어디서 나는지는 이쪽이 빠르다.

```sql
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup
FROM pg_stat_user_tables ORDER BY seq_tup_read DESC LIMIT 10;
```

## 정리

테스트는 실제 API로 데이터를 만든다. 정합성은 맞지만 전부 난수 조합이라, 남겨두면 인기 보드
후보에 섞이고 이후 측정치를 오염시킨다.

**비교 측정 전에는 반드시 지울 것.** 좋아요 이력이 계정에 누적되고 재계산 비용이 O(이력)이라,
계정을 재사용하면 출발점이 달라져 비교가 성립하지 않는다. 실제로 이것 때문에 개선을 퇴행으로
잘못 읽은 적이 있다(docs 4-9).

```bash
set -a && . ./crates_server.env && set +a
docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  psql -U $POSTGRESQL_USERNAME -d crates -f - < load-test/cleanup.sql
```

## 기준선과 한계

```
backend 4 vCPU / 3GB, VU 20, like-new-board 300회, SEED="20 600 0"
워밍업 후 2회 중앙값 → 164.6 /s   p95 72ms   퍼짐 4%        (2026-09-19)
```

> 2026-09-19 이전 수치(97 TPS 등)는 워밍업 없이 1회 측정한 것이라 이 값과 비교할 수 없다.

비교할 때 **같은 조건인지 먼저 확인할 것.** VU 수, 반복 수, board 테이블 크기, 1인당 좋아요
이력이 전부 결과를 바꾼다.

자원 상한이 생겨서 **같은 설정이면 같은 값이 나온다.** 다만 이것이 "운영에서 기대할 수치"는
아니다 — **부하 생성기가 여전히 같은 기계**에 있다. 그게 남은 가장 큰 오염이고, 컨테이너화로는
해결되지 않는다. 자세한 것은 [docs 2장](../docs/load-test-and-metrics.md).

## 자원 상한 찾기 — `run-sweep.sh`

`run-measure.sh`는 일감을 고정하고 얼마나 빨리 끝내나(닫힌 모델)를 반복해 코드 변경 전후를 비교한다.
"이 사양으로 초당 몇 건까지 받을 수 있나"는 도착률을 고정한 열린 모델로만 답할 수 있어 따로 둔다.

```bash
caffeinate -dimsu ./load-test/run-sweep.sh api-recommend 10 25 50 75 100   # 도착률(/s)을 단계별로 올린다
KEEP_GOING=1 ./load-test/run-sweep.sh mixed 25 50 75 100 150               # 실패해도 끝까지 올린다
```

- **판정** — 단계마다 p95 < 목표(일반 500ms, 검색 보드 1.5초), 실패율 < 1%, 제때 시작 못 한 요청 < 1%.
- **시나리오** — `scenarios/api-*.js`는 엔드포인트 하나씩, `scenarios/mixed.js`는 화면 흐름 비율로 섞은 것
  (홈 추천·인기·내 보드 각 14%, 콘텐츠 상세 16%, 보관함 8%, 제목 검색 8%, 검색 보드 6%, 좋아요·취소 20%).
  혼합은 엔드포인트마다 목표를 따로 걸고 `rateN-endpoints.txt`에 엔드포인트별 결과를 남긴다.
- **혼자 보낼 수 없는 요청은 짝으로 잰다** — 좋아요→취소를 한 쌍으로 보내 데이터를 원래대로 유지하고, 응답 시간은
  요청마다 따로 판정한다(`rateN-endpoints.txt`). 도착률은 쌍의 수다.

  | 시나리오 | 무엇을 | 따로 보는 요청 |
  |---|---|---|
  | `api-like-toggle` | 저장된 보드의 좋아요→취소. 보드는 짝을 보내도 지워지지 않는 것만 고른다(`lib/boards.js`) | `like`, `unlike` |
  | `api-recommend-like` | 홈 추천 보드의 첫 좋아요(토큰 검증·저장)→취소(완전 삭제). 추천은 setup에서 미리 받는다 | `recommend_like`, `unlike` |
  | `api-search-like` | 검색 보드의 첫 좋아요(토큰 검증·저장)→취소(완전 삭제). 검색은 setup에서 미리 한다 | `search_like`, `unlike` |
  AI 서버가 꺼져 있으면 `SKIP_AI=1`로 검색 보드를 빼고 나머지 비율을 늘려 잰다.
- **로그인은 측정 밖에서** — `lib/tokens.sh`가 계정 200개(6명마다 1명, 가입 시기 분포 유지)로 미리 로그인한다.
  k6 setup에서 하면 BCrypt 200번이 자원 기록에 섞인다(인기 보드 5/s에서 backend CPU 397%로 기록됨).
- **준비 확인** — Qdrant 컬렉션이 모두 green(인덱싱 끝)일 때까지 기다린다. CPU가 잠깐 내려간 순간을 완료로
  읽으면 인덱스를 짓는 Qdrant와 측정이 CPU를 다툰다(추천 p95 27.8초로 나온 적이 있다).
- **맥 잠자기를 막는다** — 측정 중 잠들면 결과가 무효다(자원 기록에 수백 초 공백이 생긴다). 긴 측정은 `caffeinate`로 감싼다.
- 단계마다 k6 요약, 2초 간격 자원 기록(CPU·커넥션 풀·Qdrant 메모리), 비싼 쿼리 상위 5개를
  `results/sweep-<시나리오>-<시각>/`에 남긴다. 측정 결과와 해석은 노션 「API부하테스트」에 있다.
