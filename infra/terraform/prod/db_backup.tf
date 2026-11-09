# ── PostgreSQL 매일 논리 백업 ─────────────────────────────────────
#
# 백업은 두 겹이다.
#   1차  RDS 자동 백업(data_stores.tf): 매일 03:00 스냅샷(처음만 전체, 그다음은 바뀐 블록만) + 트랜잭션 로그로
#        7일 안의 아무 시점(5분 단위)으로 복원한다. 장애 대부분은 이것으로 되돌린다.
#   2차  여기: 매일 03:30 pg_dump로 DB 전체를 파일로 떠서 전용 버킷에 30일 둔다. RDS 밖에 있어 인스턴스를
#        지워도 남고, 다른 PostgreSQL로 옮기거나 테이블 하나만 되살릴 수 있다.
# 복원 방법: docs/db-backup.md
#
# 실행: EventBridge 스케줄러가 Fargate 작업(templates/db_backup.sh)을 띄운다. Lambda는 15분 제한이 있고
# pg_dump를 따로 넣어야 해서 쓰지 않는다. 덤프 296MB가 약 20초, 패키지 설치를 합쳐 1분 안팎이다.
#
# 환경을 끈 동안(active = false)은 스케줄러·경보를 만들지 않는다. 그동안 RDS가 멈춰 있어 바뀌는 데이터가 없고,
# NAT도 없어 작업이 패키지를 받지 못한다.

locals {
  db_backup_family = "${local.name}-db-backup"
}

# ── 버킷 ─────────────────────────────────────────────────────────
resource "aws_s3_bucket" "backups" {
  bucket = "${local.name}-backups-${data.aws_caller_identity.current.account_id}"

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_public_access_block" "backups" {
  bucket                  = aws_s3_bucket.backups.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 실수로 지우거나 덮어써도 7일 동안은 이전 버전이 남는다.
resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    id     = "expire-postgres-dumps"
    status = "Enabled"
    filter {
      prefix = "postgres/"
    }
    expiration {
      days = var.db_dump_retention_days
    }
    noncurrent_version_expiration {
      noncurrent_days = 7
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# 암호화되지 않은 연결로는 읽고 쓰지 못하게 한다.
resource "aws_s3_bucket_policy" "backups" {
  bucket = aws_s3_bucket.backups.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = [aws_s3_bucket.backups.arn, "${aws_s3_bucket.backups.arn}/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
  depends_on = [aws_s3_bucket_public_access_block.backups]
}

# ── 작업 ─────────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "db_backup" {
  name              = "/ecs/${local.db_backup_family}"
  retention_in_days = 30
}

# 덤프를 올리고 성공 지표를 남기는 것만 한다. 지우기는 수명 주기 규칙이 한다.
resource "aws_iam_role" "db_backup_task" {
  name               = "${local.db_backup_family}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "db_backup_task" {
  name = "write-dump"
  role = aws_iam_role.db_backup_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "PutDump"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${aws_s3_bucket.backups.arn}/postgres/*"
      },
      {
        # 지표 쓰기는 리소스 단위로 좁힐 수 없다. 이름 공간으로 좁힌다.
        Sid       = "SuccessMetric"
        Effect    = "Allow"
        Action    = ["cloudwatch:PutMetricData"]
        Resource  = "*"
        Condition = { StringEquals = { "cloudwatch:namespace" = "Crates/Backup" } }
      }
    ]
  })
}

resource "aws_ecs_task_definition" "db_backup" {
  family                   = local.db_backup_family
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  # 실행 역할은 backend와 같다(이미지 받기·로그 쓰기·RDS 비밀 읽기 권한이 이미 있다).
  execution_role_arn = aws_iam_role.ecs_execution.arn
  task_role_arn      = aws_iam_role.db_backup_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "db-backup"
    image     = "public.ecr.aws/amazonlinux/amazonlinux:2023"
    essential = true
    command   = ["bash", "-c", file("${path.module}/templates/db_backup.sh")]

    environment = [
      { name = "PGHOST", value = aws_db_instance.main.address },
      { name = "PGPORT", value = tostring(aws_db_instance.main.port) },
      { name = "PGDATABASE", value = aws_db_instance.main.db_name },
      { name = "PGUSER", value = aws_db_instance.main.username },
      { name = "PGSSLMODE", value = "require" }, # rds.force_ssl
      { name = "BACKUP_BUCKET", value = aws_s3_bucket.backups.id },
      { name = "DB_INSTANCE", value = aws_db_instance.main.identifier },
    ]
    # RDS가 돌리는 마스터 비밀번호를 작업이 뜰 때마다 새로 받는다.
    secrets = [{
      name      = "PGPASSWORD"
      valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::"
    }]

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.db_backup.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "db-backup"
      }
    }
  }])
}

resource "aws_security_group" "db_backup" {
  name        = "${local.name}-db-backup"
  description = "Daily pg_dump task: to RDS and HTTPS (packages, S3)"
  vpc_id      = aws_vpc.main.id
}

resource "aws_vpc_security_group_egress_rule" "db_backup_all" {
  security_group_id = aws_security_group.db_backup.id
  description       = "Outbound"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_db_backup" {
  security_group_id            = aws_security_group.rds.id
  description                  = "From daily pg_dump task"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.db_backup.id
}

# ── 스케줄 ───────────────────────────────────────────────────────
data "aws_iam_policy_document" "scheduler_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_iam_role" "db_backup_scheduler" {
  name               = "${local.db_backup_family}-scheduler"
  assume_role_policy = data.aws_iam_policy_document.scheduler_assume.json
}

resource "aws_iam_role_policy" "db_backup_scheduler" {
  name = "run-db-backup"
  role = aws_iam_role.db_backup_scheduler.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "RunTask"
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = "${aws_ecs_task_definition.db_backup.arn_without_revision}:*"
        Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.main.arn } }
      },
      {
        Sid      = "PassTaskRoles"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.db_backup_task.arn]
      }
    ]
  })
}

resource "aws_scheduler_schedule" "db_backup" {
  count = var.active ? 1 : 0

  name = local.db_backup_family
  # RDS 자동 백업 시간(03:00~03:30) 바로 뒤.
  schedule_expression          = "cron(30 3 * * ? *)"
  schedule_expression_timezone = "Asia/Seoul"

  flexible_time_window {
    mode = "OFF"
  }

  target {
    arn      = aws_ecs_cluster.main.arn
    role_arn = aws_iam_role.db_backup_scheduler.arn

    ecs_parameters {
      # 리비전 없이 가리켜 항상 마지막 리비전을 띄운다.
      task_definition_arn = aws_ecs_task_definition.db_backup.arn_without_revision
      launch_type         = "FARGATE"

      network_configuration {
        subnets          = aws_subnet.app[*].id
        security_groups  = [aws_security_group.db_backup.id]
        assign_public_ip = false
      }
    }

    # 작업을 띄우는 것 자체가 실패할 때(용량 부족 등) 다시 시도한다. 작업 안의 실패는 아래 알림이 잡는다.
    retry_policy {
      maximum_retry_attempts       = 2
      maximum_event_age_in_seconds = 3600
    }
  }
}

# ── 알림 ─────────────────────────────────────────────────────────
# 백업 작업이 0이 아닌 코드로 끝나면 바로, 성공 지표가 26시간 넘게 없으면(작업이 아예 안 돈 경우 포함) 메일을 보낸다.
resource "aws_sns_topic" "alerts" {
  name = "${local.name}-alerts"
}

resource "aws_sns_topic_subscription" "alerts_email" {
  count = var.alert_email != "" ? 1 : 0

  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "email" # 받는 사람이 확인 메일의 링크를 눌러야 시작된다
  endpoint  = var.alert_email
}

resource "aws_sns_topic_policy" "alerts" {
  arn = aws_sns_topic.alerts.arn
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "PublishFromEventsAndAlarms"
      Effect    = "Allow"
      Principal = { Service = ["events.amazonaws.com", "cloudwatch.amazonaws.com"] }
      Action    = "sns:Publish"
      Resource  = aws_sns_topic.alerts.arn
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
    }]
  })
}

resource "aws_cloudwatch_event_rule" "db_backup_failed" {
  count = var.active ? 1 : 0

  name        = "${local.db_backup_family}-failed"
  description = "Daily pg_dump task stopped with a non-zero exit code"
  event_pattern = jsonencode({
    source      = ["aws.ecs"]
    detail-type = ["ECS Task State Change"]
    detail = {
      clusterArn = [aws_ecs_cluster.main.arn]
      group      = ["family:${local.db_backup_family}"]
      lastStatus = ["STOPPED"]
      containers = { exitCode = [{ "anything-but" = 0 }] }
    }
  })
}

resource "aws_cloudwatch_event_target" "db_backup_failed" {
  count = var.active ? 1 : 0

  rule = aws_cloudwatch_event_rule.db_backup_failed[0].name
  arn  = aws_sns_topic.alerts.arn
  input_transformer {
    input_paths = {
      reason = "$.detail.stoppedReason"
      task   = "$.detail.taskArn"
    }
    input_template = "\"DB 백업 작업이 실패했습니다. 작업 <task>, 사유 <reason>. 로그: /ecs/${local.db_backup_family}\""
  }
}

# 1시간 단위로 26칸 연속 비어 있으면 울린다. 매일 한 번 성공하면 비는 칸은 최대 23개다.
resource "aws_cloudwatch_metric_alarm" "db_backup_missing" {
  count = var.active ? 1 : 0

  alarm_name          = "${local.db_backup_family}-missing"
  alarm_description   = "No successful pg_dump for 26 hours. Logs: /ecs/${local.db_backup_family}"
  namespace           = "Crates/Backup"
  metric_name         = "PostgresDumpSucceeded"
  dimensions          = { DBInstance = aws_db_instance.main.identifier }
  statistic           = "Sum"
  period              = 3600
  evaluation_periods  = 26
  datapoints_to_alarm = 26
  threshold           = 1
  comparison_operator = "LessThanThreshold"
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alerts.arn]
  ok_actions          = [aws_sns_topic.alerts.arn]
}
