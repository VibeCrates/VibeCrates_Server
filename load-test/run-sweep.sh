#!/usr/bin/env bash
# 도착률을 단계별로 올리며 "목표 응답 시간을 지키는 최대 처리율"을 찾는다. 자원 상한을 정하는 측정이다.
#
#   ./load-test/run-sweep.sh <시나리오> [도착률...]
#   ./load-test/run-sweep.sh api-recommend                 # 기본 10 25 50 75 100 150 200 (/s)
#   ./load-test/run-sweep.sh api-search-board 5 10 20 30
#   BACKEND_CPUS=2 로 띄운 뒤 같은 명령을 다시 돌려 사양끼리 비교한다
#
#   환경변수  DURATION=60s          단계 하나의 길이
#             WARMUP_DURATION=120s  첫 단계 도착률로 한 번 돌리고 버린다
#             KEEP_GOING=1          목표를 못 지킨 단계 뒤에도 계속 올린다(기본은 거기서 멈춘다)
#
# run-measure.sh와 무엇이 다른가 — run-measure는 일감을 고정하고 얼마나 빨리 끝내나(닫힌 모델)를 반복해
# 중앙값으로 비교한다. 코드 변경 전후 비교에 맞다. 여기서는 초당 N건이 서버 상태와 무관하게 도착하고(열린 모델),
# 각 단계가 목표를 지켰는지를 본다. "이 사양으로 초당 몇 건까지 받을 수 있나"는 이쪽으로만 답할 수 있다.
#
# 단계 판정 — 셋 다 지켜야 통과다.
#   p95 < 시나리오의 목표(lib/options.js arrival의 p95)
#   실패율 < 1%
#   제때 시작 못 한 요청(dropped_iterations) < 1% — 넘으면 그 도착률은 실제로 걸리지 않은 것이다
#
# 결과는 load-test/results/sweep-<시나리오>-<시각>/ 에 단계마다 k6 요약(rateN.json), 자원 기록(rateN.csv),
# 그 단계의 비싼 쿼리 상위 5개(rateN-pgss.txt)가 남는다.
#
# 데이터 상태 — 읽기 시나리오는 상태를 바꾸지 않는다. 좋아요 시나리오(api-like-toggle, api-recommend-like,
# api-search-like)는 좋아요→취소 짝이라 개수는 그대로다 — 새로 저장된 추천·검색 보드는 취소로 다시 지워진다.
# 단계 사이에는 pg_stat_statements만 초기화한다.
#
# 캐시 — API 개별 측정(api-*)은 Redis 캐시를 끄고(CACHE_ENABLED=false로 띄운 backend), 혼합 측정(mixed)은 켜고 잰다
# (docker-compose.loadtest.yml 머리말). 맞지 않으면 시작하지 않는다. 확인을 건너뛰려면 CACHE_CHECK=0.
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./crates_server.env; set +a
. load-test/lib/common.sh

NAME="${1:?시나리오 이름이 필요합니다 (load-test/scenarios/ 안의 파일명)}"; shift
NAME="${NAME%.js}"
SCENARIO="load-test/scenarios/${NAME}.js"
[ -f "$SCENARIO" ] || { echo "시나리오를 찾을 수 없습니다: $SCENARIO" >&2; exit 1; }
[ "$#" -gt 0 ] && RATES=("$@") || RATES=(10 25 50 75 100 150 200)
DURATION="${DURATION:-60s}"
WARMUP_DURATION="${WARMUP_DURATION:-120s}"
OUT_DIR="load-test/results/sweep-${NAME}-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"

PG="docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -t -A -c"

# 캐시 상태 확인. 컨테이너 환경 변수에 없으면 application.yaml 기본값(켬)이다.
[ "$NAME" = "mixed" ] && WANT_CACHE=true || WANT_CACHE=false
CACHE_NOW=$(docker inspect backend_server --format '{{range .Config.Env}}{{println .}}{{end}}' 2>/dev/null |
    sed -n 's/^RECOMMENDATION_CACHE_ENABLED=//p')
CACHE_NOW="${CACHE_NOW:-true}"
if [ "${CACHE_CHECK:-1}" = "1" ] && [ "$CACHE_NOW" != "$WANT_CACHE" ]; then
    echo "캐시가 이 시나리오와 맞지 않습니다: 지금 ${CACHE_NOW}, ${NAME}은(는) ${WANT_CACHE}로 잰다." >&2
    if [ "$WANT_CACHE" = "false" ]; then
        echo "  CACHE_ENABLED=false docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d backend" >&2
    else
        echo "  docker compose --env-file crates_server.env -f docker-compose.yml -f docker-compose.loadtest.yml up -d backend" >&2
    fi
    exit 1
fi
echo "캐시: ${CACHE_NOW}"
# 혼합 측정은 캐시를 비우고 시작한다. 앞선 실행이 남긴 캐시가 있으면 워밍업 전부터 적중해 실행마다 출발점이 달라진다.
if [ "$CACHE_NOW" = "true" ]; then
    for pattern in 'reco:v1:*' 'emb:v1:*'; do
        docker exec redis_server sh -c "redis-cli --scan --pattern '$pattern' | xargs -r redis-cli del" >/dev/null
    done
fi

# 좋아요→취소 짝이 고를 보드. 짝을 보내도 지워지지 않는 보드만 고른다(lib/boards.js 머리말).
export BOARD_IDS_FILE="$PWD/$OUT_DIR/boards.json"
$PG "SELECT json_agg(id ORDER BY id) FROM board
      WHERE deleted_at IS NULL AND (board_type = 'USER_CUSTOM' OR like_count >= 2)" > "$BOARD_IDS_FILE"
echo "좋아요 대상 보드: $(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))))" "$BOARD_IDS_FILE")개"

# 그 단계에서 DB 시간을 가장 많이 쓴 쿼리. 부하 중에는 백분위가 없어 평균과 총합 비중으로 본다.
dump_pgss() {
    $PG "SELECT calls, round(mean_exec_time::numeric, 3) AS mean_ms,
                round((100 * total_exec_time / nullif(sum(total_exec_time) OVER (), 0))::numeric, 1) AS share_pct,
                left(regexp_replace(query, '\s+', ' ', 'g'), 120)
           FROM pg_stat_statements
          WHERE query NOT LIKE '%pg_stat_statements%'
          ORDER BY total_exec_time DESC LIMIT 5" > "$1" 2>/dev/null || true
}

# k6를 한 번 돌린다. $1 도착률, $2 길이, $3 결과 이름(없으면 버린다)
run_step() {
    local rate="$1" duration="$2" tag="${3:-}" summary sampler_pid=""
    if [ -n "$tag" ]; then
        summary="$OUT_DIR/$tag.json"
        ./load-test/lib/sampler.sh "$OUT_DIR/$tag.csv" &
        sampler_pid=$!
    else
        summary=$(mktemp -t k6sum)
    fi
    k6 run --quiet -e RATE="$rate" -e DURATION="$duration" -e TOKENS_FILE="$TOKENS_FILE" -e SKIP_AI="${SKIP_AI:-0}" \
        -e BOARD_IDS_FILE="$BOARD_IDS_FILE" \
        --summary-export="$summary" "$SCENARIO" \
        > "${OUT_DIR}/${tag:-warmup}.log" 2>&1 || true
    if [ -n "$sampler_pid" ]; then kill "$sampler_pid" 2>/dev/null || true; wait "$sampler_pid" 2>/dev/null || true; fi
    [ -n "$tag" ] || rm -f "$summary"
}

# 단계 결과 한 줄: "달성률 p50 p95 p99 실패% 누락% 통과여부 목표p95"
#   혼합 시나리오(mixed)는 엔드포인트마다 목표가 따로 걸려 있다. 그때는 하나라도 넘으면 실패이고,
#   표의 p50~p99는 전체 요청 기준, 목표 칸은 '엔드포인트별'이다. 엔드포인트별 값은 $4 파일에 남긴다.
#   좋아요→취소처럼 반복 한 번에 요청을 여러 개 보내는 개별 시나리오도 이름별로 판정한다. 그때 도착률은 반복(쌍) 수라
#   달성률도 반복 수로 센다. 혼합은 도착률이 요청 수라 요청 수로 센다($5).
judge() {
    python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import json, re, sys
m = json.load(open(sys.argv[1]))['metrics']
rate, seconds, detail = float(sys.argv[2]), float(sys.argv[3].rstrip('s')), sys.argv[4]
per_request = sys.argv[5] == 'mixed'
def target_of(metric):
    return next((float(re.search(r'<(\d+)', k).group(1)) for k in metric.get('thresholds', {}) if 'p(95)' in k), None)
base = m.get('api_duration', {})
subs = {k[len('api_duration{name:'):-1]: v for k, v in m.items() if k.startswith('api_duration{name:')}
dropped = m.get('dropped_iterations', {}).get('count', 0)
failed = m.get('api_failed', {}).get('value', 1.0) * 100
if subs:
    # 이름별 판정(혼합, 좋아요→취소 짝). 혼합은 요청 수 기준 — like·unlike는 반복 1번에 요청 2개라 iterations로 세면
    # 어긋난다. 요청 수는 Rate인 api_failed의 통과+실패로 센다. 개별 짝 시나리오는 도착률이 반복 수라 iterations로 센다.
    failed_metric = m.get('api_failed', {})
    if per_request:
        done = failed_metric.get('passes', 0) + failed_metric.get('fails', 0)
    else:
        done = m.get('iterations', {}).get('count', 0)
    ok_targets = all(v.get('p(95)', float('inf')) < target_of(v) for v in subs.values())
    with open(detail, 'w') as f:
        f.write(f"{'엔드포인트':16} {'요청/s':>7} {'p50':>6} {'p95':>6} {'p99':>6} {'목표':>6}  판정\n")
        for name, v in sorted(subs.items()):
            t = target_of(v); p95 = v.get('p(95)', float('inf'))
            f.write(f"{name:16} {v.get('count', 0) / seconds:7.1f} {v.get('med', 0):6.0f} {p95:6.0f} {v.get('p(99)', 0):6.0f} {t:6.0f}  {'통과' if p95 < t else '실패'}\n")
    target = '엔드포인트별'
else:
    # iterations.rate는 setup 시간까지 분모에 넣어 낮게 나온다. 단계 길이로 직접 나눈다.
    done = m.get('iterations', {}).get('count', 0)
    t = target_of(base)
    ok_targets = base.get('p(95)', float('inf')) < t
    target = f"{t:.0f}"
drop_pct = dropped / max(done + dropped, 1) * 100
ok = ok_targets and failed < 1 and drop_pct < 1
print(f"{done / seconds:.1f} {base.get('med', 0):.0f} {base.get('p(95)', 0):.0f} {base.get('p(99)', 0):.0f} {failed:.2f} {drop_pct:.1f} {'통과' if ok else '실패'} {target}")
PY
}

# 자원 기록의 최대값: "backend pg qdrant k6 (CPU%) 풀대기 qdrant메모리(MiB)"
step_peaks() {
    python3 - "$1" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
def mx(col):
    vals = [float(r[col]) for r in rows if r.get(col) not in (None, '')]
    return f"{max(vals):.0f}" if vals else "-"
print(mx('backend_cpu'), mx('postgres_cpu'), mx('qdrant_cpu'), mx('k6_cpu'), mx('hikari_pending'), mx('qdrant_mem_mib'))
PY
}

wait_ready
limits=$(docker inspect backend_server postgres_server qdrant_server --format '{{.Name}} {{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}' 2>/dev/null |
    awk '{ sub(/^\//, "", $1); sub(/_server$/, "", $1); printf "%s %.1f코어/%.1fGB  ", $1, $2 / 1e9, $3 / 1073741824 }' || true)
echo "시나리오: $SCENARIO   단계: ${RATES[*]} (/s) × $DURATION"
echo "자원 상한: ${limits:-확인 실패}"
echo "결과: $OUT_DIR"
echo "$limits" > "$OUT_DIR/limits.txt"
echo

# 로그인(BCrypt)은 측정 밖에서 한 번만 한다. lib/tokens.sh 머리말 참고.
printf '토큰 준비 ... '
export TOKENS_FILE="$PWD/$OUT_DIR/tokens.json"
./load-test/lib/tokens.sh "$TOKENS_FILE"
echo "$(python3 -c "import json,sys;print(len(json.load(open(sys.argv[1]))))" "$TOKENS_FILE")개 계정"

printf '워밍업(버림, %s/s × %s) ... ' "${RATES[0]}" "$WARMUP_DURATION"
run_step "${RATES[0]}" "$WARMUP_DURATION"
echo '완료'

printf '\n%7s %8s %6s %6s %6s %6s %6s  %-4s  %s\n' 목표/s 달성/s p50 p95 p99 실패% 누락% 판정 "최대 CPU% backend/pg/qdrant/k6 · 풀 대기 · qdrant MiB"
printf -- '-------------------------------------------------------------------------------------------------\n'
last_pass="-"
for rate in "${RATES[@]}"; do
    $PG "SELECT pg_stat_statements_reset()" >/dev/null 2>&1 || true
    run_step "$rate" "$DURATION" "rate$rate"
    dump_pgss "$OUT_DIR/rate$rate-pgss.txt"
    read -r got p50 p95 p99 failed dropped verdict target <<<"$(judge "$OUT_DIR/rate$rate.json" "$rate" "$DURATION" "$OUT_DIR/rate$rate-endpoints.txt" "$NAME")"
    read -r c_be c_pg c_qd c_k6 pend q_mem <<<"$(step_peaks "$OUT_DIR/rate$rate.csv")"
    printf '%7s %8s %6s %6s %6s %6s %6s  %-4s  %s/%s/%s/%s · %s · %s\n' \
        "$rate" "$got" "$p50" "$p95" "$p99" "$failed" "$dropped" "$verdict" "$c_be" "$c_pg" "$c_qd" "$c_k6" "$pend" "$q_mem"
    [ -f "$OUT_DIR/rate$rate-endpoints.txt" ] && sed 's/^/          /' "$OUT_DIR/rate$rate-endpoints.txt"
    if [ "$verdict" = "통과" ]; then
        last_pass="$rate"
    elif [ "${KEEP_GOING:-0}" != "1" ]; then
        break
    fi
done

echo
echo "목표 p95(${target})를 지킨 최대 도착률: ${last_pass}/s"
echo "단계별 비싼 쿼리: $OUT_DIR/rate*-pgss.txt"
echo
echo "읽는 법"
echo "  backend CPU가 상한(코어×100%)에 붙음  → 앱 CPU가 모자람"
echo "  CPU는 여유인데 풀 대기가 오름          → 커넥션 풀(10개)이 모자람"
echo "  pg·qdrant CPU가 상한에 붙음           → 그 서비스가 모자람"
echo "  k6 CPU가 높음(200% 이상)               → 부하 생성기의 한계. 그 단계는 믿지 말 것"
