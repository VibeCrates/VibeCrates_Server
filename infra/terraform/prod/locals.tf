data "aws_caller_identity" "current" {}

locals {
  name = "${var.project}-${var.environment}"

  tags = {
    Project     = var.project
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  has_domain = var.domain_name != ""

  # 도메인이 있고 환경이 켜져 있을 때만 사용자 트래픽을 받는 리소스(ALB, ECS 서비스)를 만든다.
  serving = local.has_domain && var.active

  site_url = local.has_domain ? "https://${var.domain_name}" : ""

  # CloudFront가 ALB에 붙는 주소. 사용자는 이 주소를 쓰지 않는다.
  origin_domain = "origin.${var.domain_name}"

  # 비밀값은 Terraform이 만들지 않는다. 값이 상태 파일에 평문으로 남기 때문이다.
  # README의 순서대로 사람이 SSM Parameter Store(SecureString)에 넣고, 여기서는 이름만 가리킨다.
  ssm_prefix = "/${var.project}/${var.environment}"

  # 작업 정의의 secrets: 환경 변수 이름 → SSM 파라미터 이름
  backend_ssm_secrets = {
    JWT_SECRET_KEY       = "${local.ssm_prefix}/jwt-secret-key"
    GOOGLE_CLIENT_ID     = "${local.ssm_prefix}/oauth/google-client-id"
    GOOGLE_CLIENT_SECRET = "${local.ssm_prefix}/oauth/google-client-secret"
    KAKAO_CLIENT_ID      = "${local.ssm_prefix}/oauth/kakao-client-id"
    KAKAO_CLIENT_SECRET  = "${local.ssm_prefix}/oauth/kakao-client-secret"
    NAVER_CLIENT_ID      = "${local.ssm_prefix}/oauth/naver-client-id"
    NAVER_CLIENT_SECRET  = "${local.ssm_prefix}/oauth/naver-client-secret"
    QDRANT_API_KEY       = "${local.ssm_prefix}/qdrant-api-key"
  }

  qdrant_api_key_param     = "${local.ssm_prefix}/qdrant-api-key"
  tailscale_auth_key_param = "${local.ssm_prefix}/tailscale-auth-key"

  ssm_param_arn_prefix = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter"
}
