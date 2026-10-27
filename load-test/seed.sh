#!/usr/bin/env bash
# 부하테스트용 계정·보드·좋아요를 준비한다 — 6개월 누적 시나리오.
#
#   ./load-test/seed.sh [하루좋아요] [하루보드수정] [인기보드비율] [지프s] [하루검색좋아요]
#   ./load-test/seed.sh 3 1 0.6 1.0 1    # 기본. 계정 1,200 / 보드 364,800 / 좋아요 456,000
#   ./load-test/seed.sh 3 1 0.6 1.0 0    # 검색 보드 없이(2026-10-27 이전 환경). 보드 250,800 / 좋아요 342,000
#
# 가입 시기별 인원(누적 100 → 200 → 500 → 800 → 1,000 → 1,200명)은 seed.sql 맨 위의 cohort 표에 있다.
# 가정과 근거는 seed.sql 머리말과 Notion "레포지토리 메서드 성능 테스트".
#
# k6와 역할이 다르다. 여기는 "측정하려는 상태"를 만들고, k6는 그 상태에 부하를 건다.
# 그동안 k6가 둘을 겸하면서 실행마다 상태가 누적돼 비교가 깨졌다(docs 4-9).
#
# 대부분을 SQL로 꽂는다. API로 좋아요 200건을 만들면 요청 200번 + 비동기 재계산 200번이라
# 수십 초가 걸리고, 준비 자체가 시스템을 데워 측정 조건을 바꾼다.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a

LIKES_PER_DAY="${1:-3}"; EDITS_PER_DAY="${2:-1}"; POPULAR_RATIO="${3:-0.6}"; ZIPF_S="${4:-1.0}"; SEARCH_LIKES_PER_DAY="${5:-1}"
awk -v r="$POPULAR_RATIO" 'BEGIN{exit !(r>=0 && r<=1)}' || { echo "인기 보드 비율은 0~1이어야 합니다: $POPULAR_RATIO" >&2; exit 1; }
awk -v s="$ZIPF_S" 'BEGIN{exit !(s>0)}' || { echo "지프 s는 0보다 커야 합니다: $ZIPF_S" >&2; exit 1; }
awk -v n="$SEARCH_LIKES_PER_DAY" 'BEGIN{exit !(n>=0)}' || { echo "하루 검색 좋아요는 0 이상이어야 합니다: $SEARCH_LIKES_PER_DAY" >&2; exit 1; }

PSQL="docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates"
PSQL1="docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -t -A -c"

# 덧붙이지 않는다. 가입 시기와 사용자별 양이 정해진 시나리오라, 이전 데이터가 남아 있으면 그 위에 섞여
# 가정과 다른 데이터가 된다.
left=$($PSQL1 "SELECT count(*) FROM users WHERE login_id LIKE 'loadtest_u%'")
[ "$left" = "0" ] || { echo "이전 시딩이 남아 있습니다(loadtest_u* ${left}명). 먼저 load-test/cleanup.sql을 실행하세요." >&2; exit 1; }

# 기준 계정을 한 번 만든다. 나머지 계정은 여기서 비밀번호 해시와 취향 벡터를 복사한다.
#
# 회원가입 API가 없어졌으므로(가입은 OAuth만 받는다) 계정 행은 SQL로 만든다. BCrypt 해시는
# pgcrypto의 crypt(.., gen_salt('bf'))가 Spring BCryptPasswordEncoder와 같은 $2a$ 형식으로 만든다.
# 이미 있으면 건너뛴다.
if [ "$($PSQL1 "SELECT count(*) FROM users WHERE login_id='loadtest_seed'")" = "0" ]; then
    printf '기준 계정 생성(SQL) ... '
    $PSQL1 "CREATE EXTENSION IF NOT EXISTS pgcrypto" >/dev/null
    $PSQL1 "INSERT INTO users (login_id, pwd, email, nickname, birth_date, role, gender, login_type, created_at, updated_at)
            VALUES ('loadtest_seed', crypt('Loadtest!234', gen_salt('bf', 10)), 'loadtest_seed@example.com',
                    'loadtest_seed', DATE '1995-01-01', 'USER', 'OTHER', 'LOCAL', now(), now())" >/dev/null
    echo '완료'
fi

# 취향 벡터는 실제 가입 흐름과 같은 API로 만든다. 콘텐츠 벡터가 Qdrant에 있어 SQL로는 평균을 낼 수 없다.
# 가입 화면의 후보 목록(onboarding_content)에서 종류마다 인기 1위를 하나씩 고른다(3개). 목록 밖의 콘텐츠는
# 서버가 거절한다. 값 자체는 측정에 영향이 없고(prepare.sh가 전원을 좋아요로 다시 계산한다), 있기만 하면 된다.
if [ "$($PSQL1 "SELECT count(*) FROM user_vector uv JOIN users u ON u.id=uv.user_id WHERE u.login_id='loadtest_seed'")" = "0" ]; then
    printf '기준 계정 취향 콘텐츠 선택(API) ... '
    token=$(curl -s -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
        -d '{"loginId":"loadtest_seed","pwd":"Loadtest!234"}' \
        | python3 -c "import json,sys; print(json.load(sys.stdin)['data']['accessToken'])" 2>/dev/null || true)
    [ -n "$token" ] || { echo "로그인 실패 — 앱이 떠 있는지 확인하세요" >&2; exit 1; }
    ids=$($PSQL1 "SELECT string_agg(content_id::text, ',') FROM (SELECT DISTINCT ON (c.dtype) o.content_id FROM onboarding_content o JOIN content c ON c.id = o.content_id ORDER BY c.dtype, o.popularity_rank) t")
    code=$(curl -s -o /dev/null -w '%{http_code}' -X POST http://localhost:8080/api/users/me/initial-contents \
        -H "Authorization: Bearer $token" -H 'Content-Type: application/json' -d "{\"contentIds\":[$ids]}")
    case "$code" in 200) echo "완료 (콘텐츠 $ids)";; *) echo "실패(HTTP $code)" >&2; exit 1;; esac
fi

echo "준비: 하루 좋아요 ${LIKES_PER_DAY}번(인기 보드 ${POPULAR_RATIO}) / 하루 검색 좋아요 ${SEARCH_LIKES_PER_DAY}번 / 하루 보드 수정 ${EDITS_PER_DAY}번 / 지프 s=${ZIPF_S}"
started=$(date +%s)
read -r users e_users ai e_ai search e_search custom e_custom items e_items likes e_likes \
        max_ul e_max_ul max_ub e_max_ub top_like top100 liked2 <<<"$(
    $PSQL -q -t -A -F' ' -v likes_per_day="$LIKES_PER_DAY" -v edits_per_day="$EDITS_PER_DAY" \
          -v popular_ratio="$POPULAR_RATIO" -v zipf_s="$ZIPF_S" -v search_likes_per_day="$SEARCH_LIKES_PER_DAY" \
          < load-test/seed.sql
)"

printf '  %-22s %12s %12s\n' "" "실제" "기대"
printf '  %-22s %12s %12s\n' "계정" "$users" "$e_users"
printf '  %-22s %12s %12s\n' "AI 추천 보드" "$ai" "$e_ai"
printf '  %-22s %12s %12s\n' "검색 추천 보드" "$search" "$e_search"
printf '  %-22s %12s %12s\n' "사용자가 만든 보드" "$custom" "$e_custom"
printf '  %-22s %12s %12s\n' "board_item" "$items" "$e_items"
printf '  %-22s %12s %12s\n' "좋아요" "$likes" "$e_likes"
printf '  %-22s %12s %12s\n' "최다 사용자 좋아요" "$max_ul" "$e_max_ul"
printf '  %-22s %12s %12s\n' "최다 사용자 보드" "$max_ub" "$e_max_ub"
echo "  인기 분포: 최다 좋아요 보드 ${top_like}개 / 상위 100개 몫 ${top100}% / 좋아요 2개 이상 보드 ${liked2}개"
echo "  ($(( $(date +%s) - started ))초)"

# ── 검증 ───────────────────────────────────────────────────
# 가정과 다른 데이터로 재면 결과를 "6개월 운영 상태"라고 부를 수 없다. 개수가 하나라도 어긋나면 멈춘다.
#   보드가 모자라면  무작위 콘텐츠 조합이 겹쳐 ON CONFLICT가 버린 것
#   좋아요가 모자라면 인기 보드 추첨이 겹침을 빼고 나서 필요한 수를 채우지 못한 것
for pair in "$users:$e_users:계정" "$ai:$e_ai:AI 보드" "$search:$e_search:검색 보드" "$custom:$e_custom:사용자 보드" \
            "$items:$e_items:board_item" "$likes:$e_likes:좋아요" "$max_ul:$e_max_ul:최다 사용자 좋아요"; do
    IFS=: read -r got want name <<<"$pair"
    [ "$got" = "$want" ] || { echo "  ⚠️  ${name}이(가) 기대와 다릅니다: ${got} (기대 ${want})" >&2; exit 1; }
done

echo
echo "비밀번호는 모두 Loadtest!234 입니다. 정리는 load-test/cleanup.sql."
