# ── backend: ECS Fargate (ARM64) ─────────────────────────────────
#
# 누가 무엇을 바꾸나
#   Terraform  작업 정의의 틀(환경 변수·비밀·CPU·메모리·역할)
#   CI         이미지. 커밋 SHA 태그로 ECR에 올리고, 작업 정의의 이미지만 바꾼 새 리비전으로 서비스를 갱신한 뒤
#              그 태그를 SSM 파라미터(backend-image-tag)에 기록한다
#
# 서비스는 작업 정의 변경을 무시한다(ignore_changes). 무시하지 않으면 다음 apply가 CI가 배포한 리비전을
# 되돌린다. 대신 Terraform도 작업 정의를 만들 때 SSM에 기록된 태그를 쓴다. 그래서 환경을 껐다 켜서
# 서비스가 새로 만들어져도 마지막으로 배포한 이미지로 뜬다.

# 처음 한 번만 만들고 값은 CI가 바꾼다(ignore_changes). Terraform은 apply 때마다 실제 값을 다시 읽어 쓴다.
# 첫 이미지를 올리기 전 값은 "unset"이라, 이 상태로 서비스를 띄우면 이미지를 못 찾아 실패한다(README 순서 참고).
resource "aws_ssm_parameter" "backend_image_tag" {
  name           = "${local.ssm_prefix}/backend-image-tag"
  type           = "String"
  insecure_value = "unset"

  lifecycle {
    ignore_changes = [insecure_value]
  }
}

resource "aws_ecs_cluster" "main" {
  name = local.name
}

resource "aws_cloudwatch_log_group" "backend" {
  name              = "/ecs/${local.name}-backend"
  retention_in_days = 14
}

# ── 역할 ─────────────────────────────────────────────────────────
data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# 실행 역할: ECS가 컨테이너를 띄울 때 쓴다(이미지 받기, 로그 쓰기, 비밀값 주입).
resource "aws_iam_role" "ecs_execution" {
  name               = "${local.name}-ecs-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution_managed" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role_policy" "ecs_execution_secrets" {
  name = "read-backend-secrets"
  role = aws_iam_role.ecs_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "SsmParameters"
        Effect   = "Allow"
        Action   = ["ssm:GetParameters"]
        Resource = [for name in values(local.backend_ssm_secrets) : "${local.ssm_param_arn_prefix}${name}"]
      },
      {
        Sid      = "RdsMasterSecret"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = aws_db_instance.main.master_user_secret[0].secret_arn
      }
    ]
  })
}

# 작업 역할: 애플리케이션이 AWS를 부를 때 쓴다. 이미지 버킷 읽기·쓰기만 준다.
resource "aws_iam_role" "ecs_task" {
  name               = "${local.name}-backend-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

# ECS Exec — 실행 중인 backend 컨테이너 안에서 명령을 실행한다(aws ecs execute-command). 부하 측정 때 앱 지표
# (/actuator — CloudFront 경로에 없어 밖에서 못 읽는다)를 컨테이너 안에서 읽어 캐시 적중률을 남기려고 켰다.
# SSM 세션 채널이라 포트를 열지 않는다. 실행 기록은 CloudTrail에 남는다.
resource "aws_iam_role_policy" "ecs_task_exec" {
  name = "ecs-exec"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ssmmessages:CreateControlChannel",
        "ssmmessages:CreateDataChannel",
        "ssmmessages:OpenControlChannel",
        "ssmmessages:OpenDataChannel",
      ]
      Resource = "*"
    }]
  })
}

resource "aws_iam_role_policy" "ecs_task_images" {
  name = "images-bucket"
  role = aws_iam_role.ecs_task.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.images.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"] # HeadBucket과 "없는 키" 판별에 필요
        Resource = aws_s3_bucket.images.arn
      },
      {
        # 일회성 작업(같은 작업 정의로 실행)의 입력 읽기와 결과(실패 목록) 쓰기. 서비스로 도는 backend는 쓰지 않는다.
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.ops.arn}/jobs/*"
      }
    ]
  })
}

# ── 작업 정의 ────────────────────────────────────────────────────
resource "aws_ecs_task_definition" "backend" {
  family                   = "${local.name}-backend"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.backend_cpu
  memory                   = var.backend_memory
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  # 맥(M4)에서 빌드한 이미지와 같은 ARM. Graviton이라 같은 성능에 x86보다 싸다.
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }

  container_definitions = jsonencode([{
    name      = "backend"
    image     = "${aws_ecr_repository.backend.repository_url}:${aws_ssm_parameter.backend_image_tag.insecure_value}"
    essential = true

    portMappings = [{ containerPort = 8080, protocol = "tcp" }]

    # 주소는 평문 환경 변수, 비밀값은 secrets(SSM·Secrets Manager에서 주입)로 나눈다.
    environment = [
      { name = "SPRING_PROFILES_ACTIVE", value = "prod" },
      { name = "SITE_URL", value = local.site_url },
      { name = "DB_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:${aws_db_instance.main.port}/${aws_db_instance.main.db_name}?sslmode=require" },
      { name = "POSTGRESQL_USERNAME", value = aws_db_instance.main.username },
      { name = "REDIS_HOST", value = var.active ? aws_elasticache_replication_group.main[0].primary_endpoint_address : "" },
      { name = "QDRANT_HOST", value = aws_instance.qdrant.private_ip },
      { name = "STORAGE_BUCKET_NAME", value = aws_s3_bucket.images.id },
      { name = "AI_SERVER_BASE_URL", value = var.ai_server_base_url },
    ]

    secrets = concat(
      [for env_name, param in local.backend_ssm_secrets : {
        name      = env_name
        valueFrom = "${local.ssm_param_arn_prefix}${param}"
      }],
      [{
        # RDS가 관리하는 비밀은 JSON({"username","password"})이다. password 키만 꺼낸다.
        name      = "POSTGRESQL_PASSWORD"
        valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::"
      }]
    )

    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.backend.name
        awslogs-region        = var.region
        awslogs-stream-prefix = "backend"
      }
    }

    # SIGTERM 뒤 처리 중인 요청을 끝낼 시간(Spring graceful shutdown 기본 30초).
    stopTimeout = 40
  }])
}

# ── 서비스 ───────────────────────────────────────────────────────
resource "aws_ecs_service" "backend" {
  count = local.serving ? 1 : 0

  name            = "backend"
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.backend.arn
  desired_count   = var.backend_desired_count
  launch_type     = "FARGATE"

  # 컨테이너 안에서 앱 지표를 읽는다(위 ecs_task_exec). 켠 뒤 새로 뜬 작업부터 적용된다.
  enable_execute_command = true

  network_configuration {
    subnets          = aws_subnet.app[*].id
    security_groups  = [aws_security_group.backend.id]
    assign_public_ip = false # NAT로만 나간다
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.backend[0].arn
    container_name   = "backend"
    container_port   = 8080
  }

  # 새 작업이 정상이 된 뒤 옛 작업을 내린다(무중단). 배포가 실패하면 이전 버전으로 되돌린다.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  # Flyway 검증·컨텍스트 기동 동안 헬스 체크 실패로 내려가지 않게 기다린다.
  health_check_grace_period_seconds = 120

  lifecycle {
    ignore_changes = [task_definition] # CI가 이미지를 바꿔 배포한다
  }

  depends_on = [aws_lb_listener.https]
}
