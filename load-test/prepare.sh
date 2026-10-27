#!/usr/bin/env bash
# 측정용 데이터를 처음부터 다시 만든다. 레포지토리 측정과 API 측정이 같은 스크립트로 같은 데이터를 쓴다.
#
#   ./load-test/prepare.sh                   # 기본: 6개월 누적(seed.sh 3 1 0.6 1.0 1) — 계정 1,200 / 보드 364,800 / 좋아요 456,000
#   ./load-test/prepare.sh 3 1 0.6 1.2 1     # 인자는 seed.sh에 그대로 넘긴다(하루 좋아요 / 하루 보드 수정 / 인기 보드 비율 / 지프 s / 하루 검색 좋아요)
#
# 정리 → 시딩 → 취향 벡터 재계산 → VACUUM ANALYZE → 통계 초기화. 몇 분 걸리므로 측정마다 돌리지 않는다.
# 측정 사이의 초기화는 run-measure.sh가 가볍게 한다.
#
# 전제: backend가 8080에 떠 있어야 한다. 기준 계정의 취향 벡터를 가입 직후 콘텐츠 선택 API로 만들고(seed.sh),
# 전원의 취향 벡터를 앱의 재계산으로 맞춘다(lib/recalc-vectors.sh). 레포지토리 측정만 할 때는 SKIP_RECALC=1.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a

[ "$#" -gt 0 ] && SEED_ARGS=("$@") || SEED_ARGS=(3 1 0.6 1.0 1)

PSQL_IN="docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates"
PSQL="docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -t -A"

echo "① 정리 — 이전 실행의 행을 지우고 VACUUM FULL로 공간을 회수한다"
$PSQL_IN -q -v ON_ERROR_STOP=1 < load-test/cleanup.sql >/dev/null

echo "② 시딩 — seed.sh ${SEED_ARGS[*]}"
./load-test/seed.sh "${SEED_ARGS[@]}"

# seed.sh는 기준 계정의 취향 벡터를 전원에게 복사한다. 좋아요와 맞지 않는 값이라 추천 측정이 실제보다 좋게 나오고,
# 쓰기 측정이 벡터를 바꿔 측정 순서에 따라 결과가 달라진다. 앱의 재계산으로 전원을 맞춘다.
# 레포지토리 측정만 할 때는 필요 없다 — SKIP_RECALC=1로 건너뛴다.
if [ "${SKIP_RECALC:-0}" != "1" ]; then
    echo "②-1 취향 벡터 재계산 — 각자의 좋아요로"
    ./load-test/lib/recalc-vectors.sh
fi

# 재계산 중의 좋아요→취소가 죽은 튜플을 남기므로 VACUUM은 그 뒤에 둔다.
# cleanup.sql이 VACUUM FULL을 시딩 앞에 돌리므로, 시딩 직후에는 가시성 맵이 비어 있다.
# 그 상태로 재면 Index Only Scan이 힙을 다시 찾아가 수천 블록이 더 나온다.
echo "③ VACUUM ANALYZE — 가시성 맵을 채운다"
$PSQL -c "VACUUM ANALYZE" >/dev/null

$PSQL -c "SELECT pg_stat_statements_reset()" >/dev/null

echo
echo "결과"
$PSQL -F $'\t' <<'SQL' | while IFS=$'\t' read -r k v; do printf '  %-26s %s\n' "$k" "$v"; done
SELECT '계정', count(*)::text FROM users
UNION ALL SELECT '보드 (AI / 검색 / 사용자)',
       count(*) FILTER (WHERE board_type = 'AI_RECOMMEND') || ' / ' || count(*) FILTER (WHERE board_type = 'SEARCH_RECOMMEND')
       || ' / ' || count(*) FILTER (WHERE board_type = 'USER_CUSTOM')
  FROM board
UNION ALL SELECT '좋아요', count(*)::text FROM board_feedback
UNION ALL SELECT '가시성 맵 (board_feedback)', relallvisible || ' / ' || relpages || ' 페이지'
  FROM pg_class WHERE relname = 'board_feedback';
SQL
