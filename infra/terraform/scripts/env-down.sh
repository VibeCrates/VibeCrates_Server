#!/usr/bin/env bash
# 환경을 끈다(시간 요금이 드는 것을 내린다). 켤 때의 반대 순서다.
#   1. active = false로 apply — ECS 서비스·ALB·ElastiCache·NAT 삭제, EC2 정지(EBS·EIP는 남는다)
#   2. RDS 정지
#
# 주의: AWS는 정지한 RDS를 7일 뒤 자동으로 다시 켠다. 더 오래 끌 때는 7일마다 이 스크립트를 다시 돌리거나,
# 스냅샷을 뜬 뒤 인스턴스를 지운다.
set -euo pipefail
cd "$(dirname "$0")/../prod"

echo 'active = false' > env.auto.tfvars
terraform apply

RDS_ID=$(terraform output -raw rds_identifier)
STATUS=$(aws rds describe-db-instances --db-instance-identifier "$RDS_ID" \
  --query 'DBInstances[0].DBInstanceStatus' --output text)
echo "RDS($RDS_ID): $STATUS"
if [ "$STATUS" = "available" ]; then
  aws rds stop-db-instance --db-instance-identifier "$RDS_ID" >/dev/null
  echo "RDS 정지를 요청했습니다(7일 뒤 자동 재시작)."
fi
