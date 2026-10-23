# 도메인 유무 × 환경 켜기/끄기 네 조합에서 리소스가 의도대로 생기고 사라지는지 계획(plan)으로 확인한다.
# AWS에 붙지 않는다(mock 공급자). 실행: terraform init -backend=false && terraform test

mock_provider "aws" {
  override_during = plan

  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { value = "ami-0123456789abcdef0", insecure_value = "ami-0123456789abcdef0" }
  }
  mock_data "aws_iam_policy_document" {
    defaults = { json = "{\"Version\":\"2012-10-17\",\"Statement\":[]}" }
  }
  mock_data "aws_route53_zone" {
    defaults = { zone_id = "Z0123456789" }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      address  = "crates-prod.abc.ap-northeast-2.rds.amazonaws.com"
      port     = 5432
      db_name  = "crates"
      username = "crates_admin"
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:ap-northeast-2:123456789012:secret:rds-abc"
        kms_key_id    = ""
        secret_status = "active"
      }]
    }
  }
  mock_resource "aws_acm_certificate" {
    defaults = {
      arn = "arn:aws:acm:ap-northeast-2:123456789012:certificate/abc"
      domain_validation_options = [
        { domain_name = "crates.example", resource_record_name = "_a.crates.example.", resource_record_type = "CNAME", resource_record_value = "_b.acm-validations.aws." },
        { domain_name = "*.crates.example", resource_record_name = "_a.crates.example.", resource_record_type = "CNAME", resource_record_value = "_b.acm-validations.aws." },
      ]
    }
  }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }
  mock_resource "aws_cloudfront_distribution" {
    defaults = { arn = "arn:aws:cloudfront::123456789012:distribution/E123", domain_name = "d123.cloudfront.net", hosted_zone_id = "Z2FDTNDATAQYW2" }
  }
}

mock_provider "aws" {
  alias           = "us_east_1"
  override_during = plan

  mock_resource "aws_acm_certificate" {
    defaults = { arn = "arn:aws:acm:us-east-1:123456789012:certificate/def" }
  }
}

mock_provider "random" {}

run "no_domain_active" {
  command = plan
  variables {
    active      = true
    domain_name = ""
  }

  assert {
    condition     = length(aws_nat_gateway.main) == 2 && length(aws_route.app_nat) == 2
    error_message = "켜면 AZ마다 NAT와 기본 경로가 있어야 한다"
  }
  assert {
    condition     = length(aws_elasticache_replication_group.main) == 1
    error_message = "켜면 ElastiCache가 있어야 한다"
  }
  assert {
    condition     = aws_ec2_instance_state.qdrant.state == "running"
    error_message = "켜면 Qdrant가 돌아야 한다"
  }
  assert {
    condition     = length(aws_cloudfront_distribution.main) == 0 && length(aws_lb.main) == 0 && length(aws_ecs_service.backend) == 0
    error_message = "도메인이 없으면 CloudFront·ALB·ECS 서비스를 만들지 않는다"
  }
  assert {
    condition     = length(aws_route.app_tailscale) == 2
    error_message = "Tailscale 중계가 켜져 있으면 AZ마다 Tailscale 경로가 있어야 한다"
  }
}

run "no_domain_inactive" {
  command = plan
  variables {
    active      = false
    domain_name = ""
  }

  assert {
    condition     = length(aws_nat_gateway.main) == 0 && length(aws_route.app_nat) == 0
    error_message = "끄면 NAT와 기본 경로가 없어야 한다"
  }
  assert {
    condition     = length(aws_eip.nat) == 2
    error_message = "끄도 EIP는 남아야 한다(나가는 IP 고정)"
  }
  assert {
    condition     = length(aws_elasticache_replication_group.main) == 0
    error_message = "끄면 ElastiCache가 없어야 한다"
  }
  assert {
    condition     = aws_ec2_instance_state.qdrant.state == "stopped" && aws_ec2_instance_state.tailscale_relay[0].state == "stopped"
    error_message = "끄면 EC2가 멈춰야 한다"
  }
}

run "domain_active" {
  command = plan
  variables {
    active      = true
    domain_name = "crates.example"
  }

  assert {
    condition     = length(aws_cloudfront_distribution.main) == 1 && length(aws_lb.main) == 1 && length(aws_ecs_service.backend) == 1
    error_message = "도메인이 있고 켜져 있으면 CloudFront·ALB·ECS 서비스가 있어야 한다"
  }
  assert {
    condition     = length(aws_route53_record.origin) == 1 && length(aws_route53_record.site) == 2
    error_message = "origin 레코드와 사이트 A·AAAA 레코드가 있어야 한다"
  }
  assert {
    condition     = length(aws_route53_record.cert_validation) == 2
    error_message = "인증서 검증 레코드(원래 도메인·와일드카드)가 있어야 한다"
  }
  assert {
    condition     = length(aws_s3_bucket_policy.cloudfront_read) == 2
    error_message = "두 버킷 모두 CloudFront만 읽게 하는 정책이 있어야 한다"
  }
  assert {
    condition     = length(aws_cloudfront_distribution.main[0].ordered_cache_behavior) == 4
    error_message = "API 3경로 + 이미지 1경로 동작이 있어야 한다"
  }
  assert {
    condition = (
      one(one(aws_cloudfront_distribution.main[0].restrictions).geo_restriction).restriction_type == "whitelist" &&
      one(one(aws_cloudfront_distribution.main[0].restrictions).geo_restriction).locations == toset(["KR"])
    )
    error_message = "기본값은 한국만 허용해야 한다"
  }
}

run "no_geo_restriction" {
  command = plan
  variables {
    active            = true
    domain_name       = "crates.example"
    allowed_countries = []
  }

  assert {
    condition     = one(one(aws_cloudfront_distribution.main[0].restrictions).geo_restriction).restriction_type == "none"
    error_message = "허용 국가를 비우면 제한하지 않아야 한다"
  }
}

run "domain_inactive" {
  command = plan
  variables {
    active      = false
    domain_name = "crates.example"
  }

  assert {
    condition     = length(aws_cloudfront_distribution.main) == 1
    error_message = "끄도 CloudFront는 남는다(설정을 다시 하지 않으려고)"
  }
  assert {
    condition     = length(aws_lb.main) == 0 && length(aws_ecs_service.backend) == 0 && length(aws_route53_record.origin) == 0
    error_message = "끄면 ALB·ECS 서비스·origin 레코드가 없어야 한다"
  }
}

run "no_tailscale" {
  command = plan
  variables {
    active                  = true
    domain_name             = ""
    tailscale_relay_enabled = false
  }

  assert {
    condition     = length(aws_instance.tailscale_relay) == 0 && length(aws_route.app_tailscale) == 0
    error_message = "AI 서버가 AWS로 오면 중계 EC2와 경로를 만들지 않는다"
  }
}
