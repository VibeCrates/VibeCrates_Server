# Board 도메인 설계

보드 5종을 **단일 `board` 테이블**로 표현하기 위한 스키마와 결정 근거를 정리한다.
결정의 배경(왜 다른 안을 버렸는지)까지 남기는 이유는, 나중에 같은 논의를 반복하지 않기 위해서다.

---

## 1. 배경 — 왜 테이블을 나누지 않았나

보드 종류가 5개라서 `user_board` / `ai_board`로 나누는 안을 먼저 검토했고, 다음 이유로 버렸다.

- **인기 보드 랭킹이 깨진다.** 좋아요 상위 정렬은 전체 보드를 대상으로 하는데, 테이블이 갈리면
  UNION 후 재정렬이라 인덱스를 못 타고 페이징도 불가능해진다.
- **`board_feedback`이 FK를 걸 곳을 잃는다.** 좋아요는 보드 종류를 가리지 않는다. 대상 테이블이
  여러 개면 polymorphic FK(= FK 제약 포기)로 가야 하고, 지금 `unique(board_id, user_id)`로
  좋아요 연타를 DB가 막고 있는 방어가 통째로 사라진다.
- **종류가 늘 때마다 테이블이 는다.**

테이블 분리가 정당한 것은 **컬럼 집합이 근본적으로 다를 때**다. 5종은 전부
`제목 + 콘텐츠 8건 + 좋아요 수`로 구조가 같고, 다른 것은 *만들어진 경위*뿐이다.
그건 컬럼 하나로 표현되는 값이지 테이블을 가를 근거가 아니다.

실제 문제는 테이블이 하나여서가 아니라 **세 축이 한 필드에 뭉개져 있어서** 생긴 것이었다.

| 축 | 질문 | 담당 컬럼 |
|---|---|---|
| 출처 | 어떻게 만들어졌나 | `board_type` |
| 귀속 | 누구 것인가 | `user_id` |
| 공개 | 남에게 보여도 되나 | `visibility` |

---

## 2. 스키마

```sql
CREATE TABLE board (
    id                BIGSERIAL PRIMARY KEY,
    board_type        VARCHAR(20)  NOT NULL,   -- PRE_MADE | AI_RECOMMEND | USER_CUSTOM
    user_id           BIGINT       NULL     REFERENCES member(id),
    visibility        VARCHAR(10)  NOT NULL,   -- PUBLIC | PRIVATE
    title             VARCHAR(255) NULL,
    content_signature VARCHAR(160) NOT NULL,
    like_count        BIGINT       NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL,
    deleted_at        TIMESTAMP    NULL,       -- 소프트 삭제

    -- USER_CUSTOM이면 소유자 필수, 나머지는 반드시 NULL
    CONSTRAINT ck_board_owner
        CHECK ((board_type = 'USER_CUSTOM') = (user_id IS NOT NULL)),

    -- PRIVATE은 USER_CUSTOM만 가능
    CONSTRAINT ck_board_visibility
        CHECK (visibility = 'PUBLIC' OR board_type = 'USER_CUSTOM')
);

-- AI_RECOMMEND는 소유자가 없는 전역 공용 보드다. 콘텐츠 구성이 같으면 같은 보드 (전역 유일)
CREATE UNIQUE INDEX uk_board_ai_signature ON board (content_signature)
    WHERE board_type = 'AI_RECOMMEND' AND deleted_at IS NULL;

-- USER_CUSTOM은 사용자 1명당 같은 구성의 보드를 1개만 가질 수 있다
CREATE UNIQUE INDEX uk_board_user_signature ON board (user_id, content_signature)
    WHERE board_type = 'USER_CUSTOM' AND deleted_at IS NULL;

-- 인기 보드: 정렬을 인덱스로 해결하고 id로 전순서를 확정한다
CREATE INDEX idx_board_popular ON board (like_count DESC, id)
    WHERE visibility = 'PUBLIC' AND deleted_at IS NULL;

-- 내가 만든 보드
CREATE INDEX idx_board_owner ON board (user_id, board_type)
    WHERE deleted_at IS NULL;
```

### board_item

콘텐츠 배치 순서를 담기 위해 `slot_no`를 추가한다. 보드는 2행 4열로 렌더링된다.

```
1 2 3 4
5 6 7 8
```

```sql
ALTER TABLE board_item ADD COLUMN slot_no SMALLINT NOT NULL;

-- 슬롯은 1~8만 유효
ALTER TABLE board_item ADD CONSTRAINT ck_board_item_slot
    CHECK (slot_no BETWEEN 1 AND 8);

-- 한 보드에 같은 슬롯이 둘이면 렌더링이 깨진다
CREATE UNIQUE INDEX uk_board_item_slot ON board_item (board_id, slot_no);

-- 한 보드에 같은 콘텐츠가 두 번 들어가면 signature가 "1,1,2,..."가 되어 8건 규칙이 무너진다
CREATE UNIQUE INDEX uk_board_item_content ON board_item (board_id, content_id);
```

**`slot_no`는 모든 보드 타입이 갖는다** (NOT NULL). 사용자가 직접 정할 수 있는 것은
`USER_CUSTOM`뿐이고, 나머지는 생성 시점에 자동으로 채운다.

| 타입 | slot_no 결정 방식 |
|---|---|
| `USER_CUSTOM` | 사용자가 배치 |
| `AI_RECOMMEND` | Qdrant 유사도 순 (1위 → slot 1) |
| `PRE_MADE` | 운영자가 정한 순서 |

nullable로 두면 조회 시 정렬 기준이 사라져 순서가 매번 달라진다. 값이 없는 보드를 허용하지 않는다.

> **컬럼명 주의**: `position`은 SQL 표준 함수명(`POSITION(x IN y)`)이라 헷갈리기 쉽고,
> `order`는 확실한 예약어다. `slot_no`를 쓴다.

> **주의**: 부분 유니크 인덱스(`WHERE` 절)와 `CHECK` 제약은 JPA 애노테이션으로 표현할 수 없다.
> 현재 `ddl-auto: create`로 스키마를 만들고 있으므로, 이 제약들은 **Flyway 마이그레이션으로
> 따로 추가**해야 한다. 엔티티에만 규칙을 적어두면 DB가 지켜주지 않는다.

### 필드 규칙

| 필드 | 규칙 |
|---|---|
| `board_type` | `PRE_MADE` / `AI_RECOMMEND` / `USER_CUSTOM` 3종. **`SEARCH`는 별도 타입이 아니라 `AI_RECOMMEND`에 통합** (3-2 참고). → 2026-10-27 결정으로 **`SEARCH_RECOMMEND` 추가** (11장) |
| `user_id` | `USER_CUSTOM`일 때만 값. 나머지는 NULL(= 전역 공용, 소유자 없음). → 2026-10-27 결정: 삭제된 `USER_CUSTOM`도 NULL (11-6) |
| `visibility` | `USER_CUSTOM`만 `PRIVATE` 선택 가능. 기본은 `PUBLIC`. 나머지 타입은 항상 `PUBLIC` |
| `content_signature` | 콘텐츠 8건의 id를 **오름차순 정렬해 콤마로 이은 문자열**. 해시하지 않음 (3-1). 보드 수정이 가능하므로 불변이 아니다 — 콘텐츠가 바뀌면 반드시 재계산 (9-4) |
| `like_count` | 원자적 증감(`UPDATE ... SET like_count = like_count ± 1`). 엔티티에 setter를 열지 않는다 |

### 보드 종류별 정리

| 종류 | `board_type` | `user_id` | `visibility` | 저장 시점 |
|---|---|---|---|---|
| 서비스 초기 큐레이션 | `PRE_MADE` | NULL | PUBLIC | 운영 시작 전 미리 |
| 사용자 벡터 기반 추천 | `AI_RECOMMEND` | NULL | PUBLIC | **좋아요 받을 때만** |
| 검색어 기반 보드 | `AI_RECOMMEND` → **`SEARCH_RECOMMEND`** (11장) | NULL | PUBLIC | **좋아요 받을 때만** |
| 사용자 제작 | `USER_CUSTOM` | 만든 사용자 | 사용자 선택 | 생성 즉시 |
| 좋아요 많이 받은 보드 | — | — | — | 별도 종류가 아니라 **위 보드들의 `like_count` 정렬 결과** |

"인기 보드"만 층위가 달랐던 것이 컬럼으로 내려오면서 자연히 해소됐다.

---

## 3. content_signature

### 3-1. 해시하지 않는 이유

모든 보드가 콘텐츠 **8건 고정**이므로 정렬한 id를 문자열 그대로 저장한다.

```
content_signature = "12,45,88,102,377,891,1204,2038"
```

`bigint` 최대 19자리 × 8 + 구분자 7 = 159자이므로 `VARCHAR(160)`이면 어떤 경우에도 안전하다.
(현재 데이터는 book 12만 + movie 4만 + music 190만 ≈ 206만 건이라 7자리씩, 실제로는 64자 내외)

해시를 피한 이유는 하나다. **`AI_RECOMMEND`에 unique 제약이 걸려 있어서, 해시가 충돌하면
콘텐츠가 다른 두 보드가 잘못 병합된다.** raw 문자열이면 충돌이 원천적으로 0이고,
디버깅할 때 눈으로 읽을 수 있다는 덤도 있다.

**정렬은 필수다.** 두 가지 이유가 겹친다.

1. Qdrant의 HNSW는 exact search가 아니라 근사(ANN) 검색이라, 같은 벡터로 조회해도 top-8의
   *순서*가 흔들릴 수 있다. 정렬하지 않으면 순서만 뒤바뀌어도 매번 새 보드가 쌓인다.
2. `USER_CUSTOM` 보드는 사용자가 배치 순서(`slot_no`)를 바꿀 수 있는데, **순서가 바뀌어도
   내부 콘텐츠가 같으면 같은 보드로 취급**해야 한다. signature가 정렬 기반이므로 이 요구가
   자동으로 충족된다 — 순서만 바꾼 수정은 signature가 변하지 않아 새 보드가 생기지 않는다.

즉 `content_signature`는 **콘텐츠 집합**을 나타내고, `slot_no`는 **표시 순서**를 나타낸다.
두 관심사가 분리돼 있다.

### 3-2. 제목(query)을 signature에 넣지 않는 이유

`SEARCH`를 `AI_RECOMMEND`에 통합하면서 "제목이 출처별로 다른데 행은 하나뿐"이라는 문제를
검토했으나, **제목 정책(4장) 자체가 이 문제를 해소한다.** 제목이 콘텐츠 8건에서 결정론적으로
파생되므로 같은 콘텐츠면 어느 경로로 들어와도 같은 제목이 나온다.

오히려 signature에 query를 넣으면 손해다.

```
현재:  {1..8} → 평균벡터 V → 최근접 query "재즈 명반"  → 저장 (좋아요 40)
query "모던 재즈 입문" 추가 → V에 더 가까움
이후:  {1..8} → 평균벡터 V → 최근접 query "모던 재즈 입문"

signature에 query 포함 → "1,..,8|모던재즈입문" ≠ "1,..,8|재즈명반"
  → 콘텐츠가 완전히 같은 보드가 둘로 갈라진다
  → 좋아요 40과 신규 좋아요가 분산되어 인기 랭킹이 흐려진다
```

평균벡터 → query 매칭도 HNSW 근사 검색이라, signature에 query가 있으면 그 흔들림이
그대로 보드 분열로 이어진다. **signature는 콘텐츠 8건만으로 구성한다.**

### 3-3. 중복 허용 범위

> 2026-10-27 결정으로 바뀌었다: `USER_CUSTOM` 같은 사용자는 **제목+구성**이 같을 때만 불가,
> `SEARCH_RECOMMEND`는 **제목+구성** 전역 unique. 11-2, 11-5 참고.

| 대상 | 동일 signature 허용? | 근거 |
|---|---|---|
| `AI_RECOMMEND` 내부 | **불가** (전역 unique) | 소유자가 없는 전역 공용 보드다. 하나로 모아야 좋아요가 분산되지 않는다 |
| `USER_CUSTOM` — 같은 사용자 | **불가** (사용자별 unique) | 한 사람이 같은 구성의 보드를 여러 개 가질 이유가 없다 |
| `USER_CUSTOM` — 다른 사용자 | 허용 | 사용자 창작물이다. 콘텐츠가 같아도 제목·의도가 다른 별개의 물건이다 |
| 타입 간 (`USER_CUSTOM` ↔ `AI_RECOMMEND` ↔ `PRE_MADE`) | 허용 | 병합하면 **소유권이 전염된다**(아래) |

**타입 간 병합을 하지 않는 결정적 이유:**

```
A가 '새벽에 혼자 듣는 것' 보드 제작 (PUBLIC, owner=A)
B가 검색 → 콘텐츠가 우연히 동일 → 병합 규칙에 따라 A의 보드에 좋아요
A가 보드를 PRIVATE로 전환하거나 삭제
  → B가 좋아요한 보드가 B의 목록에서 사라진다
```

전역 공용이어야 할 `AI_RECOMMEND` 보드가 A 한 사람의 처분에 종속된다.
**콘텐츠 구성이 같다고 해서 같은 보드가 아니다.**

---

## 4. 제목 정책

| 종류 | 제목 |
|---|---|
| `USER_CUSTOM` | 사용자가 직접 작성. 변경하지 않고 유지할 수도 있음 |
| `AI_RECOMMEND` (추천 보드) | 취향 벡터와 가까운 `query` 중 **서로 다른 것을 먼저 고르고**, 그 제목에 맞는 콘텐츠를 담는다 (Qdrant `query_vector` 컬렉션, 2026-10-27 변경) |
| `AI_RECOMMEND` (검색 보드) → `SEARCH_RECOMMEND` | 사용자가 입력한 **검색어 그대로** (NFC 변환과 앞뒤 공백 제거만, 11-3) |
| `PRE_MADE` | 운영자가 작성 |

`query`와 `query_vector`는 AI 개발자가 서비스 운영 전에 Qdrant에 적재한다.

- 사용자 벡터 기반 추천(홈): **제목을 먼저 고르고 콘텐츠를 담는다**(아래 "홈 추천 보드 만드는 순서")
- 검색 보드: 검색어 벡터로 콘텐츠 8건 조회 → 제목은 검색어 그대로. 사용자가 직접 무엇을 찾았는지가
  이 보드를 가장 잘 설명한다(2026-10-24 변경, 그전에는 추천 보드와 같은 query 매칭)

**저장된 보드의 제목은 저장 시점 값으로 고정한다.** 나중에 query가 추가되어 더 가까운 것이
생겨도 바꾸지 않는다. 좋아요 40개는 "재즈 명반"이라는 이름의 보드에 붙은 것이고,
제목이 어느 날 바뀌면 그 좋아요가 무엇에 대한 것이었는지 흐려진다.

### 홈 추천 보드 만드는 순서 (2026-10-27 변경)

예전에는 취향 벡터와 가까운 콘텐츠 32건을 순서대로 8건씩 잘라 보드 4장을 만들고, 보드마다 콘텐츠 평균 벡터와
가장 가까운 query를 제목으로 붙였다. 4장이 한 취향 벡터의 순위 구간이라 내용이 거의 같았고(보드 평균끼리 유사도
약 0.85~0.9), 그래서 제목도 자주 겹쳤다(합성 사용자 30명, 보드 120장 중 10장). AI 개발자 제안으로 순서를 뒤집었다.

1. **제목 4개** — 취향 벡터와 가까운 query 200개를 벡터와 함께 받아 MMR로 서로 다른 4개를 고른다
   (`BoardTitleService.distinctTitlesNear`). 점수 = 취향 유사도 − 0.7 × 이미 고른 제목들과의 최대 유사도.
   한국어 문자열이 같은 후보는 건너뛴다(영어 원문이 달라도 번역이 같은 query가 많다).
2. **보드마다 기준점** — `0.7 × 제목 벡터 + 0.3 × 취향 벡터`. 제목은 차별화하되 취향에서 너무 멀어지지 않게 한다.
3. **콘텐츠 8건** — 기준점 4개 × 종류 3개의 검색을 **묶음 요청 한 번**으로 보낸다. 종류마다 `장당 개수 × 4`를
   받아, 앞 보드에 담긴 콘텐츠는 서버에서 빼고 다음 후보로 채운다(`ContentVectorService.findDistinctBoardsByType`).
   이만큼 받으면 겹친 것을 다 빼도 모자라지 않는다. 그래도 8건을 못 채운 보드는 뺀다.

**저장된 보드는 저장 시점의 제목을 그대로 쓴다**(위 원칙). 같은 구성의 보드가 저장돼 있으면 새로 고른 제목 대신
그 제목이 나간다.

측정(로컬, 합성 사용자 각 20~30명, 2026-10-27):

| | 예전 | 지금 (취향 비중 0.3) |
|---|---|---|
| 한 화면 제목 중복 | 120장 중 10장 | 0 |
| 보드끼리 유사도 (한 취향 사용자) | 0.908 | 0.746 |
| 콘텐츠와 취향의 유사도 (한 취향 사용자) | 0.705 | 0.657 |
| 고른 제목의 후보 순위 | — | 중앙 46위, 90% 156위, 최대 190위 |

- 한 취향 사용자 = 콘텐츠 하나와 그 근처 8건으로 만든 취향. 무작위 8건으로 만든 취향은 흩어져 있어 취향 유사도가
  0.530 → 0.435로 더 떨어진다. 취향 비중을 0.5로 올리면 0.486, 0.7이면 0.515(대신 보드끼리 0.58, 0.71로 비슷해진다).
- 후보 200개는 실제로 끝까지 쓰인다(최대 190위). 줄이면 서로 다른 제목을 못 찾는다.

### 부수 효과 — Qdrant 호출이 2회

홈 진입마다 **제목 후보 검색 1번 + 콘텐츠 묶음 검색 1번**으로 Qdrant를 두 번 친다(예전과 같은 횟수).
콘텐츠 검색 수는 3개에서 12개로 늘었지만 한 요청으로 묶었고, 제목 후보 200개는 벡터까지 받아 응답이 약 0.6MB다.
6장의 캐시가 그만큼 중요하다. 처리 한계는 다시 재야 한다. 검색 보드는 제목이 검색어라 콘텐츠 검색 한 번이다.

---

## 5. 인기 보드 조회

```sql
SELECT ... FROM board
 WHERE visibility = 'PUBLIC' AND deleted_at IS NULL
 ORDER BY like_count DESC, id ASC
 LIMIT 16;
```

애플리케이션에서 같은 `content_signature`를 묶어 하나만 남기고 **앞 8개**를 응답한다.

### 5-1. 중복 제거 우선순위

> 2026-10-27 결정으로 `SEARCH_RECOMMEND`가 들어가고, 구성이 다른 보드 사이의 정렬에도 타입이
> 끼어든다. 11-7 참고.

같은 signature가 둘 이상 뽑혔을 때만 적용한다. 세 단계로 판단한다.

1. **타입 우선순위** — `USER_CUSTOM` > `AI_RECOMMEND` > `PRE_MADE`.
   사용자 창작물이 알고리즘·운영자 보드보다 앞선다. 좋아요 수와 무관하게 이 단계가 먼저다.
2. **같은 타입이면 좋아요가 많은 쪽.** 인기 보드 목록에서 적게 받은 쪽을 남길 이유가 없다.
3. **좋아요까지 같으면 먼저 만들어진 쪽**(`id`가 작은 쪽).

> 후보는 이미 좋아요 순으로 정렬돼 들어오지만, 비교 함수는 그 순서에 기대지 않는다.
> 호출하는 쪽의 정렬이 바뀌면 조용히 다른 보드가 뽑히기 때문이다.

> 이 우선순위는 **뽑힌 16개 안에서만** 적용된다. top-16 밖의 보드를 끌어올리지 않는다.
> 즉 좋아요가 압도적인 `AI_RECOMMEND` 보드 A만 목록에 들고 같은 콘텐츠의 `USER_CUSTOM` 보드 B가
> 밖에 있다면, A를 빼고 B를 넣지 않는다. A가 그대로 남는다.

### 5-2. `LIMIT 16`인 이유

중복 제거로 결과가 8개 미만이 되는 것을 막기 위한 여유분이다. 중복 자체가 드물 것이므로
2배면 충분하다. 부족한 채로 내려보내는 것보다, 다음 순위로 채우는 편이 API 계약상 설명하기 쉽다.

### 5-3. `ORDER BY`에 `id`를 넣는 이유

SQL의 `ORDER BY`는 지정한 컬럼이 모두 같은 행들 사이의 순서를 **보장하지 않는다.**
`like_count`는 초기 서비스에서 0~2에 몰리므로 **`LIMIT 16`의 경계가 동률일 확률이 사실상 100%** 다.
그러면 "16개 중 어느 16개인지"가 실행마다 달라질 수 있고, 그 결과 5-1의 중복 제거 결과까지
흔들린다. 데이터는 그대로인데 새로고침마다 다른 보드가 뜨게 된다.

특히 좋아요는 `UPDATE`인데, PostgreSQL은 MVCC라 UPDATE가 제자리 수정이 아니라 **새 튜플 버전을
힙 뒤쪽에 쓴다.** 행의 물리적 위치가 바뀌므로 seq scan이 읽는 순서도 바뀐다.
즉 **좋아요가 하나 눌릴 때마다 동률 그룹의 내부 순서가 뒤집힐 수 있다.**

`id`는 PK라 유일하므로 맨 뒤에 붙이면 전순서가 확정된다. `idx_board_popular`에 `id`가
포함돼 있어 정렬 연산 없이 인덱스를 앞에서부터 읽으므로 **추가 비용은 없다.**

`created_at`은 정렬에 넣지 않는다. "먼저 만들어진 것 우선"은 5-1의 중복 제거에서만 쓰는 규칙이고,
`id`가 어차피 생성 순서라 방향이 같다.

---

## 6. 캐시

`user_vector`와 콘텐츠는 최소 하루 1회 갱신되므로 캐시 적중률이 높다.

| 대상 | 키 | 비고 |
|---|---|---|
| 사용자 추천 보드 | `rec:user:{userId}:{vectorUpdatedAt}` | `UserVector.updatedAt`을 키에 넣어 **TTL 없이 자연 무효화**. 벡터가 갱신되면 키가 바뀐다 |
| 검색 보드 | `search:{keyword}` | 검색어는 무한히 늘어나므로 **TTL 필수** |

캐시에는 보드별 콘텐츠 8건과 확정된 제목을 함께 담는다(제목도 Qdrant 호출이 필요하므로).

---

## 7. 콜드 스타트

**가입 직후 사용자가 콘텐츠를 1~10개 고르고, 그 평균이 취향 벡터의 시작점이 된다**
(`POST /api/users/me/initial-contents` → `UserVectorService.initialize`). 가입은 OAuth만 받는다.

`user_vector` 행은 이때 처음 생긴다. 고르기 전에는 행이 없고, 프론트가 콘텐츠 선택 화면
(`/onboarding`) 밖으로 나가지 못하게 막는다. 백엔드도 벡터가 없는 사용자의 추천 요청은 거절한다.

행에는 벡터 `user_vector` 한 칸과, 가입 때 고른 콘텐츠 id `initial_content_ids`가 있다(V2__UserSchema).

좋아요가 바뀔 때마다 **좋아요한 보드들 + 고른 콘텐츠**로 처음부터 다시 계산한다. 고른 콘텐츠의 평균은
가장 오래된 좋아요 날짜 다음 순위의 한 표로 들어가, 좋아요 이력이 쌓일수록 영향이 줄어든다.
좋아요가 0개가 되면 고른 콘텐츠만으로 다시 계산하므로 가입 직후와 같은 값으로 돌아간다.

평균 벡터가 아니라 id를 저장하는 이유: 콘텐츠 벡터가 새 모델로 바뀌면 좋아요 쪽은 새 벡터로 다시 계산되는데,
저장해 둔 평균값만 옛 모델로 남아 두 모델의 벡터가 한 평균에 섞인다. id를 두면 계산할 때마다 현재 벡터를 읽는다.

---

## 8. 저장 시점

| 종류 | 저장 |
|---|---|
| `USER_CUSTOM` | 좋아요와 무관하게 **생성 즉시** |
| `AI_RECOMMEND` | **좋아요를 받은 시점에만** |
| `PRE_MADE` | 운영 시작 전 미리 |

`AI_RECOMMEND`를 미리 저장하지 않는 이유는 저장공간이다. 사용자 1만 명에게 매일 보드를
저장하면 `board` 연 365만 행, `board_item` 연 2,920만 행인데 그중 좋아요를 받는 것은 극소수다.

> **2026-10-27 결정으로 바뀌었다:** `AI_RECOMMEND`와 `SEARCH_RECOMMEND`는 좋아요가 0이 되면
> **완전 삭제**한다. 아래 문단은 이전 결정과 그 근거다. 11-6 참고.

**좋아요가 0이 되어도 행은 유지한다.** soft delete하면 unique 인덱스의 `deleted_at IS NULL`
조건 때문에 같은 signature가 다시 저장 가능해져서, 같은 내용의 보드가 삭제/생성을 반복하며
오히려 행이 누적된다. 남겨두면 다음에 같은 구성이 나왔을 때 그 행이 재사용된다.

---

## 9. 보드 수정

**사용자에게 보여지는 모든 보드는 수정 가능하다.** 다만 원본의 소유 상태에 따라 두 갈래로 갈린다.

| 수정 대상 | 콘텐츠 교체 | 순서(`slot_no`)·제목만 변경 |
|---|---|---|
| `USER_CUSTOM` (본인 것) | **in-place** | **in-place** |
| 그 밖의 보드 (추천·검색 보드, 남의 보드, 주인이 지운 보드) | **원본은 두고 내 보드로 새로 저장** | **원본은 두고 내 보드로 새로 저장** |

> **용어.** 예전에는 둘째 줄을 "복제"라고 불렀다. 콘텐츠를 바꾸면 구성이 원본과 달라져 결과는 사본이 아니라
> 원본을 출발점으로 삼은 **새 보드**다. 그래서 "원본은 두고 내 보드로 새로 저장"이라고 부른다(2026-10-27).
> 순서·제목만 바꾼 경우에는 구성이 원본과 같아 결과가 사본에 가깝지만, 동작은 같으므로 같은 이름을 쓴다.

콘텐츠를 바꾸든 순서만 바꾸든 판단 기준은 같다 — **원본에 소유자가 있는가.**
`AI_RECOMMEND`와 `PRE_MADE`는 `user_id = NULL`인 전역 공용 보드라, 한 사용자가 순서를 바꾸면
그 보드를 좋아요한 다른 모든 사용자의 화면이 함께 바뀐다. 콘텐츠 교체와 똑같은 소유권 전염이다.

순서만 바꿔 새로 저장하면 새 보드의 `content_signature`가 원본과 **같다.** 그래도 저장에 문제가 없다.
unique 인덱스가 타입별로 분리돼 있어(`uk_board_ai_signature` / `uk_board_user_signature`)
서로 충돌하지 않는다. 단, 그 사용자가 이미 제목과 구성이 같은 보드를 갖고 있으면 거절된다(9-2, 11-5).

### 9-1. 왜 in-place 전환이 아니라 새로 저장인가

`AI_RECOMMEND`와 `PRE_MADE`는 `user_id = NULL`인 **전역 공용** 보드다. 여러 사용자가 좋아요를
눌렀을 수 있다. `board_type`을 `USER_CUSTOM`으로 바꾸고 `user_id`를 채우는 방식이라면:

```
AI_RECOMMEND 보드 42 (signature "1..8", likeCount=40)  ← 40명이 좋아요
사용자 A가 콘텐츠 1건 교체 → in-place 전환

  → 40명이 좋아요한 공용 보드가 A의 개인 보드로 변신한다
  → 나머지 39명은 자기가 좋아요한 보드의 내용이 A에 의해 바뀌고,
     A가 PRIVATE로 돌리면 목록에서 사라진다
```

이것은 3-3에서 타입 간 병합을 막았던 **소유권 전염**과 같은 문제다. 병합은 막아놓고 수정으로
뚫리면 의미가 없다.

`likeCount = 1`(수정하는 본인뿐)인 경우에 한해 in-place를 허용하는 안도 검토했으나 채택하지
않았다. 조건 분기를 두면 "어제는 in-place였는데 오늘은 새로 저장"이 되어 동작을 설명하기 어렵고,
그 사이 누가 좋아요를 누르면 케이스가 뒤집힌다. **조건 없이 항상 내 보드로 새로 저장한다.**

주인이 지운 보드를 좋아요한 사람이 고칠 때도 같다(2026-10-27 확정). 고친 사람에게 원본을 넘기면 다른 좋아요한
사람들의 보드 내용이 바뀌고, 남이 모은 좋아요를 그대로 물려받아 인기 목록에 오를 수 있다.

### 9-2. 새로 저장 규칙

| 대상 | 처리 |
|---|---|
| 원본 보드 | **변경 없음.** `like_count`, `board_feedback` 모두 그대로 |
| 원본에 눌렀던 본인 좋아요 | **그대로 유지.** 새 보드로 옮기지 않는다 |
| 새 보드 `board_type` | `USER_CUSTOM` |
| 새 보드 `user_id` | 수정한 사용자 |
| 새 보드 `visibility` | `PUBLIC` (사용자 제작 기본값, 이후 변경 가능) |
| 새 보드 `like_count` | `0` |
| 새 보드 `title` | 원본 제목을 초기값으로 가져온다. `USER_CUSTOM`이므로 사용자가 바꿀 수 있다 |
| 새 보드 `content_signature` | 수정된 콘텐츠 8건으로 새로 계산 |
| 새 보드 `board_item.slot_no` | 사용자가 배치한 순서 |

**저장 전 `uk_board_user_signature` 검사가 필요하다.** 수정 결과가 그 사용자가 이미 가진
다른 보드와 같은 구성이 되면 unique 위반이다. 이는 정상적인 거절이므로
`"이미 같은 구성의 보드를 갖고 계세요"`로 400을 내려주고, 프론트도 이 분기를 처리해야 한다.

> ⚠️ **in-place 수정에서는 이 검사에서 자기 자신을 제외해야 한다.**
> 순서만 바꾼 수정은 signature가 그대로이므로, 자기 행까지 포함해 조회하면
> "이미 같은 구성의 보드가 있습니다"로 자기 자신 때문에 거절된다.
> `WHERE user_id = ? AND content_signature = ? AND id <> ?` 형태여야 한다.

> 콘텐츠를 하나도 바꾸지 않고 새로 저장하는 것은 막지 않는다. 타입별로 unique가 분리돼 있어
> 원본과 충돌하지 않고, 새 보드는 `like_count = 0`이라 인기 보드 랭킹에 영향을 주지 않는다.

### 9-3. 8건 고정 — 수정은 교체(swap)만 가능하다

모든 보드가 콘텐츠 8건 고정이므로 추가/삭제는 성립하지 않는다. 현재 엔티티의 메서드는
이 규칙과 맞지 않는다.

```java
// Board.java — 현재. 단독 호출하면 8건 규칙이 깨진다
public void addContent(Content content)     // 호출하면 9건
public void removeContent(Content content)  // 호출하면 7건
```

→ `replaceContents(List<Content>)` 하나로 받아 **8건 검증 + `slot_no` 부여 + signature 재계산**을
함께 처리한다. API도 개별 추가/삭제가 아니라 8건 전체를 받는 형태가 규칙과 맞는다.

```
PUT /api/boards/{boardId}/contents
{ "contentIds": [ 88, 12, 377, 45, 2038, 102, 891, 1204 ] }
```

**배열의 인덱스가 곧 `slot_no`다** (0번째 → slot 1). 순서 정보를 별도 필드로 받지 않는다.
콘텐츠 교체와 순서 변경이 같은 API로 처리되므로, 프론트는 "무엇이 바뀌었는지" 판단할 필요 없이
현재 화면 상태의 8건을 순서대로 보내면 된다. 어느 쪽이 바뀌었는지는 서버가 signature 비교로 안다.

### 9-4. signature 재계산 책임은 엔티티에 둔다

수정이 가능해지면서 `content_signature`가 더 이상 불변이 아니다. 갱신을 빠뜨리면
**unique 제약과 인기 보드 중복 제거가 조용히 틀어진다.** 잘못된 값이 들어가도 즉시 에러가 나지
않고 나중에 이상한 증상으로만 드러나므로, 방어를 엔티티 안에 둔다.

`content_signature`는 `boardItems`에서 파생되는 값이다. 파생 관계를 엔티티가 보장하는 것이
불변식(invariant) 관점에서 옳고, 서비스에 맡기면 호출을 빠뜨릴 여지가 생긴다.

**`@PreUpdate`는 사용하지 않는다.** `boardItems`는 컬렉션이라 자식만 바뀌면 `Board` 엔티티
자체가 dirty로 잡히지 않을 수 있다. 특히 `orphanRemoval`로 항목만 교체될 때 콜백이 타지 않는다.
콘텐츠를 바꾸는 메서드(`replaceContents`) 안에서 직접 다시 계산하는 편이 확실하다.

---

## 10. 현재 코드와의 차이 (마이그레이션 대상)

### 백엔드

| 위치 | 현재 | 변경 |
|---|---|---|
| `entity/board/Board.java` | `user` 필드 주석이 서로 모순 ("PRE_MADE는 null 허용" vs "USER_CUSTOM만 null") | `visibility`, `content_signature` 추가. 소유 규칙을 CHECK 제약으로 이관 |
| `entity/board/Board.java` | `addContent()` / `removeContent()` — 8건 규칙이 깨지는 경로 | `replaceContents(List<Content>)`로 대체. 8건 검증 + signature 재계산 (9-3, 9-4) |
| 신규 | — | 보드 수정 API `PUT /api/boards/{boardId}/contents`. 원본 타입에 따라 in-place / 내 보드로 새로 저장 분기 (9장) |
| `enumData/BoardType.java` | `USER_CUSTOM, AI_RECOMMEND, PRE_MADE` | 그대로 (SEARCH 추가하지 않음) → 2026-10-27 결정으로 `SEARCH_RECOMMEND` 추가 (11장) |
| `repository/BoardRepository.java` | `findByDeletedAtIsNullOrderByLikeCountDesc` — 정렬 기준이 `like_count` 하나, `visibility` 필터 없음 | `visibility='PUBLIC'` 필터 + `ORDER BY like_count DESC, id ASC` + `LIMIT 16` |
| `service/BoardService.java` | `likeNewBoard()`가 중복 검사 없이 무조건 `save()` | signature로 기존 보드 조회 후 있으면 좋아요만, 없으면 저장 |
| `service/SearchService.java` | `BoardWithContentsDto.unsaved()` 하드코딩 — 항상 `boardId=null, likeCount=0, liked=false` | signature로 기존 보드 조회해 `boardId`·`likeCount`·`liked` 복원 |
| `service/RecommendationService.java` | `List<ContentResponseDto>` 반환 | `BoardWithContentsDto` 반환 (제목·좋아요 상태 포함) |
| `service/SearchService.buildTitle()` | `"'키워드' 검색 결과"` | 콘텐츠 평균 벡터 → 최근접 query |
| `service/UserVectorService.java` | 벡터 없으면 예외 | `PRE_MADE` 폴백 |
| `entity/board/BoardItem.java` | `slot_no` 없음 | 필드 추가 + 제약 (2장) |
| `repository/BoardItemRepository.java` | `findContentIdsByBoardId(s)` — **`ORDER BY`가 없어 순서 미보장** | `ORDER BY bi.slotNo` 추가 |
| `service/ContentService.getContents()` | `findContentsByIds`가 DB 순서로 돌려줘 **순서가 다시 뭉개짐** | `slot_no` 순으로 재정렬 (`RecommendationService.recommendByVector`와 같은 방식) |
| `service/BoardService.getLikedBoards()` | 같은 문제 — 보드별 콘텐츠 순서가 뒤섞임 | 동일하게 재정렬 |
| 신규 | — | 평균 벡터 계산 + `query_vector` 최근접 조회 |
| 신규 | — | Flyway 마이그레이션 (컬럼 추가 → 백필 → 중복 정리 → 제약/인덱스) |

### 프론트 (`/Users/kyj/dev/web/crates-frontend`)

| 위치 | 변경 |
|---|---|
| `features/board/api/boardApi.ts` | `getRecommendedContents`가 `ContentSummary[]` → `BoardWithContents` |
| `features/board/pages/HomePage.tsx` | `recommendedBoardId` 로컬 state 제거 (서버가 `boardId`를 내려줌) |
| `shared/types/board.ts` | 이미 `boardId: number \| null` 반영 완료 |
| `features/board/components/BoardCollage.tsx` | `SLOTS_8`이 이미 2행 4열이고 `contents[index] → slots[index]` 매핑이라, **서버가 `slot_no` 순으로 내려주면 수정 없이 동작**. `SLOTS_12`는 8건 고정이 확정됐으므로 제거 대상 |
| 신규 | 순서 편집 UI (드래그앤드롭 등) + `PUT /api/boards/{id}/contents` 호출 |

### 개발 단계 적용 방법

현재 `ddl-auto: create`로 매 실행마다 스키마를 새로 만들고, Flyway는 비활성이다.
(둘을 같이 켜면 마이그레이션이 최초 1회만 실행된 것으로 기록되고 이후 ddl-auto가 데이터를
지워버린다 — `application.yaml` 주석 참고.)

따라서 **기존 데이터 백필이나 중복 정리는 필요 없다.** 재실행하면 초기화된다.

1. 엔티티에 필드 추가 (`Board.visibility`, `Board.contentSignature`, `BoardItem.slotNo`)
   → Hibernate가 스키마를 만든다
2. **JPA로 표현할 수 없는 제약은 `src/main/resources/import.sql`에 넣는다.**
   이 파일은 Hibernate가 스키마 생성 직후 실행하며, 이미 같은 용도로 쓰이고 있다
   (`board_feedback`의 `ON DELETE SET NULL` FK).
   - 부분 유니크 인덱스: `uk_board_ai_signature`, `uk_board_user_signature`,
     `uk_board_item_slot`, `uk_board_item_content`
   - CHECK 제약: `ck_board_owner`, `ck_board_visibility`, `ck_board_item_slot`
3. 애플리케이션 코드 교체

> ⚠️ **`import.sql`의 실패는 조용하다.** Hibernate는 `hbm2ddl.halt_on_error`가 기본 false라
> 구문 오류가 나도 로그만 남기고 기동을 계속한다. 제약이 안 걸린 채로 돌아가면 중복이 다시
> 쌓이므로, 추가 후 실제로 적용됐는지 한 번은 확인해야 한다:
> ```sql
> SELECT indexname FROM pg_indexes WHERE tablename IN ('board','board_item');
> ```

> CHECK 제약만은 `org.hibernate.annotations.@Check`로 엔티티에 붙일 수도 있다. 다만 부분 유니크
> 인덱스는 어차피 `import.sql`로 가야 하므로, 규칙이 두 곳에 흩어지지 않도록 한 파일에 모은다.

### 운영 전환 시

`ddl-auto: validate`로 바꾸고 위 DDL을 Flyway 마이그레이션으로 옮긴다. 그 시점에는 실데이터가
있으므로 아래 순서가 필요해진다.

1. 컬럼 추가 — nullable로
2. 백필 — `content_signature`(정렬 계산), `visibility`(전부 PUBLIC),
   `slot_no`(`row_number() over (partition by board_id order by id)`)
3. 중복 정리 — 아래 쿼리로 확인 후, 좋아요를 대표 보드로 이관하고 나머지는 soft delete
4. `NOT NULL` + CHECK 제약 + 인덱스 추가
5. 애플리케이션 코드 교체

3번을 건너뛰면 4번의 unique 인덱스 생성이 실패한다.

```sql
-- 중복 AI_RECOMMEND 보드
SELECT content_signature, count(*) FROM board
 WHERE board_type = 'AI_RECOMMEND' AND deleted_at IS NULL
 GROUP BY content_signature HAVING count(*) > 1;

-- 한 보드에 같은 콘텐츠가 두 번 들어간 경우
SELECT board_id, content_id, count(*) FROM board_item
 GROUP BY board_id, content_id HAVING count(*) > 1;
```

---

## 11. 검색 추천 보드와 보드 삭제 (2026-10-27 결정)

검색 보드의 제목을 사용자가 입력한 검색어로 저장하자는 AI 개발자 요구에서 출발해, 보드 종류 하나를
추가하고 삭제 규칙을 함께 정했다. 이 장이 앞 장들과 다르면 이 장이 우선한다.
구현은 기능별로 모아 한 번에 배포한다.

### 11-1. 검토했다가 버린 안

| 안 | 버린 이유 |
|---|---|
| 화면 제목만 검색어, 저장은 지금처럼 `AI_RECOMMEND` + query 제목 | 사용자가 본 제목과 보관함·인기 목록의 제목이 달라진다. 검색어를 남기려는 의도를 살리지 못한다 |
| 좋아요하면 그 사용자의 `USER_CUSTOM`으로 저장 | 같은 검색어라도 사람마다 AI를 다시 부르고 보드가 따로 쌓인다. 사용자가 만든 보드가 아니라 `USER_CUSTOM`이라는 이름과 맞지 않는다 |
| 검색어(제목) 하나로 전역 unique, 저장된 보드가 있으면 AI를 부르지 않음 | 처음 좋아요한 사람의 결과로 그 검색어가 고정된다. 요청을 조작하면 모든 사람의 검색 결과를 바꿀 수 있다 |
| Redis에 검색어별 결과를 두고 좋아요 요청을 대조 | 캐시가 만료되고 다른 사람이 같은 검색어로 다시 채우면, 정상 사용자의 좋아요가 불일치로 거절된다 |

### 11-2. `SEARCH_RECOMMEND`

| 항목 | 규칙 |
|---|---|
| `board_type` | `SEARCH_RECOMMEND` (신규) |
| `user_id` | NULL — 소유자 없는 전역 공용 보드 |
| `visibility` | 항상 `PUBLIC` |
| `title` | 검색어 (11-3) |
| 저장 시점 | 좋아요를 눌렀을 때만. 좋아요가 없으면 보여 주고 끝난다 |
| 중복 판별 | **제목 + 콘텐츠 구성**. 콘텐츠가 추가되면 같은 검색어라도 결과가 바뀌므로 같은 제목의 보드가 여럿일 수 있다 |
| 좋아요 0개 | 완전 삭제 (11-6) |
| 수정 | 다른 공용 보드처럼 원본은 두고 내 `USER_CUSTOM`으로 새로 저장 (9장) |

```sql
CREATE UNIQUE INDEX uk_board_search_title_signature
    ON board (title, content_signature)
 WHERE board_type = 'SEARCH_RECOMMEND';
```

이 타입으로 조회하는 쿼리는 네이티브 쿼리에 `board_type = 'SEARCH_RECOMMEND'`를 직접 적는다.
JPQL은 enum을 파라미터로 바꿔 부분 인덱스를 쓰지 못한다(V3 주석).

`USER_CUSTOM`과 제목·구성이 같아도 상관없다. 검색 보드는 `USER_CUSTOM`에서 꺼내 온 것이 아니라
새로 추천된 보드이고, unique 인덱스도 타입별로 따로라 충돌하지 않는다. 검색 보드를 수정해 같은 제목·구성의
내 `USER_CUSTOM`으로 새로 저장되는 것도 허용된다. 인기 목록에서는 구성이 같으면 `USER_CUSTOM`이 남는다(11-7).

이미 `AI_RECOMMEND`로 저장된 예전 검색 보드는 홈 추천 보드와 구분할 출처 정보가 없어 그대로 둔다.

### 11-3. 제목 비교 규칙

제목은 사용자의 표현이라 **NFC 변환 → 앞뒤 공백 제거(`strip()`)** 만 한다.
문장 안의 공백과 대소문자는 그대로 둔다. `"재즈  명반"`과 `"재즈 명반"`, `"Jazz"`와 `"jazz"`는 다른 제목이다.

- NFC는 화면에 똑같이 보이는 한글이 기기(macOS·iOS의 NFD)에 따라 다른 바이트로 들어와 보드가 갈라지는 것만 막는다.
- 검색어를 받자마자 한 번 적용하고, 그 값으로 AI 요청·토큰·저장을 모두 한다. 단계마다 다른 값을 쓰면 토큰은 통과했는데 다른 제목으로 저장된다.
- 벡터 비교는 쓰지 않는다. 768 × float32 = 3,072바이트로 제목(검색어 최대 100자 ≈ 300바이트)보다 10배 이상 크고,
  B-tree 항목 한도(약 2.7KB)를 넘어 unique 인덱스를 걸 수 없다. 표기 차이로 벡터가 달라져 결과가 바뀌면 구성이 달라져 어차피 다른 보드가 된다.
- `USER_CUSTOM` 제목에도 같은 규칙을 쓴다 (11-5).

### 11-4. 검색과 좋아요 흐름

**검색 (`GET /api/search/board`)**

1. 검색어에 11-3 규칙 적용
2. **검색할 때마다** AI 서버로 검색어 벡터를 받아 Qdrant에서 콘텐츠 8건 조회. 저장된 보드가 있어도 AI를 부른다.
   AI 호출을 줄이려면 검색어별 결과를 Redis에 n시간 둘 수 있다(선택, 검증 용도는 아님)
3. `(제목, 구성)`으로 저장된 `SEARCH_RECOMMEND` 보드를 찾아 `boardId`·`likeCount`·`liked`를 채운다.
   다시 검색했을 때 하트가 채워져 보이는 것은 이 조회 덕분이다
4. 응답에 **서명 토큰**을 담는다

**서명 토큰**

```
payload = { purpose: "search-like", keyword: "<11-3 적용한 검색어>", contentIds: [slot 순서 8건], iat }
token   = base64url(payload) + "." + base64url(HMAC-SHA256(서버 비밀 키, payload))
```

- 서버가 이 검색어로 이 8건을 실제로 보여 줬다는 증명이다. 비밀 키는 서버에만 있고(Secrets Manager) 밖으로 나가지 않는다.
- 서버가 아무것도 저장하지 않는다. 캐시 만료나 다른 사람의 검색과 무관하게 **그 사용자가 본 결과**가 검증된다.
- **만료 시각을 검사하지 않는다.** 서명이 맞으면 오래돼도 내용은 진짜이고, 좋아요는 멱등이라 재사용해도 해가 없다.
  서버가 몰래 다시 검색하면 사용자가 본 적 없는 8건에 좋아요가 붙을 수 있어서, 만료 후 재검색 안은 버렸다.
- 서명 비교는 상수 시간(`MessageDigest.isEqual`)으로 한다.
- `purpose`를 넣어 다른 기능의 토큰과 섞이지 않게 한다. 홈 추천 보드도 같은 키로 `"recommend-like"` 토큰을 쓴다(11-11).
- 키를 교체할 때는 하루 정도 이전 키도 함께 받아 준다. 그 뒤 이전 키 토큰은 실패하고, 프론트가 다시 검색한다.

**좋아요 (검색 보드 전용 경로, 토큰을 보낸다)**

1. 토큰 검증. 서명 불일치·형식 오류·`purpose` 불일치는 **400으로 거절**한다. 정상 사용자는 겪지 않고 요청을 조작했을 때만 생긴다
2. **토큰 안의 검색어와 콘텐츠로** 처리한다. 요청 본문의 제목·콘텐츠는 쓰지 않는다
3. `(제목, 구성)` 보드가 있으면 `like()` — 좋아요 기록(`board_feedback`) 저장 + `like_count + 1`, 이미 눌렀으면 그대로
4. 없으면 `INSERT ... ON CONFLICT DO NOTHING RETURNING id`로 저장(좋아요 1로 시작). 비어 돌아오면 다른 사람이 먼저 만든 것이니 3으로

`ON CONFLICT`를 쓰는 이유: 두 사람이 동시에 처음 좋아요하면 둘 다 "없음"을 보고 저장한다. 예외를 잡아 다시 조회하는
방식은 PostgreSQL이 오류 난 트랜잭션을 이어 가지 못해 같은 트랜잭션에서 동작하지 않는다.
홈 추천 `AI_RECOMMEND`의 `likeNewBoard`에도 같은 경합이 있어 함께 바꾼다.

AI 호출과 Qdrant 조회는 트랜잭션 밖에서 한다(2026-10-04 부하 측정에서 커넥션 풀이 막혔던 원인).

### 11-5. `USER_CUSTOM` 중복 판별 변경

같은 사용자 안에서 **제목 + 구성**이 같을 때만 막는다. 구성만 같고 제목이 다른 보드는 허용한다.
제목은 11-3 규칙(NFC + 앞뒤 공백 제거, 대소문자 구분)으로 저장·비교한다.

```sql
-- uk_board_user_signature (user_id, content_signature)를 교체
CREATE UNIQUE INDEX uk_board_user_title_signature
    ON board (user_id, content_signature, title)
 WHERE board_type = 'USER_CUSTOM' AND deleted_at IS NULL;
```

- `findActiveUserBoardBySignature`는 결과가 1건이라는 전제(`Optional`)라, 조건에 제목을 넣지 않으면 오류가 난다
- 제목만 바꾸는 수정도 같은 구성의 내 다른 보드와 제목이 겹치면 거절한다(자기 자신은 제외)
- 거절 문구는 "이미 같은 제목과 구성의 보드를 갖고 계세요"로 바꾼다
- 같은 구성의 보드 두 개를 모두 좋아요하면 그 콘텐츠가 취향 벡터에 두 번 들어간다. **의도된 동작이다** —
  다른 사람이 올린 같은 영상에 각각 좋아요를 누를 수 있는 것과 같다
- 같은 사람의 쌍둥이 보드끼리 좋아요가 나뉘는 것은 신경 쓰지 않는다. 인기 목록에는 5-1로 하나만 보인다

### 11-6. 삭제 규칙

| 종류 | 누가 | 방식 |
|---|---|---|
| `AI_RECOMMEND` | 좋아요가 0이 되는 순간 자동 | 완전 삭제 |
| `SEARCH_RECOMMEND` | 좋아요가 0이 되는 순간 자동 | 완전 삭제 |
| `USER_CUSTOM` | 만든 사용자가 보관함에서 **삭제를 눌렀을 때만** | ① 본인 좋아요 제거 → ② 남은 좋아요가 있으면 소프트 삭제(`user_id = NULL`, `deleted_at` 기록), 없으면 바로 완전 삭제 → ③ 이후 마지막 좋아요가 취소되면 완전 삭제 |
| 사용자 계정 | — | 소프트 삭제 (기존대로) |

`USER_CUSTOM`은 좋아요가 0이 돼도 자동으로 지우지 않는다. 주인이 자기 보드의 좋아요를 취소해도 보드는 그대로다.
완전 삭제는 주인이 삭제해 소프트 삭제된 보드에서 마지막 좋아요가 취소될 때만 일어난다.

완전 삭제는 좋아요가 0일 때만 일어나므로 **다른 사람이 자기 의사와 무관하게 좋아요를 잃는 일은 없다.**
그래서 좋아요를 잃은 사용자의 취향 벡터를 다시 계산할 필요도 없다.

①이 필요한 이유: 자기 보드에 좋아요를 눌러 뒀다면 그 기록 때문에 지운 보드가 자기 보관함 좋아요 탭에 다시 나타난다.

**소프트 삭제된 `USER_CUSTOM`이 보이는 곳**

| 화면 / 계산 | 보임 여부 |
|---|---|
| 인기 목록, 검색 결과 | 숨김 |
| 만든 사람의 보관함 | 숨김 (`user_id`가 NULL이라 자연히 빠짐) |
| 좋아요한 사람의 보관함 좋아요 탭, 보드 상세 | **보임** |
| 좋아요한 사람의 취향 벡터 | **포함** |
| 새 좋아요 | **허용하지 않는다** — "삭제된 보드입니다" |
| 좋아요 취소 | 가능. 마지막 취소면 완전 삭제 |

소프트 삭제된 보드는 인기 목록과 검색에서 빠지므로 새 사람이 닿을 길은 거의 없다.
남는 경로는 둘이고, 둘 다 실패로 처리한다.

- **좋아요한 사람이 취소했다가 다시 누르는 경우** — 취소하는 순간 그 보드를 다시 가질 수 없다. 알고 받아들인 동작이다.
  프론트는 소프트 삭제된 보드의 좋아요를 취소하기 전에 "삭제된 보드라 다시 좋아요할 수 없어요"를 알려 주는 것이 좋다
  (응답에 삭제 여부를 내려줘야 한다).
- **삭제 전에 띄워 둔 인기 목록에서 누르는 경우** — 11-8의 경고와 목록 새로 받기로 처리한다.

지금 쿼리는 `deleted_at`이 있는 보드를 좋아요 탭과 취향 벡터(`findLikedBoardsWithTime`)에서도 숨긴다.
그대로 두면 좋아요 기록은 남아도 사실상 좋아요를 잃는 것과 같으니 이 두 곳의 조건을 바꿔야 한다.

**완전 삭제 절차** — 외래 키에 `ON DELETE CASCADE`를 걸지 않는다.

1. 보드 행을 잠근다 (`SELECT ... FOR UPDATE`)
2. `like_count = 0`이고 `board_type = 'AI_RECOMMEND' OR 'SEARCH_RECOMMEND' OR deleted_at IS NOT NULL`인지 확인
3. `board_item` 삭제
4. `board` 삭제

- 잠그지 않고 `board_item`부터 지우면, 그 사이 좋아요가 들어왔을 때 콘텐츠 없는 보드가 남는다.
- `board_feedback`에 CASCADE가 없어 외래 키가 **안전장치**가 된다. 좋아요 기록이 남은 채 삭제가 실행되면 좋아요를 함께 지우지 않고 삭제가 실패한다.
- 싫어요(`Rating.DISLIKE`)는 도입하지 않는다. 도입하면 좋아요 0개·싫어요 기록이 남은 보드가 외래 키에 막혀 지워지지 않는다.

**동시 요청**

- 삭제와 좋아요가 겹쳐 좋아요 저장이 외래 키 위반으로 실패하면 "삭제된 보드입니다"로 응답한다.
- 이미 없는 보드의 좋아요 취소는 **성공**(`liked: false`)으로 응답한다. 사용자가 원하던 상태가 이미 됐다.

### 11-7. 인기 보드 우선순위

| 경우 | 순서 |
|---|---|
| 구성이 다른 보드끼리 | 좋아요 수 → 타입(`USER_CUSTOM` > `SEARCH_RECOMMEND` > `AI_RECOMMEND`) → `id` (생성 순서) |
| 구성이 같은 보드끼리 (하나만 남김) | 타입(같은 순서, 좋아요 수와 무관) → 좋아요 수 → `id` |

- 구성이 다를 때 타입이 좋아요 다음 기준으로 끼어들므로 `ORDER BY like_count DESC, <타입 순위>, id`가 되고,
  `idx_board_popular`를 같은 순서의 표현식 인덱스로 바꿔야 정렬 연산이 생기지 않는다.
- 구성이 같을 때 남는 보드는 자기 좋아요 수 자리에 놓인다. 좋아요가 적은 상위 타입이 남으면 그 구성이 요청 개수 밖으로 밀려
  목록에서 빠질 수 있고, 상위 타입 보드가 후보(`n × 2`) 밖이면 하위 타입 보드가 그대로 보인다. 알고 받아들인 동작이다.
- `SEARCH_RECOMMEND`는 같은 제목에 구성이 다른 보드가 여럿 오를 수 있다. 구성이 다르니 정책상 맞다.

### 11-8. 프론트 좋아요 경로

| 화면 | 좋아요 | 취소 |
|---|---|---|
| 홈 추천 | **boardId가 있어도 항상 구성으로** `POST /api/boards/likes` | boardId로 `DELETE` |
| 검색 | **항상 토큰으로** 검색 좋아요 경로 | boardId로 `DELETE` |
| 인기 목록, 보관함 | boardId로 `POST` | boardId로 `DELETE` |

- 추천·검색 보드를 내용으로 좋아요하면, 그 사이 보드가 지워졌어도 서버가 "있으면 좋아요, 없으면 저장 후 좋아요"로 처리한다.
  **삭제된 보드라는 상황이 생기지 않아** 프론트의 실패 후 재시도가 필요 없다. 지워진 보드는 좋아요 0개였으니 다시 저장해도 잃는 것이 없다.
- 인기 목록·보관함의 보드는 다시 만들 재료가 없다(남의 `USER_CUSTOM`은 다시 만들 수도 없다).
  "삭제된 보드입니다"를 띄우고 목록을 새로 받는다.

### 11-9. 마이그레이션 (V3에 합침)

처음에는 운영 DB(V1~V9 적용)를 살리려고 새 버전(V10)으로 덧붙였다가, 운영 DB를 초기화하고 다시 적재하기로 해
(2026-10-27) 보드 스키마 파일 `V3__BoardSchema.sql`에 최종 모양으로 합쳤다. 바뀐 것:

- `board_type` CHECK에 `SEARCH_RECOMMEND`
- `ck_board_owner` → "살아 있는 USER_CUSTOM은 소유자 필수, 지운 USER_CUSTOM은 소유자 없음, 나머지는 소유자 없음"
- `uk_board_user_signature` → `uk_board_user_title_signature` (11-5)
- `uk_board_search_title_signature` 신규 (11-2)
- 인기 정렬 인덱스에 타입 순위 CASE 식 (11-7)
- `idx_board_deleted` 없음 — 좋아요 쪽 조회가 삭제된 보드를 배제하지 않아 쓰는 쿼리가 없다

운영 DB를 초기화할 때 이미지 파일(S3)은 그대로 남는다. 사라지는 것은 콘텐츠와 이미지를 잇는
`content.s3object_key`·`image_extension`뿐이라, 초기화 전에 `(dtype, source_key, s3object_key, image_extension)`을
내보내 두었다가 다시 적재한 뒤 `(dtype, source_key)`로 되돌린다.

### 11-10. API (구현)

| 요청 | 동작 |
|---|---|
| `GET /api/search/board?keyword=` | `SearchBoardDto` — 보드 필드 + `likeToken` |
| `POST /api/search/board/likes` `{ likeToken }` | 검색 보드 좋아요. "있으면 좋아요, 없으면 저장 후 좋아요" |
| `GET /api/boards/recommendation/user` | `RecommendedBoardDto` 4개 — 보드 필드 + `likeToken` (11-11) |
| `POST /api/boards/likes` `{ likeToken }` | 홈 추천 보드 좋아요. boardId가 있어도 이 경로 (11-11) |
| `POST /api/boards/{id}/likes` | 저장된 보드 좋아요(인기 목록·보관함). 지워진 보드면 400 "삭제된 보드입니다." |
| `DELETE /api/boards/{id}/likes` | 좋아요 취소. 보드가 지워졌으면(또는 이미 없으면) 응답 `boardId`가 `null` |
| `DELETE /api/boards/{id}` | 내 보드 삭제(주인만) |

- 보드 응답(`BoardWithContentsDto`)에 `deleted`가 추가됐다. 주인이 지운 보드면 `true`이고, 좋아요한 사람의 보관함에서만 보인다.
- 운영 비밀값 `SEARCH_LIKE_TOKEN_SECRET`(SSM `/crates/prod/search-like-token-secret`)이 없으면 앱이 뜨지 않는다.
  로컬은 `crates_server.env`에 같은 이름으로 둔다. 홈 추천 토큰도 같은 키로 서명한다(11-11).

### 11-11. 홈 추천 보드에도 토큰 (2026-10-28)

홈 추천 보드 좋아요는 프론트가 보낸 `{ title, contentIds }`를 그대로 믿었다. 서버는 콘텐츠가 있는지만 확인해서,
요청을 직접 만들면 아무 제목과 구성으로 공개 AI 추천 보드를 만들 수 있었다. 검색 보드(11-4)와 같은 문제라 같은 방식으로 막는다.

- 추천 응답의 보드마다 `likeToken`을 붙인다. payload의 용도는 `"recommend-like"`, 제목은 **화면에 보이는 제목**이다
  (저장된 보드면 저장 시점의 제목, 아니면 이번에 고른 query).
- 좋아요(`POST /api/boards/likes`)는 `{ likeToken }`만 받고 토큰 안의 제목·콘텐츠로 처리한다. 나머지는 11-4와 같다.
- 서명 키는 검색 보드와 같다. 용도(`p`)가 달라 검색 토큰으로 AI 보드를, 추천 토큰으로 검색 보드를 만들 수 없다.
  서비스 이름은 `SearchLikeTokenService` → `BoardLikeTokenService`, 설정은 `search.like-token.*` → `board.like-token.*`로 바꿨고
  환경 변수·SSM 이름은 운영과 맞추려고 그대로 뒀다.
- **Redis 추천 캐시로 검증하지 않은 이유.** 캐시는 취향 벡터 버전으로 가르는데, 좋아요를 누르면 벡터가 다시 계산돼 버전이 바뀐다.
  같은 화면의 두 번째 보드에 좋아요할 때 캐시와 맞지 않을 수 있다. 토큰은 서버에 아무것도 남기지 않아 이런 어긋남이 없다.
- 토큰은 캐시에 담지 않고 응답마다 새로 서명한다. HMAC 한 번은 수 마이크로초라 추천 응답 시간(수십~수백 ms)에 비해 무시할 만하다.

---

## 12. 미결정 / 후속 검토

- **회원 탈퇴 시 그 사람의 `USER_CUSTOM` 보드.** 11-6과 같은 절차(본인 좋아요 제거 → 소프트 삭제 → 0이면 완전 삭제)를
  적용하면 일관된다. 탈퇴자가 다른 보드에 남긴 좋아요를 지울지도 함께 정한다.
- **검색어 욕설·개인정보 필터.** 지금은 길이 제한(100자)뿐이다. AI 개발자는 나중에 도입해도 된다는 입장.

- **PRIVATE 전환 시 남의 좋아요 처리.** `board_feedback` 레코드와 `like_count`는 보존하고
  조회 시 `visibility` 필터만 거는 안을 제안한 상태.
- **AI 서버 임베딩이 아직 스텁이다.** 실제 통신이 열리면 걷어낼 코드는
  `memory/project_ai_stub_cleanup.md` 참고. 특히 Qdrant `content_vector` 컬렉션을
  **삭제 후 재적재**해야 더미 벡터가 남지 않는다.
- **HNSW 근사성으로 top-8이 흔들릴 수 있다.** 정렬로 순서 흔들림은 흡수하지만 8건 중
  1건이 교체되면 새 보드가 생긴다. 빈도가 낮아 감수하되, 관측되면 대응을 검토한다.
