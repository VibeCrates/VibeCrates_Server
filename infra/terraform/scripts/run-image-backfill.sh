#!/usr/bin/env bash
# 이미지 채우기 작업(ImageBackfillJob)을 운영에서 ECS 일회성 작업으로 실행한다.
#
#   infra/terraform/scripts/run-image-backfill.sh <book.csv> <movie.csv> <music.csv> [종류별 상한]
#   예) .../run-image-backfill.sh src/main/resources/data/{book,movie,music}.csv        # 전부
#       .../run-image-backfill.sh src/main/resources/data/{book,movie,music}.csv 30     # 시험 삼아 30건씩
#
# 하는 일
#   1. 원본 CSV를 운영 작업 버킷(jobs/, 30일 뒤 자동 삭제)에 올린다
#   2. backend 작업 정의(같은 이미지·비밀값·역할)를 웹 서버·주기 작업 없이 작업 모드로 한 번 실행한다
#   3. 끝날 때까지 진행 로그를 보여 주고 종료 코드를 확인한다(0 = 성공)
#
# 다시 돌려도 된다. 이미지가 없는 콘텐츠만 대상이라 끊긴 곳부터 이어서 한다.
# 전제: 환경이 켜져 있어야 한다(env-up.sh). 작업이 RDS에 붙고 NAT로 원본 이미지를 받는다.
set -euo pipefail

BOOK=${1:?book.csv 경로가 필요합니다}
MOVIE=${2:?movie.csv 경로가 필요합니다}
MUSIC=${3:?music.csv 경로가 필요합니다}
LIMIT=${4:-0}
for f in "$BOOK" "$MOVIE" "$MUSIC"; do [ -f "$f" ] || { echo "파일이 없습니다: $f" >&2; exit 1; }; done

cd "$(dirname "$0")/../prod"
OPS=$(terraform output -raw ops_bucket)
CLUSTER=$(terraform output -raw ecs_cluster)
FAMILY=$(terraform output -raw ecs_task_definition_family)
SG=$(terraform output -raw backend_security_group_id)
SUBNETS=$(terraform output -json app_subnet_ids | python3 -c "import json,sys;print(','.join(json.load(sys.stdin)))")
PREFIX="jobs/image-backfill/$(date +%Y%m%d-%H%M%S)"
cd - >/dev/null

echo "원본 CSV를 s3://$OPS/$PREFIX/ 에 올립니다..."
aws s3 cp "$BOOK"  "s3://$OPS/$PREFIX/book.csv"  --only-show-errors
aws s3 cp "$MOVIE" "s3://$OPS/$PREFIX/movie.csv" --only-show-errors
aws s3 cp "$MUSIC" "s3://$OPS/$PREFIX/music.csv" --only-show-errors

# 웹 서버·주기 작업을 끄고 이 작업만 켠다(ImageBackfillJob 머리말). 이미지를 받아 올리는 작업이라
# 서비스 사양(2 vCPU / 4GB)까지는 필요 없어 작게 띄운다.
OVERRIDES=$(python3 - "$OPS" "$PREFIX" "$LIMIT" <<'PY'
import json, sys
ops, prefix, limit = sys.argv[1:]
env = {
    "JOB_IMAGE_BACKFILL_ENABLED": "true",
    "SPRING_MAIN_WEB_APPLICATION_TYPE": "none",
    "SCHEDULER_ENABLED": "false",
    "JOB_IMAGE_BACKFILL_BOOK": f"s3://{ops}/{prefix}/book.csv",
    "JOB_IMAGE_BACKFILL_MOVIE": f"s3://{ops}/{prefix}/movie.csv",
    "JOB_IMAGE_BACKFILL_MUSIC": f"s3://{ops}/{prefix}/music.csv",
    "JOB_IMAGE_BACKFILL_LIMIT_PER_TYPE": limit,
}
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
echo "작업 시작: $TASK_ID (로그: $LOG_GROUP, 스트림 backend/backend/$TASK_ID)"

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
  --limit 200 --query 'events[].message' --output text | tr '\t' '\n' | grep -E "\] (시작|끝|원본)" || true
[ "$EXIT_CODE" = "0" ]
