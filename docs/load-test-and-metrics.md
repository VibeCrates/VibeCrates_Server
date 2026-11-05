# 부하테스트 대상과 취향 벡터 지표

무엇을 부하테스트할지, 취향 벡터 정책의 효과를 무엇으로 잴지 정리한다.
"전부 측정한다"가 아니라 **지금 실제로 위험한 곳**과 **지금 재도 뜻이 있는 지표**만 남긴다.

- 계획 작성: 2026-09-14
- 1차 실측: 2026-09-14, Python 임시 드라이버 (네이티브 `bootRun`)
- 2차 실측: 2026-09-14, **k6 도입 후** (`load-test/` 참고)
- 3차 실측: 2026-09-18, **3컬럼 인덱스 + 컨테이너 자원 상한** (3-1)
- 4차 실측: 2026-09-18, **V8 역방향 인덱스** (3-1 ②)
- 5차 실측: 2026-09-19, **인덱스만 넣고 빼는 A/B** → 3컬럼 인덱스가 쓰이지 않음을 확인
  (3-1 정정). 아직 안 잰 항목은 그렇다고 표시했다.

---

## 1. 전제 정정 — 배치는 구현된 적이 없다

"새벽 배치가 채우던 것을 실시간으로 바꿨다"는 표현이 커밋 메시지에 남아 있지만,
**그 배치는 구현된 적이 없다.**

- 스케줄러는 `RefreshTokenCleanupScheduler` 하나뿐이다.
- 이전 상태는 `UserVector`에 `new float[vectorDimension]`, 즉 **0 벡터**였다.
- 0 벡터는 Cosine 거리가 정의되지 않아 추천이 성립하지 않는다.

즉 정책 변경은 "느린 배치 → 빠른 실시간"이 아니라 **"동작하지 않던 것 → 동작하는 것"**이다.
비교 대상이 없으므로 "이전 대비 개선율" 같은 숫자는 만들 수 없고, 만들면 거짓이 된다.
자세한 것은 5장.

---

## 2. 실측 요약 — 예상이 두 번 빗나갔다

**같은 증상을 두 번 봤는데 원인이 매번 달랐다.** 그래서 예측보다 관측이 먼저다.

| 항목 | 사전 예상 | 실측 결과 |
|---|---|---|
| 좋아요 재계산이 이력에 비례 | 위험 | **맞음.** 보드 1건당 약 1ms |
| 비동기 적체 / 커넥션 고갈 | 1순위 위험 | **틀림.** 적체 없음, 커넥션은 대부분 놀고 있었다 |
| signature 조회가 부분 인덱스를 못 탐 | "가능성" | **맞음.** 인덱스 추가로 해결 |
| 재계산이 이력 때문에 무너짐 | "다시 볼 조건" | **2차에서 실제로 발생.** 1인당 181건에서 |
| 재계산이 아예 실패 | 예상 못 함 | **gRPC 4MB 한도.** 좋아요 170건이 상한이었다 (3-2) |
| 인덱스 2컬럼으로 충분 | 충분하다고 봄 | **틀림.** USER_CUSTOM은 signature 중복이 허용돼 `user_id`가 필요했다 (3-1) |
| 인덱스 고치면 곡선이 평평해짐 | 기대 | **아님.** 조회는 해결됐지만 재계산 쪽 원인 둘이 남았다 (3-1) |
| `deleted_at IS NULL` 부분 인덱스로 해결 | 이 문서가 제안했던 것 | **틀림.** 플래너가 쓰지 않는다. 드문 쪽을 인덱싱해야 했다 (3-1 ②) |
| 3컬럼 인덱스가 조회를 개선 | 측정했다고 믿었음 | **틀림.** 앱은 커스텀 플랜으로 V5 부분 인덱스를 쓴다. 3컬럼은 0회 사용 (3-1 정정) |

1차에서는 비동기 경로가 아니라 **동기 경로**(signature 조회)가 무너졌다. 인덱스를 추가해 고쳤다.
2차에서는 인덱스가 정상인데도 처리량이 떨어졌고, 이번에는 **비동기 재계산**이 원인이었다.
같은 증상(동시성을 올릴수록 처리량이 떨어짐)에 원인이 두 번 달랐다는 점이 이 문서의 요지다.

### 테스트 환경 (숫자를 읽을 때의 전제)

**"절대 수치"에 두 가지 뜻이 섞여 있다. 지금은 앞의 것만 얻었다.**

| | 뜻 | 지금 되나 |
|---|---|---|
| **재현 가능한 수치** | 같은 설정으로 다시 재면 같은 값 | **된다** (2026-09-15부터) |
| **운영에서 기대할 수치** | 실제 서버에서 이 정도 나온다 | **안 된다** |

2026-09-15부터 앱이 컨테이너에서 돌고 자원 상한이 걸린다(`cpus: '4'`, `memory: 3g`).
그 전에는 앱이 10코어를 다 쓸 수도, 부하 생성기에 뺏길 수도 있어서 같은 코드를 두 번 재도
값이 달랐다. 이제 **"4 vCPU / 3GB에서 N TPS"**라고 말할 수 있다.

```
호스트          10코어 (성능 4 + 효율 6) / 32GB
Docker VM       10 CPU / 7GB 할당
backend 컨테이너  cpus: '4', memory: 3g
```

부하 중 실측 (k6 400건 / VU 20, 97 TPS):

```
backend_server    최대 404.2%   평균 202.3%   ← 상한에 정확히 붙는다. 상한이 병목으로 작동 중
qdrant_server     최대  39.6%   평균  19.9%
postgres_server   최대   1.4%   평균   0.7%
```

**남는 오염**

- **부하 생성기가 같은 기계에 있다.** 이게 가장 크고, 컨테이너화로는 해결되지 않는다.
  진짜 절대값을 원하면 생성기를 다른 기계로 빼야 한다.
- **Apple Silicon의 성능/효율 코어.** `cpus: '4'`를 줘도 그 4개가 어느 쪽인지 고를 수 없다.
  같은 설정으로 두 번 재도 값이 흔들릴 수 있다.
- **메모리 3GB의 근거가 약하다.** "Docker VM 7GB에서 남는 만큼"이지 앱이 필요로 하는 양이
  아니다(실측 880MB, 상한의 29%). 지금은 병목이 아니라 측정에 영향이 없지만, 메모리 상한은
  GC 동작을 바꾸므로 운영 사양이 정해지면 그 값으로 맞춰야 한다.
- **Docker VM이 32GB 기계에서 7GB만 쓴다.** 앱에 더 주려면 이것부터 올려야 한다.

**Qdrant에는 상한을 걸지 않았다.** 시딩 중에는 CPU 797%까지 쓰지만, 그건 HNSW 인덱스를
19만 건에 대해 새로 짓는 일회성 작업이고(`seed.recreate=true`) 운영 전에 끝난다.
그때는 아무것도 측정하지 않는다. 정작 측정이 필요한 부하 중에는 0.4코어다.

### 기준선 (2026-09-15)

```
backend 4 vCPU / 3GB, k6 VU 20, 좋아요(보드 생성 포함) 400건
  97 TPS   p95 221ms   p99 272ms   실패 0
```

이 값과 비교할 때 **반드시 같은 조건인지 먼저 확인할 것** — VU 수, 반복 수, board 테이블 크기,
1인당 좋아요 이력이 전부 결과를 바꾼다(3-1, 3-2).

---

## 3. 부하테스트 대상

도구는 **k6**를 쓴다(`load-test/`). 1차 측정은 Python 임시 드라이버로 했고, 재현 가능하도록
k6로 옮기면서 관측을 앱 계측으로 넘겼다 — 부하 생성기가 DB를 곁에서 들여다보면
관측이 측정 대상(커넥션 풀)을 건드린다.

| 순위 | 대상 | 상태 |
|---|---|---|
| 1 | 보드 signature 조회 (추천 조회 · 좋아요 저장) | **해결.** 인덱스 추가 후 정상 |
| 2 | **좋아요 → 취향 벡터 재계산** | **결함 2건 발견.** 커넥션 경합 + 4MB 한도. 후자는 수정 완료 |
| 3 | 보관함 전체 탭 | 미측정 |
| 4 | 콘텐츠 제목 검색 | 미측정 |

### 3-1. 보드 signature 조회 — 병목 확인, 해결됨

`BoardRepository.findActiveByTypeAndSignature`는 추천 보드를 내려줄 때
`resolveGeneratedBoard`가 **보드마다** 부르므로 홈 진입 1회에 4번, 그리고 좋아요로 보드를
저장할 때마다 1번 호출된다.

```sql
-- V5: board_type 이 부분 인덱스의 술어에 있다
CREATE UNIQUE INDEX uk_board_ai_signature ON board (content_signature)
    WHERE board_type = 'TASTE_RECOMMEND' AND deleted_at IS NULL;
```
```java
// board_type 을 바인드 파라미터로 넘긴다
"WHERE b.boardType = :boardType AND b.contentSignature = :signature AND b.deletedAt IS NULL"
```

PostgreSQL이 제네릭 플랜을 쓰면 `board_type = $1`이 `= 'TASTE_RECOMMEND'`를 함의한다고 증명하지
못해 **부분 인덱스를 쓰지 못한다.** 실측(보드 3,128행):

| 조건 | 실행 계획 | 버퍼 | 소요 |
|---|---|---:|---:|
| 리터럴 | `Index Scan using uk_board_ai_signature` | 2 | 0.012ms |
| 바인드 + 제네릭 플랜 | **`Seq Scan`** (3,128행 필터) | 74 | 0.607ms |
| **바인드 + `auto`(기본값, 앱이 실제로 쓰는 것)** | **`Index Scan using uk_board_ai_signature`** | **3** | — |

> **이 표의 앞 두 줄은 사실이고, 세 번째 줄이 빠져 있었다.** 그래서 "제네릭 플랜이니까 앱에서도
> Seq Scan이 난다"고 추론했는데 틀렸다. `plan_cache_mode`의 기본값은 `auto`이고,
> auto는 제네릭 플랜의 추정 비용이 커스텀보다 싸지 않으면 **계속 커스텀을 쓴다.**
>
> ```
> 커스텀(auto)      Index Scan using uk_board_ai_signature   cost 8.29    buffers 3
> 강제 제네릭        Seq Scan (Rows Removed by Filter: 3127)  cost 69.92   buffers 23
> ```
>
> 제네릭이 8배 비싸므로 PostgreSQL은 전환하지 않는다. 같은 prepared statement를 30회 실행해
> 확인했다 — `uk_board_ai_signature` 30회, `seq_scan` 0회. **한 번도 제네릭으로 넘어가지 않는다.**
>
> 즉 `force_generic_plan`으로 재현한 것은 **PostgreSQL이 스스로 회피하는 상태**였다.
> 측정은 정확했고 추론이 틀렸다(4-7).

보드 수에 비례해 나빠진다. **통제 실험** — 동일 조건(좋아요 100건 / 워커 20)에서
`board` 테이블만 620행 → 3,128행으로 커졌을 때:

| 경로 | TPS | p50 |
|---|---:|---:|
| 새 보드 생성 + 좋아요 (signature 조회 있음) | **40** (620행 시점 107) | 568ms |
| 기존 보드 좋아요 (signature 조회 없음) | 62 | 344ms |
| 추천 보드 조회 (읽기 전용) | 138 | 96ms |

동시성을 올리면 처리량이 오히려 떨어진다.

| 워커 | 좋아요 | TPS | HTTP p95 |
|---:|---:|---:|---:|
| 20 | 100 | 107 | 281ms |
| 40 | 200 | 92 | 631ms |
| 60 | 400 | 66 | 1,289ms |
| 80 | 600 | 46 | 2,591ms |
| 100 | 800 | 31 | **4,695ms** |

#### JPQL에 enum을 직접 써도 소용없다

`findActiveUserBoardBySignature`처럼 `b.boardType = com.crates...BoardType.USER_CUSTOM`으로
리터럴을 박는 선례가 코드에 있지만, **Hibernate 6가 그것도 파라미터로 바꾼다.**
테스트 중 로그 집계: `board_type='USER_CUSTOM'` 0회, `board_type=?` 6,900회.
따라서 `uk_board_user_signature`도 같은 문제를 안고 있다.

#### ⚠️ 정정 (2026-09-19) — 이 인덱스는 실제로 쓰이지 않는다

**아래 서술 대부분이 틀렸다.** 컨테이너 자원 상한을 고정한 뒤 **인덱스만 넣고 빼서** A/B를
재봤더니, `idx_board_signature_lookup`이 **애플리케이션에서 한 번도 쓰이지 않았다.**

같은 컨테이너(4 vCPU / 3GB) · 같은 코드 · V8 적용 상태에서 인덱스만 다르게 5회씩:

| 회차 | board | 인덱스 **없음** (V5만) | 인덱스 **있음** (V7 3컬럼) |
|---:|---|---:|---:|
| 1 | 10 → 610 | 199.4 / 135ms | 181.2 / 131ms |
| 2 | 610 → 1,210 | 120.7 / 225ms | 119.6 / 219ms |
| 3 | 1,210 → 1,810 | 82.2 / 370ms | 78.7 / 372ms |
| 4 | 1,810 → 2,410 | 61.9 / 496ms | 56.6 / 564ms |
| 5 | 2,410 → 3,010 | 45.2 / 740ms | 41.9 / 788ms |

**차이가 없다.** 오히려 인덱스가 있는 쪽이 조금씩 낮은데, INSERT마다 유지할 인덱스가 하나
늘어서인 것으로 보이나 이 정도 차이는 실행 간 변동 범위와 겹친다.

부하테스트 중 어떤 인덱스가 실제로 쓰였는지 세어보면 이유가 드러난다.

```
uk_board_ai_signature        2,953회   ← V5 부분 유니크 인덱스
idx_board_signature_lookup       1회   ← V7 3컬럼. 사실상 안 쓰임

signature 조회 쿼리: 3,000회 / 평균 0.0089ms / 회당 버퍼 1
```

**V5의 부분 인덱스가 이미 최적으로 동작하고 있었다.**

##### 무엇을 잘못했나 — `force_generic_plan`은 앱이 도달하지 않는 상태였다

V7을 만든 근거는 "제네릭 플랜에서는 부분 인덱스를 쓸 수 없다"였고 그 자체는 사실이다.
놓친 것은 **PostgreSQL이 그 상태를 스스로 피한다**는 점이다.

`plan_cache_mode`의 기본값은 `auto`다. 처음 몇 번은 커스텀 플랜(실제 값을 아는 상태)으로
실행하고, 제네릭 플랜의 추정 비용이 커스텀 평균보다 **싸지 않으면 계속 커스텀을 쓴다.**
부분 인덱스를 쓰는 커스텀 플랜이 훨씬 싸므로 제네릭으로 넘어가지 않는다.

`SET plan_cache_mode = force_generic_plan`은 그 보호를 강제로 끈 것이다.
**검증 방법이 문제를 만들어냈다.**

`uk_board_ai_signature`가 선택된 것도 자연스럽다. 그쪽은 `content_signature` 단독 **유니크**
부분 인덱스라 최대 1행이 보장되고, 3컬럼 비유니크 인덱스보다 싸다.

##### 그럼 2026-09-14의 TPS 저하는 무엇이었나

`findLikedBoardsWithTime`의 board 풀스캔이었다(3-1 ②, V8에서 해결). 당시
`pg_stat_user_tables`에서 board seq scan을 보고 signature 조회 탓으로 **잘못 귀속**했다.

##### 남은 판단

`idx_board_signature_lookup`은 지금 워크로드에서 값을 내지 못한다. 제거 검토 대상이다.
다만 두 가지는 확인이 더 필요하다.

- `findActiveUserBoardBySignature`(USER_CUSTOM 경로)는 이 부하테스트가 실행하지 않는다.
  그쪽도 `uk_board_user_signature`(부분 유니크)가 있어 같은 논리일 것으로 보이지만 미측정이다.
- 커스텀 플랜은 실행마다 계획을 세워 플래닝 비용이 든다. 지금은 0.0089ms라 문제가 아니다.

---

#### 아래는 정정 전 서술이다 (2026-09-18, 환경이 혼재된 측정)

k6로 같은 부하(VU 20 / 좋아요 600건)를 보드가 늘어나는 동안 5회 반복했다.

| 회차 | board 행 | TPS | p95 | 2026-09-14 (2컬럼·네이티브) |
|---:|---|---:|---:|---|
| 1 | 10 → 610 | 72.1 | 308ms | 74.6 / 361ms |
| 2 | 610 → 1,210 | 71.1 | 401ms | 53.6 / 481ms |
| 3 | 1,210 → 1,810 | 46.8 | 691ms | 37.6 / 707ms |
| 4 | 1,810 → 2,410 | 39.6 | 868ms | 29.4 / 989ms |
| 5 | 2,410 → 3,010 | **41.1** | **706ms** | 23.8 / 1,259ms |

비슷한 보드 규모에서 **처리량 30~70% 개선, p95는 최대 44% 감소**했다.

> **다만 이 비교는 깨끗하지 않다.** 두 측정 사이에 인덱스(2→3컬럼)뿐 아니라 실행 환경도
> 바뀌었다(네이티브 `bootRun` → 컨테이너 4 vCPU / 3GB). 개선분을 인덱스 하나에 귀속시킬 수 없다.
> 변수를 하나만 바꿔 재보려면 인덱스만 되돌리고 같은 컨테이너에서 다시 재야 한다.

**signature 조회 자체는 완전히 해결됐다.**

```
select b1_0.id, ... from board b1_0 where board_type=$1 and content_signature=$2 and deleted_at is null
  3,000회 호출 / 평균 0.017ms / 호출당 버퍼 2.5
```

`pg_stat_statements` 상위권에도 없다. 인덱스가 의도대로 동작한다.

#### 그런데 처리량 저하는 남아 있다 — 원인이 둘이다

72.1 → 41.1 TPS로 여전히 떨어진다. 원인은 이 인덱스가 아니다.

**① 1인당 좋아요 이력 (기존에 파악한 것)** k6가 좋아요 3,612건을 20명에게 집중시켜 1인당 181건을
만들었고, 재계산 비용이 O(이력)이라 동기 경로와 커넥션을 다투기 시작했다. 3-2 참고.

이번 실행에서도 같은 양상이다.

```
재계산 3,000건 / 평균 157ms / 훑은 보드 평균 75.6건, 최대 159건
커넥션 획득 평균 144.5ms   (타임아웃 0, 재계산 실패 0, 밀린 사용자 0)
```

**② `findLikedBoardsWithTime`이 board를 풀스캔한다 (새로 발견)**

`pg_stat_user_tables`에 요청당 seq scan 1회가 계속 잡혀서 추적한 결과다. signature 조회가
아니라 **재계산이 좋아요 목록을 읽는 쿼리**였다.

```
select f.board_id, f.created_at from board_feedback f join board b ...
  3,000회 호출 / 평균 0.378ms / 호출당 버퍼 85
```

```
Hash Join
  ->  Seq Scan on board b (rows=3010)      Filter: (deleted_at IS NULL)   buffers 67
  ->  Hash  ->  Bitmap Heap Scan on board_feedback f                      buffers 34
                  ->  Bitmap Index Scan on idx_feedback_user_recent       buffers 3
```

좋아요 쪽은 완벽하다 — 159행을 버퍼 3개로 찾는다. 문제는 **`f.board.deletedAt IS NULL`을
확인하려고 board 전체를 훑는 것**이다.

지금 규모에서는 **플래너의 선택이 옳다.** 3,010행 seq scan(67버퍼)이 159번의 PK 조회(약 477버퍼)보다
싸다. 문제는 이 비용이 **board 크기에 비례해 자란다**는 것이다. 좋아요 한 번마다 board 전체를
읽으므로, 재계산 비용이 O(이력)일 뿐 아니라 **O(전체 보드 수)**이기도 하다.

board가 더 커지면 플래너가 nested loop로 넘어가지만 그 역시 좋아요 건수만큼 PK를 조회한다.
어느 쪽이든 O(전체 보드 수)다.

**해결 — 조건을 뒤집어 "드문 쪽"을 인덱싱한다 (V8, 2026-09-18)**

네 가지를 재보고 골랐다.

| 방식 | board 100,010행 / 좋아요 159건 | 정확성 |
|---|---:|---|
| ① 조인 유지 (당시) | 480버퍼 / 0.629ms | 유지 |
| ② `board(id) WHERE deleted_at IS NULL` | **플래너가 안 씀** — 계획 그대로 | 유지 |
| ③ 조인 제거 | 6버퍼 / 0.026ms | **깨짐** |
| ④ `NOT EXISTS` + `board(id) WHERE deleted_at IS NOT NULL` | **8버퍼 / 0.090ms** | 유지 |

**②는 동작하지 않는다.** 소프트 삭제가 0건이면 `deleted_at IS NULL` 부분 인덱스가 전체 행을
담으므로, 테이블만큼 큰 인덱스를 훑느니 힙을 훑는 것이 싸다고 플래너가 판단한다.
삭제가 적을수록 더 안 듣는다 — 정확히 우리 상황이다.
(이 문서가 한동안 ②를 제안하고 있었다. 검증 없이 적은 것이었다.)

**④를 택했다.** 살아있는 보드는 사실상 전부라 인덱싱해도 소용이 없지만, 삭제된 보드는
극소수라 인덱스가 거의 비어 있다. 비용의 차수가 O(전체 보드) → **O(삭제된 보드)**가 된다.

```sql
CREATE INDEX idx_board_deleted ON board (id) WHERE deleted_at IS NOT NULL;   -- 10만 행에서 16 kB
```
```
Nested Loop Anti Join
  ->  Bitmap Index Scan on idx_feedback_user_recent                    buffers  3
  ->  Index Only Scan using idx_board_deleted  (rows=0)                buffers  1   ← board 쪽
```

실제 DB에서 **board 쪽 비용 67버퍼 → 1버퍼**, 결과는 동일(159행 = 159행).
`pg_stat_user_tables`의 회차당 seq scan도 **600회 → 1회**가 됐다.

JPQL에서 서브쿼리에 `f.board`가 아니라 **`f.board.id`**를 써야 한다. 전자는 board로 조인을
유발해 없애려던 비용이 되돌아온다.

**end-to-end 효과 — 이력이 적을수록 크다**

같은 출발점(board 10행 / 좋아요 0건)에서 5회 반복:

| 회차 | board | V7만 | V8 적용 후 | 배수 |
|---:|---|---:|---:|---:|
| 1 | 10 → 610 | 72.1 / 308ms | **146.6 / 160ms** | 2.03× |
| 2 | 610 → 1,210 | 71.1 / 401ms | **113.1 / 236ms** | 1.59× |
| 3 | 1,210 → 1,810 | 46.8 / 691ms | **66.6 / 480ms** | 1.42× |
| 4 | 1,810 → 2,410 | 39.6 / 868ms | **47.3 / 778ms** | 1.19× |
| 5 | 2,410 → 3,010 | 41.1 / 706ms | 41.0 / 753ms | 1.00× |

**회차가 갈수록 이득이 줄어 5회차에는 사라진다.** 그 지점에서는 1인당 보유 좋아요가 150건에
달해 O(이력) 재계산(평균 424ms)이 전체를 지배하고, 67버퍼를 아낀 것이 묻힌다.

**"1인당 150건"이 무엇을 뜻하는지 주의해서 읽을 것.** 한 번에 150개를 누른 것이 아니라
그 시점에 **좋아요 상태로 보유한 보드가 150개**라는 뜻이다(취소가 없었으므로 누적 = 보유).
회차당 30건씩 5회에 걸쳐 쌓였다.

```
5회차 × (좋아요 600건 ÷ 사용자 20명) = 사용자당 30건씩 5번 = 누적 150건
실제 데이터: loadtest_sig8 보유 156건, 첫 좋아요와 마지막 좋아요 간격 49초
```

여기서 **비현실적인 것은 누적량이 아니라 속도와 동시성이다.** 49초 만에 150건을 누르는 것과
20명 전원이 동시에 그러는 것은 사람의 행동이 아니지만, **누적 150건 자체는 열심히 쓰는
사용자가 몇 달에 걸쳐 충분히 도달하는 수**다.

그래서 O(이력) 비용은 테스트 인공물이 아니다. 파워 유저가 좋아요 150건을 모으면 그 사용자는
**좋아요를 누를 때마다 보드 150개를 훑는 재계산**을 실제로 유발한다. 응답은 비동기라 빠르지만
서버 뒤에서 그 비용이 그대로 발생하고, 이력이 늘수록 계속 자란다.

남은 병목은 전적으로 재계산의 O(이력) 비용이다 → 6장 5번. **운영에서 실제로 마주할 문제다.**

#### 해법 (검증 완료)

V7에서 `rating`에 한 것과 같다. **조건 컬럼을 부분 인덱스 술어가 아니라 인덱스 컬럼으로 옮긴다.**

```sql
CREATE INDEX idx_board_signature_lookup ON board (board_type, content_signature, user_id)
    WHERE deleted_at IS NULL;
```
```
Index Scan using idx_board_signature_lookup   buffers 2   0.031ms   ← 제네릭 플랜에서도 탄다
```

#### user_id가 세 번째 컬럼인 이유 (2026-09-18 정정)

처음에는 두 컬럼 `(board_type, content_signature)`으로 만들었다. 이 인덱스가 덮어야 하는
조회가 둘인데 **조건 집합이 다르다**는 것을 놓쳤다.

```
findActiveByTypeAndSignature      board_type + content_signature
findActiveUserBoardBySignature    board_type + content_signature + user_id
```

USER_CUSTOM은 **사용자가 다르면 같은 `content_signature`를 가질 수 있다.**
`uk_board_user_signature`가 `(user_id, content_signature)`로 유일성을 보장하므로,
signature 단독으로는 중복이 허용된다.

그리고 실제로 몰리는 경로가 있다 — `updateBoard`에서 남의 보드를 **제목만 바꿔** 저장하면
콘텐츠가 그대로라 signature가 같은 채로 복제된다. 인기 보드를 N명이 제목만 바꿔 담으면
`(USER_CUSTOM, 같은 signature)` 행이 N개 쌓인다.

실측 (같은 signature 3,000행, 제네릭 플랜 강제):

| 인덱스 | 실행 계획 | 버퍼 | 소요 |
|---|---|---:|---:|
| `(board_type, content_signature)` | Index Scan + **`Rows Removed by Filter: 2999`** | 32 | 0.180ms |
| `(board_type, content_signature, user_id)` | Index Cond에 3컬럼 전부 | **4** | **0.020ms** |

`board_type`을 선두에 두면 앞 두 컬럼이 `findActiveByTypeAndSignature`의 접두사가 되어
TASTE_RECOMMEND 경로도 같은 인덱스 하나로 해결된다. 크기는 103,000행 기준 4.0MB → 4.9MB.

> **인덱스를 설계할 때는 "어떤 쿼리가 쓰는가"가 아니라 "그 쿼리들의 조건 집합이 무엇인가"를
> 봐야 한다.** 두 쿼리가 같은 인덱스를 쓴다는 사실만 보고 조건이 같다고 넘겨짚었다.

트랜잭션 안에서 만들어 `EXPLAIN`으로 확인하고 롤백해 검증했다.

- `deleted_at IS NULL`은 부분 술어로 남겨도 된다. 쿼리에 **상수**로 박혀 있어 플래너가 증명할 수
  있다. **파라미터에 의존하는 술어만** 문제다.
- 유니크 제약(`uk_board_ai_signature`, `uk_board_user_signature`)은 그대로 둔다. 제약의 의미가
  부분적이라(삭제되지 않은 TASTE_RECOMMEND 안에서만 유일) 일반 유니크로 바꿀 수 없다.
  조회용 인덱스를 **따로** 추가하는 형태여야 한다.

### 3-2. 좋아요 → 취향 벡터 재계산 — 이력이 쌓이면 두 방향으로 무너진다

**비용이 이력에 비례하는 것은 사실이다.** `recalculateFor`가 매번 좋아요 집합 전체를 다시 읽는다.

| 좋아요 이력 | 재계산 반영 지연 | 좋아요 HTTP |
|---:|---:|---:|
| 0 | 32ms | 52ms |
| 10 | 19ms | 34ms |
| 25 | 35ms | 25ms |
| 50 | 49ms | 20ms |
| 100 | **107ms** | 28ms |

**보드 1건당 약 1ms.** 좋아요 1,000건인 사용자는 좋아요를 누를 때마다 1초짜리 재계산이 돈다.
HTTP 응답은 이력과 무관하게 평평하다 — 비동기 설계는 의도대로 동작한다.

#### 1차 측정 — 위험이 재현되지 않았다

사전에 1순위로 지목했던 것(무제한 팬아웃 → 커넥션 고갈)은 나오지 않았다.

| 이력/인 | 좋아요 | 워커 | 배수 시간 | 최대 적체 |
|---:|---:|---:|---:|---:|
| 41 | 100 | 20 | 0.07s | 0명 |
| 56 | 400 | 60 | 0.12s | 0명 |
| 106 | 800 | 100 | 0.41s | 1명 |

- 재계산 실패 0건, 커넥션 타임아웃 0건, 벡터 유실 0명
- DB 커넥션은 평균 9.9개가 `idle`, `active` 최대 2개 — **놀고 있었다**

당시 결론은 "지금 규모에선 문제 없다"였고, 다시 볼 조건을 **1인당 좋아요 수백 건**으로 적어뒀다.
부하를 20명에게 나눠 걸어서 1인당 100건을 넘지 못했기 때문이다.

#### 2차 측정(k6) — 그 조건이 실제로 왔다

k6가 좋아요 3,612건을 **20명에게 집중**시켜 1인당 181건을 만들었다. 예측한 지점이다.

| 회차 | board 행 | TPS | HTTP p95 |
|---:|---|---:|---:|
| 1 | 622 → 1,222 | 74.6 | 361ms |
| 2 | 1,222 → 1,822 | 53.6 | 481ms |
| 3 | 1,822 → 2,422 | 37.6 | 707ms |
| 4 | 2,422 → 3,022 | 29.4 | 989ms |
| 5 | 3,022 → 3,622 | 23.8 | 1,259ms |

```
재계산이 훑은 보드   평균 90.8건  최대 186건
재계산 소요          평균 192ms   최대 557ms
커넥션 획득 대기     평균 192ms   ← 커넥션 10개를 비동기 재계산이 잠식
```

동기 경로(좋아요 요청)와 비동기 재계산이 같은 풀 10개를 다툰다. **1인당 평균 좋아요 수를
지표로 감시할 것** — `crates.user.vector.recalculation.boards` 분포가 그 값이다.

> 처음에는 이 하락을 3-1의 인덱스 탓으로 의심했다. 그런데 `EXPLAIN`이 `Index Scan`을
> 보여줬고, `pg_stat_statements`에도 signature 조회가 상위에 없었다. **숫자가 큰 쪽이 아니라
> 고칠 수 있는 쪽을 찾는 데 3층 관측이 필요했다.**

#### 그리고 한계선에서 아예 실패한다 — gRPC 4MB

같은 실행에서 **재계산 실패 216건, 벡터가 밀린 사용자 20명 전원**이 나왔다.
그런데 HTTP 응답은 전부 200이다.

`retrieveVectors`가 좋아요한 보드 전체의 콘텐츠 벡터를 **한 번의 요청으로** 가져온다.
"보드 수와 무관하게 Qdrant 왕복은 한 번"이라고 설계한 부분이 그대로 상한이 됐다.

```
170보드 × 콘텐츠 8건 × 768차원 × 4byte(float32) ≈ 4.2MB
                              gRPC 기본 수신 한도 = 4,194,304 byte
```

`retrieveVectors`를 크기별로 직접 호출해 경계를 확인했다.

```
1,200개 → OK (3.52MB)
1,360개 → CANCELLED: Failed to read message
1,488개 → RESOURCE_EXHAUSTED: gRPC message exceeds maximum size 4194304: 4232685
```

**좋아요를 많이 누른 사용자일수록 먼저 멈춘다.** 활발한 사용자의 추천이 먼저 망가지고,
예외는 리스너의 catch에서 로그로만 남아 겉으로는 멀쩡해 보인다.

**조치** — 1,000개씩 나눠 요청(`RETRIEVE_CHUNK_SIZE`). 수신 한도를 올리는 방법도 있으나
상한을 뒤로 미룰 뿐이고 사용자가 더 모으면 같은 자리에서 다시 터진다.

**검증** — 이미 망가져 있던 20명에게 좋아요를 한 번씩 보냈다.

```
recalculation_boards_max      188    ← 벡터 1,504개 조회. 수정 전 터지던 크기
recalculation_seconds_count    21    ← 전부 updated
recalculation_failures_total    0
stale_users                     0    ← 20명 → 0명
```

평균 재계산 220ms로, 왕복이 1회→2회로 늘었는데도 이전(192ms)과 거의 같다. **쪼개기 비용은
사실상 없다.** 1,000은 768차원 기준값이므로 차원이 커지면 함께 낮춰야 한다.

#### 남은 것

4MB 한도는 막았지만 **비용이 O(이력)인 것은 그대로다.** 좋아요 1,000건인 사용자는 좋아요마다
1초짜리 재계산이 돈다. 이력 상한이나 증분 방식을 다시 검토할 근거가 숫자로 생겼다.

이것을 부하테스트의 과장으로 읽지 말 것. **누적 좋아요 150건은 열심히 쓰는 사용자가 몇 달에
걸쳐 도달하는 평범한 수치**이고, 그 사용자는 좋아요를 누를 때마다 보드 150개를 훑는 재계산을
유발한다. 비현실적인 것은 부하테스트의 속도와 동시성뿐이다(3-1 ②).

### 3-3. 보관함 전체 탭 (미측정)

```sql
ORDER BY COALESCE(f.created_at, b.created_at) DESC, b.id DESC
```

표현식 정렬이라 인덱스로 해결할 수 없다. 매 페이지마다 매칭 집합 전체를 정렬하고, 오프셋
페이징이라 뒷페이지일수록 건너뛰는 비용이 는다. 보드 1건에 콘텐츠 8건이 딸려 오므로
`size=20`이면 콘텐츠 160행을 조립한다.

설계 당시 **"개인 보관함은 많아야 수백 건"**을 전제하고 오프셋을 택했다. 이 테스트의 목적은
그 전제 확인이다. 깨지면 키셋(커서) 페이징으로 옮겨야 하는데 `COALESCE` 정렬 키가 까다롭다.

### 3-4. 콘텐츠 제목 검색 (미측정)

단건 성능은 V6 마이그레이션 주석에 실측돼 있다.

```
콘텐츠 191,239건 / PostgreSQL 15
"har" (3,579건 매칭) 17ms · "harry potter" 1ms · "the" (62,004건 매칭) 116ms
```

그러므로 **동시 실행만** 확인하면 된다. 자동완성이라 디바운스가 있어도 요청이 잦고,
트라이그램 매칭은 CPU 바운드라 동시성이 올라가면 서로 잡아먹는다.

### 3-5. 지금 재면 안 되는 것 — 검색 보드

`/api/search/board`는 미룬다. 지금은 `ai.server.stub.enabled: true`라 로컬에서 난수를
만들지만, AI 서버가 연결되면 외부 HTTP 호출(connect 2s / read 3s)이 된다.
프로파일이 완전히 달라 지금 재면 틀린 것을 재게 된다.

### 3-6. 부수 발견 — 로그인 실패가 500

```
ERROR [SYSTEM ERROR] URI: /api/auth/login | 자격 증명에 실패하였습니다.
```

`AuthenticationException`이 `GlobalExceptionHandler`에 없어 500으로 나간다.
커밋 `b3dc6a7`에서 고친 것과 같은 종류다.

---

## 4. 측정 방법과 함정

### 4-1. 재계산 지연을 재는 법

앱에 계측이 없으므로 DB의 두 타임스탬프 차이로 잰다.

```
user_vector.updated_at - board_feedback.created_at   =  큐 대기 + 재계산 소요
```

둘 다 앱이 `LocalDateTime.now()`로 찍으므로 시계 오차가 없다.
**이 값이 음수면 재계산이 유실된 것이다.**

### 4-2. 내가 틀렸던 측정 — 기록해 둘 것

1차 시도에서 "1.3초 발사 → **70초** 배수"가 나왔고, 하마터면 심각한 적체로 보고할 뻔했다.
실제로는 **측정 코드가 만든 값**이었다.

- 사용자별로 "`updated_at`이 3초간 안 변하면 잠잠"이라고 판정했고
- 그 판정을 20명에게 **순차로** 돌렸다 → 20 × 3초 = **60초가 바닥으로 깔린다**

집계 쿼리 하나로 "밀린 사용자가 몇 명인가"를 판정하도록 바꾸니 실제 배수는 0.1초 안팎이었다.

```sql
-- 이 값이 0이 되는 순간이 배수 완료
SELECT count(*) FROM user_vector uv
  JOIN users u ON u.id = uv.user_id
  JOIN LATERAL (SELECT created_at FROM board_feedback
                WHERE user_id = uv.user_id AND rating = 'LIKE'
                ORDER BY created_at DESC LIMIT 1) f ON true   -- idx_feedback_user_recent
  WHERE u.login_id LIKE 'loadtest_%' AND uv.updated_at < f.created_at;
```

교훈: **폴링 간격과 안정 판정 시간이 측정하려는 값보다 크면 측정기가 결과를 만든다.**
비동기 작업의 지연을 잴 때는 관측 자체의 바닥값을 먼저 계산해 볼 것.

### 4-3. 인덱스 후보를 안전하게 검증하는 법

PostgreSQL은 DDL도 트랜잭션이다. 스키마를 건드리지 않고 후보 인덱스를 시험할 수 있다.

```sql
BEGIN;
CREATE INDEX ... ;
ANALYZE board;
SET plan_cache_mode = force_generic_plan;   -- 바인드 파라미터 상황을 강제 재현
PREPARE q(varchar, varchar) AS SELECT ... WHERE board_type = $1 AND content_signature = $2 ...;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE q('TASTE_RECOMMEND', '...');
ROLLBACK;
```

`force_generic_plan`이 핵심이다. 그냥 `EXPLAIN`을 하면 커스텀 플랜이 잡혀 **문제가 재현되지
않는다.** 실제 앱은 실행 횟수에 따라 제네릭 플랜으로 넘어가므로 증상이 간헐적으로 보인다.

### 4-4. 퍼센타일은 부하가 도는 중에 읽어야 한다

Micrometer의 `percentiles`는 **창(window) 기반**이다. 부하가 끝난 뒤에 읽으면 최근 창이 비어
전부 0으로 보인다. `count`/`sum`/`max`는 누적이라 남는다.

```
crates_user_vector_recalculation_seconds{quantile="0.95"}  0.0     ← 끝난 뒤라 0
crates_user_vector_recalculation_seconds_sum             653.67    ← 누적은 남음
crates_user_vector_recalculation_seconds_count          3396
```

실제로 이것 때문에 "퍼센타일이 0인데 평균은 192ms"라는 모순된 값을 한참 들여다봤다.
**평균은 `sum / count`로 직접 계산하면 언제든 나온다.**

그리고 `/actuator/metrics`는 퍼센타일을 **아예 노출하지 않는다.** Timer의 `measure()`는
count/total/max만 돌려주고 퍼센타일은 히스토그램 스냅샷에 있다. `micrometer-registry-prometheus`를
붙이고 `/actuator/prometheus`로 읽어야 한다.

### 4-5. pg_stat_statements는 측정 전에 리셋한다

누적 집계라 **앱 기동 이후의 모든 쿼리**가 섞여 있다. 초기 시딩 쿼리 하나(44.8초, 1회 호출)가
전체 실행 시간의 **69%**를 차지해 부하테스트 쿼리를 전부 가린 적이 있다.

```sql
SELECT pg_stat_statements_reset();
```

그리고 이 도구는 **퍼센타일을 주지 않는다.** `calls`, `total_exec_time`, `mean`, `min`, `max`,
`stddev`뿐이라 분포를 복원할 수 없다. 답하는 질문이 다르다 — "꼬리 지연이 얼마인가"는 k6와
Micrometer가, **"어느 쿼리가 그 시간을 먹는가"는 이것만** 답한다.

### 4-6. 풀스캔은 pg_stat_user_tables가 가장 빨리 보여준다

`EXPLAIN`으로 하나씩 찍어보기 전에 이것부터 본다.

```sql
SELECT relname, seq_scan, seq_tup_read, idx_scan, n_live_tup
FROM pg_stat_user_tables ORDER BY seq_tup_read DESC LIMIT 10;
```

3-1의 부분 인덱스 문제가 여기 그대로 남아 있었다 — `board` 행이 3,232개뿐인데 seq scan
13,651회, 순차로 읽은 행 700만.

> 다만 **seq scan이 많다고 다 문제는 아니다.** 같은 표에서 `board_item`이 4,034만 행을
> 순차로 읽고 있었는데, 확인해 보니 인덱스 경로가 오히려 버퍼를 더 썼다(322 vs 198).
> 보드 id 155개를 각각 인덱스로 뒤지는 것보다 26,000행을 한 번 훑는 게 실제로 싸다.
> 플래너가 맞게 판단한 것이고, 테이블이 커지면 알아서 인덱스로 넘어간다.
> **숫자가 큰 쪽이 아니라 "고칠 수 없는 쪽"이 문제다.**

### 4-7. 강제한 실행 계획이 실제로 발생하는지 확인할 것

`SET plan_cache_mode = force_generic_plan`으로 "부분 인덱스를 못 쓰는" 상태를 재현하고
그것을 근거로 인덱스를 추가했다(V7). 그런데 **애플리케이션은 그 상태에 도달하지 않았다.**

`plan_cache_mode`의 기본값 `auto`는 제네릭 플랜이 커스텀보다 싸지 않으면 **계속 커스텀을
쓴다.** 부분 인덱스가 걸린 조회에서는 커스텀이 훨씬 싸므로 제네릭으로 넘어가지 않는다.

확인하는 법은 `EXPLAIN`이 아니라 **누적 사용 횟수**다.

```sql
SELECT indexrelname, idx_scan FROM pg_stat_user_indexes
WHERE relname = 'board' ORDER BY idx_scan DESC;
```

```
uk_board_ai_signature        2,953회   ← 실제로 쓰인 것
idx_board_signature_lookup       1회   ← 추가했지만 안 쓰임
```

`EXPLAIN`은 "쓸 수 있다"를, `idx_scan`은 **"실제로 썼다"**를 보여준다. 둘은 다르다.

### 4-8. 앱을 재시작하면 Qdrant가 재시딩된다 — health 응답은 측정 준비 완료가 아니다

인덱스를 복원한 뒤 앱을 재시작하고 바로 측정했더니 TPS가 181 → 72로 떨어졌다.
`ai.vectorstore.qdrant.seed.recreate: true`라 기동마다 19만 벡터를 다시 넣고,
그 뒤 HNSW 인덱스를 백그라운드로 짓는다(CPU 797퍼센트까지 쓴다).

`/actuator/health`가 응답하는 시점은 시딩 완료가 아니다. Spring Boot는 `ApplicationRunner`를
"Started" 로그 **이후에** 실행하고, 웹 서버는 그보다 먼저 열린다.

```bash
docker logs backend_server 2>&1 | grep "seed completed" | tail -1
docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' qdrant_server   # 한가한지
```

### 4-9. 좋아요 이력은 실행 사이에 누적된다 — 리셋하지 않으면 비교가 성립하지 않는다

V8 효과를 재려고 같은 스크립트를 돌렸는데 TPS가 41.1 → **21.3**으로 떨어져 나왔다.
개선했는데 나빠진 것처럼 보였다.

원인은 **계정을 재사용한 것**이었다. 앞선 실행이 남긴 좋아요가 그대로 쌓여 있었다.

```
직전 측정   재계산이 훑은 보드 평균  75.6건 → 재계산 평균 157ms
이번 측정   재계산이 훑은 보드 평균 165.5건 → 재계산 평균 424ms
```

재계산 비용이 O(이력)이므로 이력이 2.2배면 비용도 2.7배다. V8과 무관한 변화였다.
`load-test/cleanup.sql`로 비우고 같은 출발점에서 다시 재니 2배 개선이 나왔다.

교훈: **부하테스트가 상태를 남기면 다음 실행의 출발점이 달라진다.** 실행 전에 무엇이
누적되는지 확인하고, 비교 측정에서는 반드시 리셋할 것. 이 프로젝트에서 누적되는 것은
board 행 수와 1인당 좋아요 이력 둘이다.

### 4-8. DELETE는 공간을 돌려주지 않는다 — 정리한 뒤에도 이전 규모를 잰다

4-7의 리셋에는 구멍이 있었다. `cleanup.sql`이 행을 지워도 **페이지는 그대로 남는다.**
PostgreSQL의 DELETE는 행을 죽은 것으로 표시할 뿐이다. seq scan은 그 빈 페이지를 전부 읽으므로,
규모를 바꿔가며 재면 새 규모가 아니라 **이전 실행의 잔해**를 재게 된다.

보드 4만 개짜리 데이터를 정리하고 810개로 다시 심은 직후:

```
정리 직후        board   820행 / 1,311페이지     인기 보드 seq scan  1,311버퍼  0.373ms
VACUUM FULL 후   board   820행 /    15페이지     인기 보드 seq scan     15버퍼  0.167ms
```

**같은 820행인데 버퍼가 87배 차이 난다.** 숫자를 보고 "작은 테이블인데 왜 이렇게 비싸지"
하고 다른 원인을 찾아 헤매기 딱 좋다.

**일반 VACUUM으로는 부족하다.** 빈 페이지를 재사용 가능으로 표시하고 테이블 *끝*의 연속된
빈 페이지만 잘라낸다. 살아있는 행 하나가 뒤쪽에 있으면 앞의 공백이 통째로 남는다.
`run-measure.sh`에 `VACUUM ANALYZE`가 이미 있었는데도 이 문제가 났던 이유다.

`cleanup.sql` 끝에 넣었다(COMMIT 뒤 — VACUUM은 트랜잭션 안에서 못 돈다).

```sql
SET lock_timeout = '30s';
VACUUM (FULL, ANALYZE) board;
VACUUM (FULL, ANALYZE) board_item;
VACUUM (FULL, ANALYZE) board_feedback;
```

ACCESS EXCLUSIVE 락을 잡으므로 앱이 떠 있으면 밀릴 수 있다. 상한을 두되 **시간 초과를
조용히 넘기지 않는다** — 정리가 안 된 채 재면 위의 87배를 재게 되므로, 측정이 실패하는
편이 낫다.

`run-measure.sh`의 `reset_data()`는 시딩 *뒤에* `ANALYZE`만 한다. 공간 회수는 cleanup이
끝냈고, 여기서 필요한 것은 방금 심은 행을 통계에 반영하는 것뿐이다.

### 4-9. board를 24배 키워도 계획은 그대로였다 — 크기가 답이 아닌 경우

"데이터가 작아서 운영 계획을 못 본다"는 가정을 확인해봤다. 1인당 좋아요를 10건으로 고정하고
board만 820행 → 20,010행으로 키운 뒤, 양쪽 모두 `VACUUM FULL` + `ANALYZE` 후 비교했다
(`plan_cache_mode = force_generic_plan`).

| 쿼리 | board 820행 | board 20,010행 | 계획 변화 |
|---|---|---|---|
| `findLikedBoardsWithTime` | Nested Loop Anti Join · 6버퍼 | 같은 계획 · 5버퍼 | 없음 |
| `findLikedBoards` (보관함) | Nested Loop · 41버퍼 | Nested Loop · 40버퍼 | Bitmap Heap → Index Scan (`board_pkey`) |
| signature 조회 | `idx_board_signature_lookup` · 6버퍼 | 같은 인덱스 · 4버퍼 | 없음 |
| 인기 보드 (제네릭 강제) | Seq Scan · 15버퍼 · 0.167ms | Seq Scan · 392버퍼 · 2.571ms | 없음 (비용만 24배) |
| 인기 보드 (`auto`, 앱의 실제 경로) | Index Only Scan · 5버퍼 | Index Only Scan · 5버퍼 | 없음 |

**계획이 하나도 바뀌지 않는다.** V7·V8이 이미 비용을 board 크기와 무관한 형태로 만들어놨기
때문이다 — 재계산 쿼리는 좋아요 건수와 *삭제된* 보드 수에만 비례하고, signature 조회는
인덱스를 한 번 탄다. 보관함의 bitmap → index scan 전환도 같은 PK 조회를 다르게 포장한 것이라
버퍼가 그대로다.

board 크기에 비례해 자라는 것은 **인기 보드 하나뿐이고, 제네릭 플랜을 강제했을 때만** 그렇다.
`idx_board_popular`의 술어에 `visibility = 'PUBLIC'`이 있는데 앱은 이 값을 바인드로 넘기므로
제네릭 플랜에서는 술어를 증명하지 못한다. 그런데 `auto` 모드로 7회 실행해보면 PostgreSQL이
제네릭 플랜이 더 비싸다고 보고 **커스텀 플랜을 계속 쓴다.** 두 규모 모두 5버퍼다.
V5·V7에서 반복된 구도이고 결론도 같다 — **잠재적 위험이지 현재 병목이 아니다.**

> 교훈 둘. 첫째, **키울 축을 먼저 정하라.** 여기서 의미 있는 축은 board 행 수가 아니라
> 1인당 좋아요다(재계산이 O(이력)이므로). 전체 행 수만 키우면 3-2의 숫자는 꿈쩍도 하지 않는다.
> 둘째, **`force_generic_plan`은 최악의 경우지 현실이 아니다.** 그것만 보고 "풀스캔이 난다"고
> 단정하면 없는 문제를 고치게 된다. 4-2에서 한 번 저지른 실수다.

---

## 5. 취향 벡터 정책의 효과 측정

### 5-1. "이전 대비 개선"은 잴 수 없다

1장에서 정리한 이유에 하나가 더 겹친다 — **콘텐츠 벡터가 전부 더미 난수다**
(`ContentVectorSeeder`). 의미 없는 공간에서 추천 품질을 재면 숫자는 나오지만 뜻이 없다.

그래서 **지금 잴 것(메커니즘)**과 **AI 서버 연결 후에 잴 것(품질)**을 나눈다.

### 5-2. 지금 잴 수 있는 것 — 산식이 설계대로 도는가

**① 반영 지연** (4-1의 식). 정책 변경의 본질이 "지연"이므로 헤드라인 지표다.

> 실측: 무부하 p50 36ms. 좋아요 800건 폭주 상황에서도 배수 0.4초 이내.
> Qdrant 버전 불일치를 고치기 전에는 이 값이 **음수**였다(벡터가 가입 시각에 멈춰 있었다).

**② 재계산 비용 곡선.** 3-2 표.

> **구현됨 (2026-09-14).** `UserVectorMetrics`가 아래 넷을 내보낸다.
> 조회는 `GET /actuator/metrics/{이름}` — JWT 필요.
>
> | 지표 | 종류 | 뜻 |
> |---|---|---|
> | `crates.user.vector.stale.users` | Gauge | 마지막 좋아요보다 벡터가 오래된 사용자 수. **0이 정상** |
> | `crates.user.vector.recalculation` | Timer | 재계산 소요 시간. `result` 태그 = updated / unchanged / skipped |
> | `crates.user.vector.recalculation.boards` | Summary | 재계산 1건이 훑은 보드 수 |
> | `crates.user.vector.recalculation.failures` | Counter | 예외로 끝난 횟수 |
>
> 검증: 좋아요 3건 → `recalculation` COUNT 3 / TOTAL_TIME 0.445s / tag `updated`,
> `boards` TOTAL 465(평균 155). **보드당 0.96ms로 3-2 실측(약 1ms)과 일치한다.**
>
> 주의 둘. Timer는 메서드 본문까지만 재고 **커밋은 빠진다** — 끝에서 끝까지는 4-1의 식으로 봐야 한다.
> failures는 커밋 단계 예외를 못 센다(리스너 try/catch 밖에서 터진다) — `stale.users`와 함께 볼 것.
> 실패 카운터만 기동 시 0으로 미리 등록했다. Micrometer는 첫 사용 때 지표를 만들어서,
> 그냥 두면 "실패 없음"과 "계측 안 붙음"이 똑같이 '지표 없음'으로 보인다.

Prometheus 레지스트리는 없어 `/actuator/metrics` 인메모리 조회만 가능하다.

**③ 좋아요 한 번의 이동량.** `cos(V_이전, V_이후)`. 0에 가까우면 정책이 사실상 무효고,
1에 가까우면 과반응이다. `ai.user-vector.decay-alpha` 조정 시의 회귀 지표.

**④ 추천 결과 교체율.** 좋아요 전후 top-32의 겹침(overlap@32).

> ③④는 품질이 아니라 **산식 검증**이라 더미 벡터로도 유효하다.
> ④가 없어서 Qdrant 버전 불일치를 나흘 늦게 발견했다. 벡터가 전혀 변하지 않는데도
> 우는 지표가 하나도 없었다. **우선순위는 ④가 가장 높다.**

### 5-3. AI 서버 연결 후 — 품질

**오프라인 리플레이**가 가장 실용적이다. 배치가 구현된 적 없어도 흉내 내면 된다.

```
좋아요 N건 이상인 사용자를 시간순 정렬
  → 마지막 k건을 홀드아웃
  → 앞의 (N-k)건으로 벡터를 두 방식으로 계산
       (a) 마지막 하루 경계까지만 반영    ← 배치 시뮬레이션
       (b) 전부 반영                     ← 현행 실시간
  → 홀드아웃 보드의 콘텐츠가 top-K에 얼마나 들어오는가: Recall@K / NDCG 비교
```

핵심은 **산식을 동일하게 두고 반영 시점만 다르게** 하는 것이다. 그래야 "실시간"이라는
변수 하나만 분리된다.

**온라인 지표**(추천 노출 대비 좋아요율)는 분모가 없다. 5-4가 선행되어야 한다.

### 5-4. 선행 조건 — SystemLog 배선

노출 로그를 담을 자리는 **이미 설계돼 있으나 한 줄도 쓰이지 않는다.**

- `SystemLog`: `timestamp`, `userId`, `eventType`, `payload`(jsonb)
- `EventType`: `CLICK_BOARD`, `CLICK_CONTENT`, `UPDATE_BOARD`, `LIKE_BOARD`
- `SystemLogRepository`는 존재하지만 **어디서도 주입되지 않는다**

`userId`를 연관관계가 아니라 raw id로 들고 있어 탈퇴 후에도 남고, payload가 jsonb라 확장된다.
설계는 끝났고 배선만 빠졌다. AI 서버 연결 **전에** 해두어야 연결 직후부터 비교할 데이터가 쌓인다.

---

## 6. 다음 할 일

1. **`idx_board_signature_lookup` 제거 검토** (2026-09-19). 만들었지만 앱이 쓰지 않는다 —
   V5의 부분 유니크 인덱스가 커스텀 플랜으로 이미 최적 동작한다(3-1 정정).
   USER_CUSTOM 경로를 함께 측정한 뒤 제거 여부를 정할 것.
2. ~~재계산 계측~~ — **완료(2026-09-14).** `UserVectorMetrics` 참고(5-2 ②).
   남은 것은 **추천 결과 교체율(5-2 ④)**이다. 우선순위가 가장 높은데 아직 없다.
3. ~~Qdrant 4MB 한도~~ — **완료(2026-09-14).** 1,000개씩 분할 조회. 3-2 참고.
4. **추천 결과 교체율 지표(5-2 ④)** — 우선순위가 가장 높은데 아직 없다.
5. **재계산 비용 완화** — 4MB 한도는 막았지만 비용 곡선은 그대로다(3-2).
   이력 상한, 증분 방식, 또는 재계산 동시 실행 상한을 검토할 것.
   **부하테스트 인공물이 아니다** — 누적 좋아요 150건은 파워 유저가 실제로 도달하는 수치이고,
   그때마다 보드 150개를 훑는다. 남은 병목 중 가장 크다.
6. ~~`findLikedBoardsWithTime`의 board 풀스캔~~ — **완료(2026-09-18).** V8에서 조건을 뒤집어
   드문 쪽(삭제된 보드)을 인덱싱했다. board 쪽 비용 67버퍼 → 1버퍼. 3-1 ② 참고.
7. **로그인 실패 500 수정** (3-6).
8. `SystemLog` 배선 (5-4). AI 서버 연결 전에.
9. 나머지 부하테스트 (3-3 보관함 → 3-4 검색).

---

## 7. 미결정

- ~~자원 상한~~ / ~~bootRun 대신 jar~~ — **완료(2026-09-15).** 앱이 compose에서 돌고
  `cpus: '4'`, `memory: 3g`가 걸린다. 컨테이너는 `bootJar` 결과물을 실행하므로 devtools도 빠진다.
- **부하 생성기 분리.** 여전히 같은 노트북에 있다. 이게 남은 가장 큰 오염이고,
  컨테이너화로는 해결되지 않는다(2장).
- **메모리 상한의 근거.** 3GB는 "남는 만큼"이지 측정된 값이 아니다. 운영 사양이 정해지면
  그 값으로 맞출 것. Docker VM 할당(32GB 기계에서 7GB)이 선행 제약이다.
- **목표 수치.** 동시 사용자 규모 가정이 없어 합격선을 정하지 못했다.
  3-1을 고친 뒤의 값을 기준선으로 삼는 편이 낫다.
- 오프셋 → 키셋 페이징 전환 시점 (3-3).

---

## 8. 확인한 근거

| 주장 | 확인 방법 |
|---|---|
| 배치는 구현된 적 없음 | `grep -rln "JobBuilder\|StepBuilder\|@Scheduled" src/main/java` → 스케줄러 1개 |
| 이전 상태가 0 벡터 | `git log -p --all -S "0 벡터"` → `.userVector(new float[vectorDimension])` |
| Hikari 기본 10 | `application.yaml`에 `spring.datasource.hikari` 항목 없음 |
| 커넥션이 병목이 아니었음 | 폭주 중 `pg_stat_activity` 샘플링 → `idle` 평균 9.9 / `active` 최대 2 |
| 부분 인덱스가 안 먹음 | `SET plan_cache_mode = force_generic_plan` + `PREPARE`/`EXPLAIN` → Seq Scan |
| enum 리터럴이 파라미터로 바뀜 | 앱 로그 집계 → `board_type='USER_CUSTOM'` 0회, `board_type=?` 6,900회 |
| 대체 인덱스가 먹음 | `BEGIN; CREATE INDEX; EXPLAIN; ROLLBACK;` → Index Scan, buffers 74→2 |
| 1차: 재계산 실패·유실 0건 | 앱 로그에 재계산 오류 0건 + 4-2의 집계 쿼리 0명 |
| 2차: 재계산 실패 216건 | `crates_user_vector_recalculation_failures_total` / `stale_users` 20 |
| gRPC 4MB가 원인 | retrieveVectors를 1,200 / 1,360 / 1,488개로 직접 호출 → 경계 확인 |
| 분할 조회로 해결됨 | 보드 188건(벡터 1,504개) 재계산 21건 전부 updated, `stale_users` 0 |
| 인덱스는 정상이었음 | `EXPLAIN` → `Index Scan using idx_board_signature_lookup` |
| board_item seq scan은 정상 | `SET enable_seqscan=off` 비교 → 인덱스 경로가 버퍼 더 씀(322 vs 198) |
| 3컬럼 인덱스가 필요함 | 일회용 컨테이너에 같은 signature 3,000행 → Filter 2999행 제거, 버퍼 32 vs 4 |
| signature 조회는 해결됨 | `pg_stat_statements` → 3,000회 평균 0.017ms, 호출당 버퍼 2.5 |
| 남은 seq scan의 출처 | `findLikedBoardsWithTime`의 `EXPLAIN` → `Seq Scan on board (rows=3010)` |
| `deleted_at IS NULL` 부분 인덱스가 무효 | 일회용 컨테이너에 생성 후 `EXPLAIN` → 계획이 `Seq Scan` 그대로 |
| 역방향 인덱스가 유효 | 같은 환경 → `Index Only Scan using idx_board_deleted`, 480→8버퍼 |
| V8이 실제 DB에서 동작 | board 3,010행에서 board 쪽 67→1버퍼, 결과 159행 동일, 회차당 seq scan 600→1 |
| 이력 누적이 측정을 오염시킴 | 계정 재사용 시 훑은 보드 75.6→165.5건, 재계산 157→424ms (4-9) |
| 3컬럼 인덱스가 안 쓰임 | 같은 컨테이너에서 인덱스만 넣고 빼 A/B → TPS 차이 없음 |
| 앱이 쓴 인덱스 | `pg_stat_user_indexes` → uk_board_ai_signature 2,953회 / lookup 1회 |
| V5 부분 인덱스가 최적 | signature 조회 3,000회 평균 0.0089ms, 회당 버퍼 1 |

> 위 `부분 인덱스가 안 먹음` 항목은 `force_generic_plan`을 전제로 한 것이다. 애플리케이션이
> 그 상태에 도달하지 않으므로, 관찰로는 유효하지만 **결론의 근거로는 부족했다**(4-7).
| 자원 상한이 실제로 걸림 | `docker inspect backend_server` → NanoCpus 4e9, Memory 3GiB |
| 상한이 병목으로 작동 | 부하 중 `docker stats` → backend 최대 404.2% (4코어에 붙음) |
| Qdrant는 부하 중 한가함 | 같은 샘플링 → qdrant 평균 19.9%. 797%는 시딩 중 수치였다 |
| 앱 메모리가 병목 아님 | 부하 중 880MiB / 3GiB (29%) |
| `SystemLog` 미사용 | `grep -rn "SystemLogRepository" src/main/java` → 선언 파일 외 참조 0건 |
| 콘텐츠 191,239건 | Qdrant `content_vector` points_count, V6 마이그레이션 주석 |
| pg_trgm 실측치 | `V6__ContentTitleSearch.sql` 주석 |

관련 문서: [board-schema.md](board-schema.md)
