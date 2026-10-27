#!/usr/bin/env bash
# 시딩한 계정 전원의 취향 벡터를 자기 좋아요로 다시 계산한다. prepare.sh가 시딩 뒤에 부른다.
#
#   ./load-test/lib/recalc-vectors.sh [동시실행=4]
#
# 왜 필요한가 — seed.sh는 기준 계정 하나의 취향 벡터(가입 때 고른 콘텐츠의 평균)를 전원에게 복사한다.
# 좋아요(1개월차 가입자 720건)와 맞지 않는 값이라 API 측정이 두 군데서 틀어진다.
#   - 추천: 1,200명이 같은 벡터로 검색해 결과와 캐시가 한곳에 몰린다. 실제보다 좋게 나온다.
#   - 쓰기: 좋아요 한 번이면 재계산이 돌아 벡터가 "좋아요로 계산한 값"으로 바뀐다. 좋아요→취소 짝으로
#           상태를 유지한다는 전제가 깨지고, 쓰기 측정 뒤의 추천 측정은 다른 데이터를 재게 된다.
# 그래서 측정 전에 전원을 "재계산이 끝난 상태"로 맞춘다. 이 상태에서는 좋아요→취소 짝이 벡터를 바꾸지 않는다.
#
# 어떻게 — 앱의 재계산을 그대로 쓴다. 계산 방식을 SQL로 흉내 내면 앱과 어긋날 수 있다.
#   계정마다 좋아요하지 않은 AI 보드 하나에 좋아요 → 재계산 반영을 기다림 → 취소 → 재계산 반영을 기다림.
#   취소 뒤의 재계산이 원래 좋아요만으로 계산하므로, 끝나면 좋아요·like_count는 그대로이고 벡터만 맞춰진다.
#
#   좋아요 뒤의 재계산을 기다리는 이유: 재계산은 커밋 후 비동기다. 기다리지 않고 바로 취소하면
#   "좋아요 포함"으로 계산한 쪽이 늦게 끝나 최종 벡터를 덮어쓸 수 있다.
#
#   백필(5분마다 100명)로 맞추면 1,200명에 1시간이 걸려서 쓰지 않는다.
#
# 재계산 1회 실측(좋아요 1만 건, 콘텐츠 벡터 13,500개): 약 1.0~1.3초. 계정당 2회라
# 실측(2026-09-27, 측정용 상한 qdrant 1코어): 동시 4개로 1,000명에 30분(100명당 약 180초). 그때는 계정당 좋아요가
# 1만 건이었다. 지금(최대 720건)은 재계산이 더 가볍지만 계정이 1,200명이다.
#
# 보드는 아직 좋아요하지 않은 AI 보드다. 만든 사람의 좋아요가 남아 있어 취소해도 좋아요 0개가 되지 않는다 — 지워지지 않는다.
# 재계산 하나가 벡터 약 40MB를 올리므로 동시 수를 크게 늘리지 말 것.
#
# 전제: backend가 8080에 떠 있고, seed.sh가 만든 loadtest_u* 계정이 있어야 한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
set -a; . ./crates_server.env; set +a

PARALLEL="${1:-4}"
BASE="${BASE_URL:-http://localhost:8080}"
export BASE POSTGRESQL_USERNAME POSTGRESQL_PASSWORD

q() { docker exec -e PGPASSWORD="$POSTGRESQL_PASSWORD" postgres_server psql -U "$POSTGRESQL_USERNAME" -d crates -t -A -c "$1"; }

# 계정 하나를 처리한다. 인자: "login_id user_id board_id". 실패하면 한 줄을 stderr로 남기고 1로 끝난다.
recalc_one() {
    local login="$1" uid="$2" board="$3" token prev
    q1() { docker exec -e PGPASSWORD="$POSTGRESQL_PASSWORD" postgres_server psql -U "$POSTGRESQL_USERNAME" -d crates -t -A -c "$1"; }
    # 재계산이 끝나 벡터 갱신 시각이 바뀔 때까지 기다린다(최대 60초).
    wait_changed() {
        for _ in $(seq 1 120); do
            [ "$(q1 "SELECT updated_at FROM user_vector WHERE user_id = $uid")" != "$1" ] && return 0
            sleep 0.5
        done
        return 1
    }
    token=$(curl -s -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
        -d "{\"loginId\":\"$login\",\"pwd\":\"Loadtest!234\"}" |
        python3 -c "import json,sys;print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null) ||
        { echo "$login 로그인 실패" >&2; return 1; }

    prev=$(q1 "SELECT updated_at FROM user_vector WHERE user_id = $uid")
    [ "$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $token" "$BASE/api/boards/$board/likes")" = 200 ] ||
        { echo "$login 좋아요 실패(보드 $board)" >&2; return 1; }
    wait_changed "$prev" || { echo "$login 좋아요 뒤 재계산이 60초 안에 끝나지 않음" >&2; return 1; }

    prev=$(q1 "SELECT updated_at FROM user_vector WHERE user_id = $uid")
    [ "$(curl -s -o /dev/null -w '%{http_code}' -X DELETE -H "Authorization: Bearer $token" "$BASE/api/boards/$board/likes")" = 200 ] ||
        { echo "$login 좋아요 취소 실패(보드 $board) — 좋아요 1건이 남았다" >&2; return 1; }
    wait_changed "$prev" || { echo "$login 취소 뒤 재계산이 60초 안에 끝나지 않음" >&2; return 1; }
    echo .
}
export -f recalc_one

# 계정마다 아직 좋아요하지 않은 AI 보드 하나. AI 보드는 소유자가 없어 "내 보드"와 겹칠 일이 없다.
targets=$(q "
SELECT u.login_id, u.id, b.id
  FROM users u
  CROSS JOIN LATERAL (
        SELECT b.id FROM board b
         WHERE b.board_type = 'AI_RECOMMEND' AND b.deleted_at IS NULL
           AND NOT EXISTS (SELECT 1 FROM board_feedback f WHERE f.board_id = b.id AND f.user_id = u.id)
         ORDER BY b.id LIMIT 1) b
 WHERE u.login_id LIKE 'loadtest_u%'
   AND EXISTS (SELECT 1 FROM board_feedback f WHERE f.user_id = u.id)
 ORDER BY u.id" | tr '|' ' ')
total=$(printf '%s\n' "$targets" | grep -c . || true)
[ "$total" -gt 0 ] || { echo "재계산할 계정이 없습니다 (좋아요가 있는 loadtest_u* 계정 0명)"; exit 0; }

echo "취향 벡터 재계산: ${total}명, 동시 ${PARALLEL}개"
started=$(date +%s)
# 진행 표시: 100명마다 경과 시간을 찍는다. 성공한 계정은 "."을 한 줄씩 내므로 그 수가 곧 완료 수다.
# 시각으로 검증하지 않는다 — 시딩은 DB의 now()로, 재계산은 앱이 KST로 써서 시간대가 다르다.
# 각 계정은 recalc_one이 갱신 시각이 바뀐 것을 직접 확인한 뒤에만 "."을 낸다.
done_count=$(printf '%s\n' "$targets" |
    xargs -P "$PARALLEL" -L 1 bash -c 'recalc_one "$@"' _ |
    awk -v total="$total" -v t0="$started" '{ n++; if (n % 100 == 0 || n == total) { "date +%s" | getline now; close("date +%s"); printf "  %d / %d (%ds)\n", n, total, now - t0 > "/dev/stderr" } } END { print n + 0 }') || true
# ↑ 한 계정이라도 실패하면 xargs가 123으로 끝난다. 여기서 멈추지 않고 아래에서 몇 명이 빠졌는지 보여준다.

echo "완료: ${done_count} / ${total}명 ($(( $(date +%s) - started ))초)"
[ "$done_count" -eq "$total" ] || { echo "⚠️  재계산되지 않은 계정이 있습니다. 위의 실패 메시지를 확인하세요." >&2; exit 1; }
