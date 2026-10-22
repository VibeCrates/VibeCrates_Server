# ── PostgreSQL (RDS) ─────────────────────────────────────────────
# 원본 데이터라 백업·시점 복구·패치를 RDS에 맡긴다. data 서브넷(인터넷 경로 없음)에 둔다.
#
# 마스터 비밀번호는 RDS가 Secrets Manager에 만들고 돌린다(manage_master_user_password). Terraform이
# 비밀번호를 만들면 상태 파일에 평문으로 남는다. backend는 작업 정의의 secrets로 그 비밀을 받는다.

resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_db_parameter_group" "postgres" {
  name   = "${local.name}-postgres${var.rds_engine_version}"
  family = "postgres${var.rds_engine_version}"

  # 부하 측정과 같은 지표를 운영에서도 본다(쿼리별 호출 수·시간·블록).
  parameter {
    name         = "shared_preload_libraries"
    value        = "pg_stat_statements"
    apply_method = "pending-reboot"
  }

  # 평문 연결을 거절한다. backend는 sslmode=require로 붙는다.
  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  # 1초 넘는 쿼리를 로그에 남긴다(CloudWatch Logs로 내보냄).
  parameter {
    name  = "log_min_duration_statement"
    value = "1000"
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_db_instance" "main" {
  identifier = local.name

  engine                     = "postgres"
  engine_version             = var.rds_engine_version
  auto_minor_version_upgrade = true
  instance_class             = var.rds_instance_class
  parameter_group_name       = aws_db_parameter_group.postgres.name

  db_name                     = "crates"
  username                    = "crates_admin"
  manage_master_user_password = true

  allocated_storage     = var.rds_allocated_storage
  max_allocated_storage = 50 # 차면 자동으로 늘린다(상한)
  storage_type          = "gp3"
  storage_encrypted     = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]
  publicly_accessible    = false
  multi_az               = false # 비용 때문에 단일 AZ. 운영이라면 켠다

  backup_retention_period = 7
  backup_window           = "18:00-18:30" # UTC = 한국 새벽 3시
  maintenance_window      = "sun:19:00-sun:19:30"
  copy_tags_to_snapshot   = true

  enabled_cloudwatch_logs_exports = ["postgresql"]

  # 실수로 지우지 못하게 막고, 지울 때는 마지막 스냅샷을 남긴다.
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${local.name}-final"

  # 파라미터 그룹 변경(shared_preload_libraries)은 재부팅해야 적용된다. 정비 시간에 맞추지 않고 바로 반영한다.
  apply_immediately = true
}

# ── Redis (ElastiCache) ──────────────────────────────────────────
# 추천 캐시·검색어 임베딩 캐시·OAuth 임시 토큰만 담는다. 모두 다시 만들 수 있는 데이터라
# 환경을 끌 때 지우고 켤 때 새로 만든다(정지 기능이 없다).

resource "aws_elasticache_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.data[*].id
}

resource "aws_elasticache_parameter_group" "redis" {
  name   = "${local.name}-redis7"
  family = "redis7"

  # 로컬 docker-compose와 같은 이유: 차면 TTL이 있는 키 중 오래 안 쓴 것부터 지운다.
  # 곧 쓰이고 사라지는 로그인 토큰보다 오래된 캐시가 먼저 밀려난다.
  parameter {
    name  = "maxmemory-policy"
    value = "volatile-lru"
  }
}

resource "aws_elasticache_replication_group" "main" {
  count = var.active ? 1 : 0

  replication_group_id = local.name
  description          = "crates cache (recommendation, embedding, oauth temp token)"

  engine               = "redis"
  engine_version       = "7.1"
  node_type            = var.redis_node_type
  num_cache_clusters   = 1
  parameter_group_name = aws_elasticache_parameter_group.redis.name
  port                 = 6379

  subnet_group_name  = aws_elasticache_subnet_group.main.name
  security_group_ids = [aws_security_group.redis.id]

  # backend는 TLS로 붙는다(application-prod.yaml의 REDIS_SSL_ENABLED 기본 true).
  transit_encryption_enabled = true
  at_rest_encryption_enabled = true

  apply_immediately = true
}
