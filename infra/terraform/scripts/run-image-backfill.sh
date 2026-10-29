#!/usr/bin/env bash
# 이미지 채우기 작업(ImageBackfillJob)을 운영에서 ECS 일회성 작업으로 실행한다.
#
#   infra/terraform/scripts/run-image-backfill.sh 종류=CSV경로 [종류=CSV경로 ...]
#   예) D=src/main/resources/data
#       .../run-image-backfill.sh book=$D/book.csv movie=$D/movie.csv music=$D/music.csv      # 셋 다
#       LIMIT=30 .../run-image-backfill.sh music=$D/music.csv                                 # 음악만 30건 시험
#       REQUEST_TIMEOUT=120s CONCURRENCY=10 .../run-image-backfill.sh music=$D/music.csv movie=$D/movie.csv
#
# 넘긴 종류만 채운다. 환경 변수(넘기지 않으면 앱 기본값, application.yaml)
#   LIMIT            종류별 상한(0 = 전부)
#   REQUEST_TIMEOUT  한 건을 본문까지 다 받는 최대 시간(예: 120s)
#   CONCURRENCY      동시 요청 수
#
# 하는 일
#   1. 원본 CSV를 운영 작업 버킷(jobs/, 30일 뒤 자동 삭제)에 올린다
#   2. backend 작업 정의(같은 이미지·비밀값·역할)를 웹 서버·주기 작업 없이 작업 모드로 한 번 실행한다
#   3. 끝날 때까지 진행 로그를 보여 주고 종료 코드를 확인한다(0 = 성공)
#   4. 원인별 실패 건수를 보여 주고 실패 목록 CSV를 내려받는다
#
# 다시 돌려도 된다. 이미지가 없는 콘텐츠만 대상이라 끊긴 곳부터 이어서 한다.
# 전제: RDS와 NAT가 켜져 있어야 한다. 작업이 RDS에 붙고 NAT로 원본 이미지를 받는다. ALB·ElastiCache·ECS 서비스는
#       필요 없어, 환경을 끈 상태에서 RDS를 시작하고 NAT만 켜도 된다(terraform apply -target=aws_nat_gateway.main -target=aws_route.app_nat).
set -euo pipefail

[ $# -gt 0 ] || { echo "종류=CSV경로 를 하나 이상 넘겨야 합니다(예: music=.../music.csv)" >&2; exit 1; }
TYPES=()
FILES=()
for arg in "$@"; do
  type=${arg%%=*}
  file=${arg#*=}
  case "$type" in
    book|movie|music) ;;
    *) echo "종류는 book, movie, music 중 하나입니다: $arg" >&2; exit 1 ;;
  esac
  [ -f "$file" ] || { echo "파일이 없습니다: $file" >&2; exit 1; }
  TYPES+=("$type")
  FILES+=("$file")
done
TYPES_CSV=$(IFS=,; echo "${TYPES[*]}")

cd "$(dirname "$0")/../prod"
OPS=$(terraform output -raw ops_bucket)
CLUSTER=$(terraform output -raw ecs_cluster)
FAMILY=$(terraform output -raw ecs_task_definition_family)
SG=$(terraform output -raw backend_security_group_id)
SUBNETS=$(terraform output -json app_subnet_ids | python3 -c "import json,sys;print(','.join(json.load(sys.stdin)))")
PREFIX="jobs/image-backfill/$(date +%Y%m%d-%H%M%S)"
cd - >/dev/null

echo "원본 CSV를 s3://$OPS/$PREFIX/ 에 올립니다..."
for i in "${!TYPES[@]}"; do
  aws s3 cp "${FILES[$i]}" "s3://$OPS/$PREFIX/${TYPES[$i]}.csv" --only-show-errors
done

# 웹 서버·주기 작업을 끄고 이 작업만 켠다(ImageBackfillJob 머리말). 이미지를 받아 올리는 작업이라
# 서비스 사양(2 vCPU / 4GB)까지는 필요 없어 작게 띄운다.
OVERRIDES=$(python3 - "$OPS" "$PREFIX" "$TYPES_CSV" "${LIMIT:-}" "${REQUEST_TIMEOUT:-}" "${CONCURRENCY:-}" <<'PY'
import json, sys
ops, prefix, types, limit, timeout, concurrency = sys.argv[1:]
env = {
    "JOB_IMAGE_BACKFILL_ENABLED": "true",
    "SPRING_MAIN_WEB_APPLICATION_TYPE": "none",
    "SCHEDULER_ENABLED": "false",
    # 환경을 끈 상태(active = false)의 작업 정의는 REDIS_HOST가 빈 값이라 앱이 기동하지 못한다('host' must not be empty).
    # 이 작업은 Redis를 쓰지 않고 Lettuce는 쓸 때 연결하므로, 비어 있지만 않으면 된다. 켜진 상태에서도 이 작업만 덮어쓴다.
    "REDIS_HOST": "localhost",
    "JOB_IMAGE_BACKFILL_TYPES": types,
    "JOB_IMAGE_BACKFILL_REPORT_LOCATION": f"s3://{ops}/{prefix}/failures.csv",
}
for t in types.split(","):
    env[f"JOB_IMAGE_BACKFILL_{t.upper()}"] = f"s3://{ops}/{prefix}/{t}.csv"
# 넘기지 않은 값은 앱 기본값을 쓴다
for name, value in [("JOB_IMAGE_BACKFILL_LIMIT_PER_TYPE", limit),
                    ("SEED_IMAGE_UPLOAD_REQUEST_TIMEOUT", timeout),
                    ("SEED_IMAGE_UPLOAD_CONCURRENCY", concurrency)]:
    if value:
        env[name] = value
print(json.dumps({
    "cpu": "1024", "memory": "2048",
    "containerOverrides": [{"name": "backend", "environment": [{"name": k, "value": v} for k, v in env.items()]}],
}))
PY
)

TASK_ARN=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$FAMILY" --launch-type FARGATE \
  --started-by image-backfill \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=DISABLED}" \
  --overrides "$OVERRIDES" --query 'tasks[0].taskArn' --output text)
TASK_ID=${TASK_ARN##*/}
LOG_GROUP="/ecs/$CLUSTER-backend"
echo "작업 시작: $TASK_ID (종류 $TYPES_CSV, 로그: $LOG_GROUP, 스트림 backend/backend/$TASK_ID)"

# AWS CLI의 tasks-stopped 대기는 10분이면 포기한다. 이 작업은 몇 시간 걸릴 수 있어 직접 기다린다.
while :; do
  STATUS=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].lastStatus' --output text)
  LAST=$(aws logs get-log-events --log-group-name "$LOG_GROUP" --log-stream-name "backend/backend/$TASK_ID" \
    --limit 50 --query 'events[].message' --output text 2>/dev/null | tr '\t' '\n' | grep "image-backfill" | tail -1 || true)
  echo "$(date +%H:%M:%S) $STATUS ${LAST:+| ${LAST##*: }}"
  [ "$STATUS" = "STOPPED" ] && break
  sleep 30
done

EXIT_CODE=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].containers[0].exitCode' --output text)
REASON=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].stoppedReason' --output text)
echo "끝: 종료 코드 $EXIT_CODE ($REASON)"
aws logs get-log-events --log-group-name "$LOG_GROUP" --log-stream-name "backend/backend/$TASK_ID" \
  --limit 300 --query 'events[].message' --output text | tr '\t' '\n' \
  | grep -E "\] (시작|끝|원본|실패 원인|실패 목록|http)" || true

if [ "$EXIT_CODE" = "0" ]; then
  # 저장소 안에 떨어뜨리면 실수로 커밋될 수 있어 임시 디렉터리에 받는다
  REPORT="${TMPDIR:-/tmp}/image-backfill-failures-${PREFIX##*/}.csv"
  aws s3 cp "s3://$OPS/$PREFIX/failures.csv" "$REPORT" --only-show-errors && echo "실패 목록: $REPORT"
fi
[ "$EXIT_CODE" = "0" ]
