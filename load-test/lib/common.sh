# run-measure.sh와 run-sweep.sh가 함께 쓰는 함수. source해서 쓴다.

# ── 측정 준비 확인 ─────────────────────────────────────────────
# health가 200/401을 주는 시점은 시딩 완료가 아니다. Spring Boot는 ApplicationRunner를
# "Started" 로그 이후에 실행하고 웹 서버는 그보다 먼저 열린다. 그리고 seed completed 로그가
# 찍힌 뒤에도 Qdrant가 HNSW 인덱스를 백그라운드로 짓는다(콘텐츠만 있을 때 실측 35초, CPU 815%).
wait_ready() {
    printf '준비 확인: '
    for _ in $(seq 1 60); do
        [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health || true)" != "000" ] && break
        printf '.'; sleep 5
    done
    # grep -q를 파이프라인에 쓰면 안 된다. 먼저 종료하면서 docker logs가 SIGPIPE로 죽고,
    # set -o pipefail이 그것을 실패로 잡아 멀쩡한 상태에서도 여기서 멈춘다.
    # backend를 막 다시 띄웠으면 웹 서버는 열렸어도 콘텐츠 벡터를 적재하는 중일 수 있다. 바로 실패하지 않고 기다린다.
    local seeded
    for _ in $(seq 1 60); do
        seeded=$(docker logs backend_server 2>&1 | grep -c "content vector seed completed" || true)
        [ "${seeded:-0}" -gt 0 ] && break
        printf '.'; sleep 10
    done
    [ "${seeded:-0}" -gt 0 ] || { echo "Qdrant 시딩 로그 없음" >&2; exit 1; }
    # query 벡터(37만 건)는 처음 적재할 때 몇 분 걸린다. 웹 서버가 열린 뒤에도 적재가 이어진다.
    for _ in $(seq 1 60); do
        loaded=$(docker logs backend_server 2>&1 | grep -c "query vector seed completed" || true)
        [ "${loaded:-0}" -gt 0 ] && break
        printf '.'; sleep 10
    done
    [ "${loaded:-0}" -gt 0 ] || { echo "query 벡터 적재 로그 없음" >&2; exit 1; }
    # 적재 로그가 찍혀도 Qdrant는 HNSW 인덱스를 백그라운드로 짓는다. 그동안 재면 측정과 인덱싱이 CPU를 다퉈 둘 다 느려진다.
    # 예전에는 "Qdrant CPU가 20% 아래"로 판단했는데, 적재가 끝나고 인덱싱이 시작되기 전 CPU가 잠깐 내려간 순간을
    # 완료로 읽었다(2026-10-04: query_vector가 402,000/732,544만 인덱싱된 채 측정이 시작돼 추천 p95 27.8초).
    # 그래서 컬렉션 상태를 직접 본다. 모든 컬렉션이 green(최적화 끝)이어야 한다. 1코어 상한이면 수십 분까지 본다.
    local status
    for _ in $(seq 1 360); do
        status=$(curl -s http://localhost:6333/collections | python3 -c "
import json, sys, urllib.request
names = [c['name'] for c in json.load(sys.stdin)['result']['collections']]
states = [json.load(urllib.request.urlopen(f'http://localhost:6333/collections/{n}'))['result']['status'] for n in names]
print('green' if names and all(s == 'green' for s in states) else ','.join(f'{n}={s}' for n, s in zip(names, states)))
" 2>/dev/null || echo unknown)
        [ "$status" = "green" ] && { echo "완료 (Qdrant 컬렉션 모두 green)"; return; }
        printf '.'; sleep 10
    done
    echo "Qdrant 인덱싱이 끝나지 않았습니다 ($status) — 측정을 신뢰할 수 없습니다" >&2; exit 1
}

# 샘플러 기록에서 회차의 최대값을 뽑는다: "backend postgres qdrant k6 (CPU%) 풀대기"
peaks() {
    python3 - "$1" <<'PY'
import csv, sys
rows = list(csv.DictReader(open(sys.argv[1])))
def mx(col):
    vals = [float(r[col]) for r in rows if r.get(col) not in (None, '')]
    return f"{max(vals):.0f}" if vals else "-"
print(mx('backend_cpu'), mx('postgres_cpu'), mx('qdrant_cpu'), mx('k6_cpu'), mx('hikari_pending'))
PY
}

