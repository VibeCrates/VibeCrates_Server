# ── 사용자 행동 로그 내보내기 (docs/board-schema.md 13-7) ─────────────────────
# backend가 매일 새벽 3시(KST) 전날 하루치를 ops 버킷의 event-logs/dt=YYYY-MM-DD/events.jsonl.gz로 올린다
# (UserEventLogExporter). 운영 DB에는 사용자만 접근할 수 있어, AI 개발자는 DB가 아니라 이 파일을 받아 간다.
#
# 권한은 경로(event-logs/)로 자른다. 같은 버킷의 jobs/(이미지 채우기 입력 CSV, 실패 목록)는 양쪽 모두 건드리지 못한다.

locals {
  event_log_prefix = "event-logs"
}

# backend: 쓰기와 "이미 올린 날인지" 확인만. 읽기·지우기는 주지 않는다 — 올린 파일은 고치지 않는다.
resource "aws_iam_role_policy" "ecs_task_event_logs" {
  name = "event-logs-export"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${aws_s3_bucket.ops.arn}/${local.event_log_prefix}/*"
      },
      {
        # 날짜 파일이 있는지 목록으로 확인한다. HeadObject로 보려면 GetObject까지 필요해 목록 조회를 쓴다.
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = aws_s3_bucket.ops.arn
        Condition = { StringLike = { "s3:prefix" = ["${local.event_log_prefix}/*"] } }
      }
    ]
  })
}

# AI 개발자: event-logs/ 읽기만. 콘솔 비밀번호를 만들지 않고, 다른 서비스·버킷·경로 권한이 없다.
# 계정의 결제·연락처 정보, 다른 IAM 사용자, RDS·ECS·SSM·Secrets Manager는 볼 수 없다.
#
# 액세스 키는 여기서 만들지 않는다. aws_iam_access_key로 만들면 비밀 키가 Terraform state에 평문으로 남는다.
# 사용자가 직접 발급해 전달한다:
#   aws iam create-access-key --user-name <아래 이름>
# 그만 줄 때는 키를 비활성화(update-access-key --status Inactive)하거나 지운다. 그 즉시 접근이 막힌다.
resource "aws_iam_user" "ai_log_reader" {
  name = "${local.name}-ai-log-reader"
  tags = { Purpose = "AI 개발자 행동 로그 읽기 전용 event-logs/" }
}

resource "aws_iam_user_policy" "ai_log_reader" {
  name = "read-event-logs"
  user = aws_iam_user.ai_log_reader.name
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # aws s3 ls / sync s3://<버킷>/event-logs/ 가 쓰는 목록 조회. event-logs/ 아래만 보인다.
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = aws_s3_bucket.ops.arn
        Condition = { StringLike = { "s3:prefix" = ["${local.event_log_prefix}/", "${local.event_log_prefix}/*"] } }
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${aws_s3_bucket.ops.arn}/${local.event_log_prefix}/*"
      }
    ]
  })
}

output "event_log_location" {
  description = "AI 개발자에게 알려 줄 로그 위치"
  value       = "s3://${aws_s3_bucket.ops.id}/${local.event_log_prefix}/"
}

output "ai_log_reader_user" {
  description = "AI 개발자용 IAM 사용자 이름(액세스 키는 사용자가 직접 발급한다)"
  value       = aws_iam_user.ai_log_reader.name
}
