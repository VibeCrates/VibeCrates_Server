# 보안 그룹. "누가 누구에게"를 그룹끼리 참조해 적는다(IP 대역이 아니라). 태스크·인스턴스가 바뀌어도
# 규칙을 고칠 필요가 없고, 같은 VPC 안이라도 허용한 그룹 말고는 닿지 않는다.
#
#   CloudFront ─443→ ALB ─8080→ backend ─┬─5432→ RDS
#                                        ├─6379→ ElastiCache
#                                        ├─6334→ Qdrant
#                                        └─8000→ Tailscale 중계 → AI 서버
#   Qdrant ─5432→ RDS   (데이터 이관 때 SSM 포트 포워딩의 중계로만 쓴다)

# CloudFront가 오리진에 접속할 때 쓰는 IP 대역. ALB는 여기서 오는 443만 받는다.
data "aws_ec2_managed_prefix_list" "cloudfront_origin" {
  name = "com.amazonaws.global.cloudfront.origin-facing"
}

resource "aws_security_group" "alb" {
  name        = "${local.name}-alb"
  description = "ALB: HTTPS from CloudFront only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-alb" }
}

resource "aws_vpc_security_group_ingress_rule" "alb_from_cloudfront" {
  security_group_id = aws_security_group.alb.id
  description       = "HTTPS from CloudFront"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  prefix_list_id    = data.aws_ec2_managed_prefix_list.cloudfront_origin.id
}

resource "aws_vpc_security_group_egress_rule" "alb_to_backend" {
  security_group_id            = aws_security_group.alb.id
  description                  = "To backend"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.backend.id
}

resource "aws_security_group" "backend" {
  name        = "${local.name}-backend"
  description = "backend (Fargate): from ALB only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-backend" }
}

resource "aws_vpc_security_group_ingress_rule" "backend_from_alb" {
  security_group_id            = aws_security_group.backend.id
  description                  = "From ALB"
  ip_protocol                  = "tcp"
  from_port                    = 8080
  to_port                      = 8080
  referenced_security_group_id = aws_security_group.alb.id
}

# 나가는 쪽은 열어 둔다. ECR·SSM·카카오/네이버·AI 서버 등 목적지가 많고, 들어오는 쪽이 막혀 있으며
# 사설 서브넷이라 NAT를 거쳐서만 나간다.
resource "aws_vpc_security_group_egress_rule" "backend_all" {
  security_group_id = aws_security_group.backend.id
  description       = "Outbound"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "qdrant" {
  name        = "${local.name}-qdrant"
  description = "Qdrant: gRPC from backend only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-qdrant" }
}

resource "aws_vpc_security_group_ingress_rule" "qdrant_from_backend" {
  security_group_id            = aws_security_group.qdrant.id
  description                  = "gRPC from backend"
  ip_protocol                  = "tcp"
  from_port                    = 6334
  to_port                      = 6334
  referenced_security_group_id = aws_security_group.backend.id
}

resource "aws_vpc_security_group_egress_rule" "qdrant_all" {
  security_group_id = aws_security_group.qdrant.id
  description       = "Outbound (docker pull, SSM)"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_security_group" "rds" {
  name        = "${local.name}-rds"
  description = "PostgreSQL: from backend, and Qdrant host for migration"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-rds" }
}

resource "aws_vpc_security_group_ingress_rule" "rds_from_backend" {
  security_group_id            = aws_security_group.rds.id
  description                  = "From backend"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.backend.id
}

# 데이터 이관(pg_restore)과 운영 중 점검용. 노트북에서 SSM 포트 포워딩으로 Qdrant 인스턴스를 거쳐 들어온다.
# RDS를 공개하지 않고, SSH 포트도 열지 않으려는 것이다.
resource "aws_vpc_security_group_ingress_rule" "rds_from_qdrant_host" {
  security_group_id            = aws_security_group.rds.id
  description                  = "From Qdrant host (SSM port forwarding for migration)"
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  referenced_security_group_id = aws_security_group.qdrant.id
}

resource "aws_security_group" "redis" {
  name        = "${local.name}-redis"
  description = "ElastiCache: from backend only"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-redis" }
}

resource "aws_vpc_security_group_ingress_rule" "redis_from_backend" {
  security_group_id            = aws_security_group.redis.id
  description                  = "From backend"
  ip_protocol                  = "tcp"
  from_port                    = 6379
  to_port                      = 6379
  referenced_security_group_id = aws_security_group.backend.id
}

resource "aws_security_group" "tailscale_relay" {
  count = var.tailscale_relay_enabled ? 1 : 0

  name        = "${local.name}-tailscale-relay"
  description = "Tailscale relay: AI server traffic from backend"
  vpc_id      = aws_vpc.main.id
  tags        = { Name = "${local.name}-tailscale-relay" }
}

resource "aws_vpc_security_group_ingress_rule" "relay_from_backend" {
  count = var.tailscale_relay_enabled ? 1 : 0

  security_group_id            = aws_security_group.tailscale_relay[0].id
  description                  = "AI server traffic from backend"
  ip_protocol                  = "tcp"
  from_port                    = var.ai_server_port
  to_port                      = var.ai_server_port
  referenced_security_group_id = aws_security_group.backend.id
}

# Tailscale 직접 연결(WireGuard). 공개 서브넷의 중계 EC2가 AI 서버와 DERP 중계 없이 바로 통신하도록 연다(instances.tf).
resource "aws_vpc_security_group_ingress_rule" "relay_tailscale" {
  count = var.tailscale_relay_enabled && var.tailscale_relay_public ? 1 : 0

  security_group_id = aws_security_group.tailscale_relay[0].id
  description       = "Tailscale direct (WireGuard)"
  ip_protocol       = "udp"
  from_port         = 41641
  to_port           = 41641
  cidr_ipv4         = "0.0.0.0/0"
}

resource "aws_vpc_security_group_egress_rule" "relay_all" {
  count = var.tailscale_relay_enabled ? 1 : 0

  security_group_id = aws_security_group.tailscale_relay[0].id
  description       = "Outbound (Tailscale)"
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
}
