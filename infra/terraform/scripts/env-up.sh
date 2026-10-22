#!/usr/bin/env bash
# 환경을 켠다(부하 테스트·시연 전). 순서가 중요하다.
#   1. RDS 시작(몇 분 걸린다) — Terraform은 RDS를 시작·정지하지 못한다
#   2. active = true로 apply — NAT → ElastiCache·EC2 시작 → ALB → ECS 서비스
# NAT가 먼저 있어야 backend가 ECR에서 이미지를 받고 카카오/네이버에 닿는다. 같은 apply 안에서
# Terraform이 의존 관계대로 만든다(ECS 서비스는 라우팅·리스너 뒤).
set -euo pipefail
cd "$(dirname "$0")/../prod"

RDS_ID=$(terraform output -raw rds_identifier)
STATUS=$(aws rds describe-db-instances --db-instance-identifier "$RDS_ID" \
  --query 'DBInstances[0].DBInstanceStatus' --output text)
echo "RDS($RDS_ID): $STATUS"
if [ "$STATUS" = "stopped" ]; then
  aws rds start-db-instance --db-instance-identifier "$RDS_ID" >/dev/null
fi
echo "RDS가 사용 가능해질 때까지 기다립니다..."
aws rds wait db-instance-available --db-instance-identifier "$RDS_ID"

echo 'active = true' > env.auto.tfvars
terraform apply
