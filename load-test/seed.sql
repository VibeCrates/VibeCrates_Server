-- 부하테스트용 데이터를 SQL로 직접 꽂는다 — 6개월 누적 시나리오.
--
-- 왜 API가 아니라 SQL인가
--   "좋아요 200건짜리 계정"을 API로 만들면 요청 200번이 나가고 그때마다 취향 벡터 재계산이
--   비동기로 돌아 수십 초가 걸린다. 준비 자체가 부하가 되어, 측정하려는 상태에 도달하기 전에
--   시스템이 데워지거나 지쳐버린다.
--
--   더 중요한 이유는 역할 분리다. 그동안 k6가 데이터 생성과 부하 생성을 겸하면서 실행마다
--   상태가 누적됐고, 그것이 "개선을 퇴행으로 잘못 읽은" 원인이었다(docs 4-9).
--
-- ── 가정 ────────────────────────────────────────────────────────────────
-- 서비스가 6개월 운영됐을 때 쌓일 만한 양이다. 근거는 Notion "레포지토리 메서드 성능 테스트".
--   가입    아래 cohort 표. 매달 1일에 가입하고 이후 매일 활동한다(가장 무거운 쪽으로 잡은 상한)
--   좋아요  하루 :likes_per_day 번. 그중 :popular_ratio 는 이미 있는 보드(인기 보드)에,
--           나머지는 새 AI 추천 보드에 누른다. 추천 보드는 좋아요를 눌러야 DB에 저장되므로
--           "새 AI 보드에 누른 좋아요 1번 = AI 보드 1개"다.
--   수정    하루 :edits_per_day 번. 보드의 콘텐츠를 하나만 바꿔도 "내가 만든 보드"가 된다.
--   검색    하루 :search_likes_per_day 번 검색 결과에 좋아요를 누른다(검색 2번 중 1번). 검색 보드도 좋아요를
--           눌러야 저장되므로 "검색 좋아요 1번 = 검색 보드(SEARCH_RECOMMEND) 1개"다. 검색 자체는 DB에 남지 않는다.
--           검색 보드는 모두 서로 다른 (검색어, 구성)이라고 본다 — 인기 검색어에 좋아요가 모이는 실제보다 많은 상한이다.
--   인기    인기 보드 좋아요는 지프 분포(:zipf_s). 순위 r인 보드가 1/r^s 에 비례해 받는다.
--
-- 기본값(3 / 1 / 0.6 / 1.0 / 1)이면
--   계정 1,200 / AI 보드 136,800 / 검색 보드 114,000 / 사용자 보드 114,000 / board_item 2,918,400 / 좋아요 456,000
--
-- psql 변수
--   :likes_per_day   하루 좋아요 수
--   :edits_per_day   하루 보드 수정 수
--   :popular_ratio   좋아요 중 인기 보드로 가는 비율 (0~1)
--   :zipf_s          인기 보드 지프 분포의 s (클수록 위쪽에 몰린다)
--   :search_likes_per_day  하루 검색 좋아요 수 (= 하루 새 검색 보드 수)
--
-- 전제: loadtest_seed 계정과 그 취향 벡터가 미리 있어야 한다(비밀번호 해시와 취향 벡터를 복사한다).
--       seed.sh가 계정은 SQL(pgcrypto)로, 취향 벡터는 가입 직후 콘텐츠 선택 API로 만든다.
--       loadtest_u* 계정이 남아 있으면 안 된다 — seed.sh가 확인한다(cleanup.sql 먼저).

\set ON_ERROR_STOP on
BEGIN;

-- 같은 인자면 같은 데이터가 나오도록 난수 씨앗을 고정한다. 측정을 되풀이할 때 데이터가 달라지면
-- 결과 차이가 코드 때문인지 데이터 때문인지 가를 수 없다.
SELECT setseed(0.2026) \g /dev/null

-- ── 가입 시기 ───────────────────────────────────────────────────────────
-- 누적 100 → 200 → 500 → 800 → 1,000 → 1,200명. 각 달에 새로 가입한 인원이다.
-- 가정을 바꾸려면 이 표만 고친다. 마지막 달이 "지금"이고, n개월차 가입자는 (마지막 달 - n + 1) × 30일을 쓴 것이다.
CREATE TEMP TABLE cohort (month int PRIMARY KEY, new_users int NOT NULL) ON COMMIT DROP;
INSERT INTO cohort VALUES (1, 100), (2, 100), (3, 300), (4, 300), (5, 200), (6, 200);

-- 사용자마다 만들 양을 정한다. 좋아요 = 새 AI 보드(ai_n) + 새 검색 보드(search_n) + 인기 보드(pop_n).
CREATE TEMP TABLE plan_user ON COMMIT DROP AS
SELECT (row_number() OVER (ORDER BY c.month, g) - 1)::int AS ui,
       c.month,
       ((SELECT max(month) FROM cohort) - c.month + 1) * 30 AS days
FROM cohort c
CROSS JOIN LATERAL generate_series(1, c.new_users) g;

ALTER TABLE plan_user
    ADD COLUMN ai_n int, ADD COLUMN pop_n int, ADD COLUMN search_n int, ADD COLUMN custom_n int, ADD COLUMN user_id bigint;
UPDATE plan_user
   SET ai_n     = round(days * :likes_per_day * (1 - :popular_ratio))::int,
       search_n = round(days * :search_likes_per_day)::int,
       custom_n = round(days * :edits_per_day)::int;
UPDATE plan_user SET pop_n = days * :likes_per_day - ai_n;

-- 셸이 결과와 비교할 기대값. 세션 임시 테이블이라 COMMIT 뒤에도 남는다(psql이 끝날 때 사라진다).
CREATE TEMP TABLE seed_expect AS
SELECT count(*)                              AS users,
       sum(ai_n)                             AS ai_boards,
       sum(search_n)                         AS search_boards,
       sum(custom_n)                         AS custom_boards,
       (sum(ai_n) + sum(search_n) + sum(custom_n)) * 8 AS items,
       sum(ai_n + search_n + pop_n)          AS likes,
       max(ai_n + search_n + pop_n)          AS max_user_likes,
       max(custom_n)                         AS max_user_boards
FROM plan_user;

-- ── 계정 ────────────────────────────────────────────────────────────────
-- 비밀번호 해시는 기준 계정에서 복사한다. BCrypt는 솔트가 달라도 같은 평문을 검증하므로
-- 모든 테스트 계정이 같은 비밀번호로 로그인된다. 가입 시각은 가입 시기에 맞춘다.
INSERT INTO users (login_id, pwd, email, nickname, birth_date, role, gender, login_type, created_at, updated_at)
SELECT 'loadtest_u' || p.ui,
       (SELECT pwd FROM users WHERE login_id = 'loadtest_seed'),
       'loadtest_u' || p.ui || '@example.com', 'loadtest_u' || p.ui,
       DATE '1995-01-01', 'USER', 'OTHER', 'LOCAL',
       now() - (p.days || ' days')::interval, now() - (p.days || ' days')::interval
FROM plan_user p
ORDER BY p.ui;

UPDATE plan_user p SET user_id = u.id FROM users u WHERE u.login_id = 'loadtest_u' || p.ui;

-- 취향 벡터가 없으면 추천 조회가 BusinessException으로 죽는다. 가입 직후 콘텐츠를 고를 때
-- 만들어지는 행을 여기서 대신 만든다. 벡터와 고른 콘텐츠 id를 기준 계정 것으로 채운다 —
-- 좋아요 재계산이 이 id를 다시 읽으므로 벡터만 복사하면 첫 좋아요부터 계산이 어긋난다.
-- 벡터 값은 좋아요와 맞지 않으므로 API 측정 전에는 prepare.sh가 전원을 다시 계산한다.
--
-- updated_at은 "지금"이다. 마지막 좋아요보다 이르면 백필 배치가 전원을 밀린 사용자로 보고
-- 측정 중에 재계산을 돌린다.
INSERT INTO user_vector (user_id, user_vector, initial_content_ids, updated_at)
SELECT p.user_id, s.user_vector, s.initial_content_ids, now()
FROM plan_user p
CROSS JOIN (SELECT uv.user_vector, uv.initial_content_ids FROM user_vector uv
            JOIN users b ON b.id = uv.user_id WHERE b.login_id = 'loadtest_seed') s;

-- ── 보드 계획 ───────────────────────────────────────────────────────────
-- 보드마다 만든 시각을 그 사용자의 이용 기간 안에서 무작위로 정한다. AI·검색 보드는 그 시각에
-- 사용자가 좋아요를 눌러 저장된 것이고, 사용자 보드는 그 시각에 수정해 만든 것이다.
-- k는 만든 시각 순서다. 보드를 이 순서로 넣어 id와 힙 위치가 시간 순서를 따르게 한다(실제 서비스와 같다).
CREATE TEMP TABLE plan_board ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY created_at, actor, kind) AS k, kind, actor, created_at
FROM (
    SELECT 'AI' AS kind, p.user_id AS actor, now() - random() * (p.days || ' days')::interval AS created_at
    FROM plan_user p CROSS JOIN LATERAL generate_series(1, p.ai_n)
    UNION ALL
    SELECT 'SEARCH', p.user_id, now() - random() * (p.days || ' days')::interval
    FROM plan_user p CROSS JOIN LATERAL generate_series(1, p.search_n)
    UNION ALL
    SELECT 'CUSTOM', p.user_id, now() - random() * (p.days || ' days')::interval
    FROM plan_user p CROSS JOIN LATERAL generate_series(1, p.custom_n)
) t;

ALTER TABLE plan_board ADD COLUMN title text;
UPDATE plan_board
   SET title = CASE kind WHEN 'AI' THEN 'loadtest-seeded-' WHEN 'SEARCH' THEN 'loadtest-search-' ELSE 'loadtest-custom-' END || k;
CREATE UNIQUE INDEX ON plan_board (k);

-- ── 보드 콘텐츠 ─────────────────────────────────────────────────────────
-- 보드마다 콘텐츠 8개를 무작위로 고른다.
--
-- 예전에는 콘텐츠 위치를 일정 간격으로 밀어가며(슬라이딩 윈도우) 골랐는데, 그 방식으로는 서로 다른
-- 보드를 콘텐츠 수(약 18만)만큼만 만들 수 있었다. 무작위 8개 조합은 경우의 수가 사실상 무한해
-- 25만 개를 만들어도 겹치지 않는다(겹치면 아래 ON CONFLICT가 버리고, seed.sh가 개수로 잡아낸다).
--
-- 후보를 12개 뽑아 겹치는 것을 빼고 앞의 8개를 쓴다. 18만 개 중 12개가 8개 미만으로 줄 확률은 0에 가깝다.
-- id를 직접 계산하지 않고 위치(pos)로 다루는 이유: 콘텐츠 id에 빈틈이 있으면 없는 id를 참조해 FK에서 깨진다.
CREATE TEMP TABLE content_pos ON COMMIT DROP AS
SELECT id, (row_number() OVER (ORDER BY id) - 1)::int AS pos FROM content;
CREATE UNIQUE INDEX ON content_pos (pos);

CREATE TEMP TABLE plan_item ON COMMIT DROP AS
WITH cand AS (
    SELECT b.k, g, floor(random() * (SELECT count(*) FROM content_pos))::int AS pos
    FROM plan_board b CROSS JOIN generate_series(1, 12) g
), dedup AS (
    SELECT DISTINCT ON (k, pos) k, g, pos FROM cand ORDER BY k, pos, g
), ranked AS (
    SELECT k, pos, row_number() OVER (PARTITION BY k ORDER BY g) AS slot FROM dedup
)
SELECT r.k, r.slot::int AS slot, c.id AS content_id
FROM ranked r JOIN content_pos c USING (pos)
WHERE r.slot <= 8;

-- content_signature는 Board.signatureOf와 같은 규칙이다 — id를 오름차순 정렬해 콤마로 잇는다.
-- 앱이 계산한 값과 글자 하나라도 다르면 중복 검사가 어긋나 보드가 두 벌로 쌓인다.
CREATE TEMP TABLE plan_sig ON COMMIT DROP AS
SELECT k, string_agg(content_id::text, ',' ORDER BY content_id) AS signature, count(*) AS n
FROM plan_item GROUP BY k;

-- ── 보드 ────────────────────────────────────────────────────────────────
-- AI·검색 보드는 소유자가 없다. 검색 보드의 제목은 검색어인데, 서로 다른 검색어라는 가정이라 보드마다 다른 문자열을 쓴다.
-- 사용자 보드는 공개로 만든다 — 앱이 사용자 보드를 만들 때의 기본값이다.
INSERT INTO board (user_id, board_type, visibility, title, content_signature, like_count, created_at)
SELECT CASE b.kind WHEN 'CUSTOM' THEN b.actor END,
       CASE b.kind WHEN 'AI' THEN 'AI_RECOMMEND' WHEN 'SEARCH' THEN 'SEARCH_RECOMMEND' ELSE 'USER_CUSTOM' END,
       'PUBLIC', b.title, s.signature, 0, b.created_at
FROM plan_board b JOIN plan_sig s USING (k)
WHERE s.n = 8
ORDER BY b.k
ON CONFLICT DO NOTHING;

CREATE TEMP TABLE board_map ON COMMIT DROP AS
SELECT b.k, bo.id AS board_id, b.kind, b.actor, b.created_at
FROM plan_board b JOIN board bo ON bo.title = b.title;
CREATE UNIQUE INDEX ON board_map (k);

INSERT INTO board_item (board_id, content_id, slot_no)
SELECT m.board_id, i.content_id, i.slot
FROM plan_item i JOIN board_map m USING (k)
ORDER BY m.board_id, i.slot;

-- ── 좋아요 ──────────────────────────────────────────────────────────────
CREATE TEMP TABLE plan_like (board_id bigint, user_id bigint, created_at timestamp) ON COMMIT DROP;

-- ① 새 AI·검색 보드를 만든 좋아요. 그 보드를 만든 시각에 그 사용자가 누른 것이다.
INSERT INTO plan_like
SELECT board_id, actor, created_at FROM board_map WHERE kind IN ('AI', 'SEARCH');
CREATE INDEX ON plan_like (user_id, board_id);

-- ② 인기 보드에 누른 좋아요 — 지프 분포.
--
-- 모든 보드(AI·검색·사용자, 전부 공개)에 무작위로 인기 순위를 매긴다. 어떤 보드가 위에 오를지는
-- 만든 시각과 상관없다. 쿼리 비용은 좋아요가 언제 달렸는지 보지 않으므로 결과가 같다.
-- 시딩한 데이터는 "6개월 뒤의 최종 상태"라 그 안에서 순위가 움직이지 않는다. API 부하 테스트에서
-- 좋아요가 오면 like_count가 올라 순위가 움직인다.
CREATE TEMP TABLE pop_rank ON COMMIT DROP AS
SELECT board_id, created_at, row_number() OVER (ORDER BY random())::int AS rnk FROM board_map;
CREATE UNIQUE INDEX ON pop_rank (rnk);

-- 사용자마다 순위를 지프 분포로 뽑는다. 연속 근사의 역함수로 한 번에 계산한다(N = 보드 수).
--   s = 1  : rank = (N+1)^u
--   s ≠ 1  : rank = (1 + u·((N+1)^(1-s) - 1))^(1/(1-s))
-- 같은 보드를 두 번 뽑으면 버린다 — 한 사람은 한 보드에 한 번만 누른다. 그래서 상위 보드는
-- 거의 모든 사용자가 누른 상태(최대 1,200)가 되고, 그 아래로 긴 꼬리가 생긴다.
-- 버리는 만큼을 채우려고 필요한 수의 2배 + 50번을 뽑고, 뽑힌 순서대로 필요한 만큼만 쓴다.
-- 자기가 이미 누른 보드(① 자기 AI·검색 보드)도 뺀다.
WITH n AS (SELECT count(*)::float8 AS n FROM pop_rank),
draw AS (
    SELECT p.user_id, p.pop_n, p.days, d AS seq,
           LEAST(GREATEST(floor(
               CASE WHEN (:zipf_s)::float8 = 1
                    THEN power(n.n + 1, random())
                    ELSE power(1 + random() * (power(n.n + 1, 1 - (:zipf_s)::float8) - 1),
                               1 / (1 - (:zipf_s)::float8))
               END)::int, 1), n.n::int) AS rnk
    FROM plan_user p
    CROSS JOIN n
    CROSS JOIN LATERAL generate_series(1, p.pop_n * 2 + 50) d
    WHERE p.pop_n > 0
), cand AS (
    SELECT DISTINCT ON (dr.user_id, r.board_id)
           dr.user_id, dr.pop_n, dr.days, dr.seq, r.board_id, r.created_at AS board_at
    FROM draw dr JOIN pop_rank r USING (rnk)
    WHERE NOT EXISTS (SELECT 1 FROM plan_like l WHERE l.user_id = dr.user_id AND l.board_id = r.board_id)
    ORDER BY dr.user_id, r.board_id, dr.seq
), kept AS (
    SELECT *, row_number() OVER (PARTITION BY user_id ORDER BY seq) AS nth FROM cand
)
-- 좋아요 시각은 "보드가 생긴 뒤"이면서 "그 사용자가 가입한 뒤"인 구간에서 무작위로 정한다.
INSERT INTO plan_like
SELECT board_id, user_id, s + random() * (now() - s)
FROM (SELECT board_id, user_id, GREATEST(board_at, now() - (days || ' days')::interval) AS s
      FROM kept WHERE nth <= pop_n) x;

-- 시각 순서대로 넣는다. 실제 서비스에서는 여러 사용자의 좋아요가 시간 순으로 뒤섞여 쌓이므로,
-- 한 사용자의 좋아요는 힙 곳곳에 흩어진다. 순서를 정하지 않으면 한 사용자의 좋아요가 몇 페이지에
-- 몰려 힙을 찾아가는 쿼리가 실제보다 싸게 나온다(2026-09-26 실측: 1만 건이 84페이지에 몰렸다).
INSERT INTO board_feedback (board_id, user_id, rating, created_at)
SELECT board_id, user_id, 'LIKE', created_at FROM plan_like
ORDER BY created_at
ON CONFLICT DO NOTHING;

-- like_count를 실제 좋아요 수와 맞춘다. 안 맞으면 인기 보드 정렬이 엉뚱해진다.
UPDATE board SET like_count = c.n
FROM (SELECT f.board_id, count(*) n FROM board_feedback f
      JOIN board_map m ON m.board_id = f.board_id GROUP BY f.board_id) c
WHERE board.id = c.board_id;

COMMIT;

ANALYZE users; ANALYZE user_vector; ANALYZE board; ANALYZE board_item; ANALYZE board_feedback;

-- 셸이 파싱할 수 있도록 한 줄로 내보낸다. seed.sh가 기대값과 비교한다.
--   실제·기대 쌍(계정, AI 보드, 검색 보드, 사용자 보드, board_item, 좋아요, 최다 사용자 좋아요, 최다 사용자 보드),
--   분포(최다 좋아요 보드, 상위 100개 몫 %, 좋아요 2개 이상인 보드)
SELECT
    (SELECT count(*) FROM users WHERE login_id LIKE 'loadtest_u%'),                         e.users,
    (SELECT count(*) FROM board WHERE title LIKE 'loadtest-seeded-%'),                      e.ai_boards,
    (SELECT count(*) FROM board WHERE title LIKE 'loadtest-search-%'),                      e.search_boards,
    (SELECT count(*) FROM board WHERE title LIKE 'loadtest-custom-%'),                      e.custom_boards,
    (SELECT count(*) FROM board_item bi JOIN board b ON b.id = bi.board_id WHERE b.title LIKE 'loadtest-%'), e.items,
    (SELECT count(*) FROM board_feedback f JOIN users u ON u.id = f.user_id WHERE u.login_id LIKE 'loadtest_u%'), e.likes,
    (SELECT max(c) FROM (SELECT count(*) c FROM board_feedback f JOIN users u ON u.id = f.user_id
                          WHERE u.login_id LIKE 'loadtest_u%' GROUP BY f.user_id) t),  e.max_user_likes,
    (SELECT max(c) FROM (SELECT count(*) c FROM board WHERE title LIKE 'loadtest-custom-%' GROUP BY user_id) t), e.max_user_boards,
    (SELECT max(like_count) FROM board WHERE title LIKE 'loadtest-%'),
    (SELECT round(100.0 * sum(like_count) FILTER (WHERE rn <= 100) / NULLIF(sum(like_count), 0))
       FROM (SELECT like_count, row_number() OVER (ORDER BY like_count DESC) rn
               FROM board WHERE title LIKE 'loadtest-%') t),
    (SELECT count(*) FROM board WHERE title LIKE 'loadtest-%' AND like_count >= 2)
FROM seed_expect e;
