output "active" {
  value = var.active
}

output "site_url" {
  value = local.site_url
}

output "cloudfront_domain" {
  value = local.has_domain ? aws_cloudfront_distribution.main[0].domain_name : null
}

output "cloudfront_distribution_id" {
  description = "프론트 배포 워크플로의 캐시 무효화 대상"
  value       = local.has_domain ? aws_cloudfront_distribution.main[0].id : null
}

output "nat_public_ips" {
  description = "나가는 IP(고정). 상대가 IP 허용 목록을 요구하면 이 값을 준다"
  value       = aws_eip.nat[*].public_ip
}

output "ecr_repository_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "frontend_bucket" {
  value = aws_s3_bucket.frontend.id
}

output "images_bucket" {
  value = aws_s3_bucket.images.id
}

output "rds_endpoint" {
  value = aws_db_instance.main.address
}

output "rds_identifier" {
  description = "scripts/env-up.sh·env-down.sh가 시작·정지한다"
  value       = aws_db_instance.main.identifier
}

output "rds_master_secret_arn" {
  description = "마스터 비밀번호(Secrets Manager, RDS가 관리)"
  value       = aws_db_instance.main.master_user_secret[0].secret_arn
}

output "redis_endpoint" {
  value = var.active ? aws_elasticache_replication_group.main[0].primary_endpoint_address : null
}

output "qdrant_instance_id" {
  description = "SSM 접속·포트 포워딩 대상(데이터 이관)"
  value       = aws_instance.qdrant.id
}

output "qdrant_private_ip" {
  value = aws_instance.qdrant.private_ip
}

output "ecs_cluster" {
  value = aws_ecs_cluster.main.name
}

output "ecs_task_definition_family" {
  value = aws_ecs_task_definition.backend.family
}

output "github_backend_deploy_role_arn" {
  value = aws_iam_role.github_backend_deploy.arn
}

output "github_frontend_deploy_role_arn" {
  value = aws_iam_role.github_frontend_deploy.arn
}
