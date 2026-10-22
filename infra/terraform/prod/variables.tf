variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "project" {
  type    = string
  default = "crates"
}

variable "environment" {
  type    = string
  default = "prod"
}

# ── 환경 켜기 · 끄기 ─────────────────────────────────────────────
# 부하 테스트·시연 때만 켠다. false면 시간 요금이 드는 것을 내리고, 데이터가 있는 것은 남긴다.
#   내림: NAT 게이트웨이(EIP는 남김), ALB, ECS 서비스, ElastiCache
#   멈춤: Qdrant·Tailscale 중계 EC2(EBS는 남음)
#   남김: VPC, RDS(정지는 Terraform이 못 해서 scripts/env-down.sh가 한다), S3, CloudFront, ECR
# scripts/env-up.sh / env-down.sh가 이 값을 바꿔 apply한다. 손으로 바꿀 때는 env.auto.tfvars를 고친다.
variable "active" {
  type        = bool
  description = "true면 서비스가 돈다. false면 시간 요금이 드는 리소스를 내린다."
}

# ── 도메인 ───────────────────────────────────────────────────────
# Route 53에서 산 도메인. 비어 있으면 인증서·ALB·ECS 서비스·CloudFront·DNS를 만들지 않는다.
# 도메인 없이도 네트워크·데이터 계층을 먼저 올려 데이터를 옮겨 둘 수 있게 하려는 것이다.
variable "domain_name" {
  type    = string
  default = ""
}

# ── 네트워크 ─────────────────────────────────────────────────────
variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

# ALB와 RDS 서브넷 그룹이 가용 영역 2개를 요구한다.
variable "azs" {
  type    = list(string)
  default = ["ap-northeast-2a", "ap-northeast-2c"]
}

# ── 사양 (로컬 측정에서 잡은 시작값. 클라우드 부하 테스트로 조정한다) ──
variable "backend_cpu" {
  type    = number
  default = 2048 # 2 vCPU
}

variable "backend_memory" {
  type    = number
  default = 4096 # MiB
}

variable "backend_desired_count" {
  # OAuth 로그인 요청 상태가 서버 세션에 담기므로 늘리기 전에 ALB 고정 세션이나 상태 저장 위치를 바꿔야 한다.
  type    = number
  default = 1
}

variable "qdrant_instance_type" {
  type    = string
  default = "m7g.large"
}

variable "qdrant_volume_size" {
  type    = number
  default = 20 # GiB. 로컬 저장소 1.9GB + 스냅샷 복원용 여유
}

# build.gradle의 io.qdrant:client와 맞춘다. 어긋나면 backend가 기동을 멈춘다(QdrantConfig).
variable "qdrant_version" {
  type    = string
  default = "v1.14.1"
}

variable "rds_instance_class" {
  type    = string
  default = "db.t4g.small"
}

# 로컬과 같은 주 버전. 부하 측정에서 본 실행 계획을 그대로 기대한다.
variable "rds_engine_version" {
  type    = string
  default = "15"
}

variable "rds_allocated_storage" {
  type    = number
  default = 20
}

variable "redis_node_type" {
  type    = string
  default = "cache.t4g.micro"
}

# ── AI 서버 (위치 미정) ──────────────────────────────────────────
# 지금은 AI 개발자 노트북에 Tailscale로 붙는다. VPC 안의 중계 EC2가 Tailscale 망으로 넘겨 준다.
# AI 서버가 AWS로 오면 tailscale_relay_enabled = false로 두고 ai_server_base_url만 바꾼다.
variable "ai_server_base_url" {
  type    = string
  default = "http://100.77.133.40:8000"
}

variable "tailscale_relay_enabled" {
  type    = bool
  default = true
}

variable "tailscale_cidr" {
  type    = string
  default = "100.64.0.0/10" # Tailscale이 기기에 주는 주소 대역
}

variable "ai_server_port" {
  type    = number
  default = 8000
}

# ── 배포 자동화 (GitHub Actions OIDC) ────────────────────────────
variable "github_org" {
  type    = string
  default = "VibeCrates"
}

variable "github_backend_repo" {
  type    = string
  default = "VibeCrates_Server"
}

variable "github_frontend_repo" {
  type    = string
  default = "VibeCrates_Frontend"
}

# 배포를 허용할 브랜치. 다른 브랜치나 포크의 워크플로는 AWS 역할을 받을 수 없다.
variable "github_deploy_branch" {
  type    = string
  default = "main"
}
