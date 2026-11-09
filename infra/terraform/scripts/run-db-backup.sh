#!/usr/bin/env bash
# 매일 03:30에 도는 DB 덤프(db_backup.tf)를 지금 한 번 돌린다. 위험한 마이그레이션·데이터 수정 직전에 쓴다.
#   infra/terraform/scripts/run-db-backup.sh
#
# 끝날 때까지 기다려 종료 코드와 올린 파일(s3://…/postgres/YYYY/MM/DD/…dump)을 보여 준다.
# 전제: 환경이 켜져 있어야 한다(RDS available, NAT 있음).
set -euo pipefail

cd "$(dirname "$0")/../prod"
CLUSTER=$(terraform output -raw ecs_cluster)
FAMILY=$(terraform output -raw db_backup_task_family)
SG=$(terraform output -raw db_backup_security_group_id)
SUBNETS=$(terraform output -json app_subnet_ids | python3 -c "import json,sys;print(','.join(json.load(sys.stdin)))")
cd - >/dev/null

TASK_ARN=$(aws ecs run-task --cluster "$CLUSTER" --task-definition "$FAMILY" --launch-type FARGATE \
  --started-by manual-db-backup \
  --network-configuration "awsvpcConfiguration={subnets=[$SUBNETS],securityGroups=[$SG],assignPublicIp=DISABLED}" \
  --query 'tasks[0].taskArn' --output text)
TASK_ID=${TASK_ARN##*/}
LOG_GROUP="/ecs/$FAMILY"
STREAM="db-backup/db-backup/$TASK_ID"
echo "작업 시작: $TASK_ID (로그: $LOG_GROUP)"

aws ecs wait tasks-stopped --cluster "$CLUSTER" --tasks "$TASK_ARN"
EXIT_CODE=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].containers[0].exitCode' --output text)
REASON=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks "$TASK_ARN" --query 'tasks[0].stoppedReason' --output text)
aws logs get-log-events --log-group-name "$LOG_GROUP" --log-stream-name "$STREAM" \
  --query 'events[].message' --output text 2>/dev/null | tr '\t' '\n' | grep '\[db-backup\]' || true
echo "끝: 종료 코드 $EXIT_CODE ($REASON)"
[ "$EXIT_CODE" = "0" ]
