# 서브넷 3계층 × 가용 영역 2개
#
#   public  ALB, NAT 게이트웨이            인터넷 게이트웨이로 직접 연결
#   app     backend(Fargate), Qdrant,      NAT로만 나간다(이미지 받기, 카카오/네이버 토큰 교환,
#           Tailscale 중계                 Tailscale, SSM). 공인 IP가 없다
#   data    RDS, ElastiCache               인터넷으로 가는 경로가 없다. 요청을 받기만 하므로 나갈 일이 없고,
#                                          털려도 바깥으로 빼낼 길이 없게 한다
#
# NAT 게이트웨이는 가용 영역마다 하나다. 하나만 두면 그 AZ 장애가 다른 AZ의 나가는 통신까지 막고,
# AZ를 넘는 트래픽 요금도 붙는다. 환경을 끄면(active = false) NAT만 지우고 EIP는 남겨, 다시 켜도
# 나가는 IP가 같다(상대가 IP 허용 목록을 쓰는 경우를 대비).

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true # RDS·ElastiCache 엔드포인트, 사설 DNS 이름에 필요

  tags = { Name = local.name }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = local.name }
}

resource "aws_subnet" "public" {
  count = length(var.azs)

  vpc_id            = aws_vpc.main.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, count.index) # 10.20.0.0/24, 10.20.1.0/24

  tags = { Name = "${local.name}-public-${var.azs[count.index]}", Tier = "public" }
}

resource "aws_subnet" "app" {
  count = length(var.azs)

  vpc_id            = aws_vpc.main.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 10 + count.index) # 10.20.10.0/24, 10.20.11.0/24

  tags = { Name = "${local.name}-app-${var.azs[count.index]}", Tier = "app" }
}

resource "aws_subnet" "data" {
  count = length(var.azs)

  vpc_id            = aws_vpc.main.id
  availability_zone = var.azs[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 8, 20 + count.index) # 10.20.20.0/24, 10.20.21.0/24

  tags = { Name = "${local.name}-data-${var.azs[count.index]}", Tier = "data" }
}

# ── public ───────────────────────────────────────────────────────
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-public" }
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}

resource "aws_route_table_association" "public" {
  count          = length(var.azs)
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# ── NAT (AZ마다) ─────────────────────────────────────────────────
resource "aws_eip" "nat" {
  count  = length(var.azs)
  domain = "vpc"

  tags = { Name = "${local.name}-nat-${var.azs[count.index]}" }

  # 나가는 IP를 고정해 두려는 것이라, 실수로 지워져 새 IP를 받지 않게 막는다.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_nat_gateway" "main" {
  count = var.active ? length(var.azs) : 0

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = { Name = "${local.name}-${var.azs[count.index]}" }

  depends_on = [aws_internet_gateway.main]
}

# ── app: AZ마다 라우팅 테이블 (같은 AZ의 NAT로 나간다) ──────────────
resource "aws_route_table" "app" {
  count  = length(var.azs)
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-app-${var.azs[count.index]}" }
}

resource "aws_route" "app_nat" {
  count = var.active ? length(var.azs) : 0

  route_table_id         = aws_route_table.app[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main[count.index].id
}

# AI 서버(Tailscale 망)로 가는 길은 VPC 안의 중계 EC2로 보낸다(tailscale.tf).
resource "aws_route" "app_tailscale" {
  count = var.tailscale_relay_enabled ? length(var.azs) : 0

  route_table_id         = aws_route_table.app[count.index].id
  destination_cidr_block = var.tailscale_cidr
  network_interface_id   = aws_instance.tailscale_relay[0].primary_network_interface_id
}

resource "aws_route_table_association" "app" {
  count          = length(var.azs)
  subnet_id      = aws_subnet.app[count.index].id
  route_table_id = aws_route_table.app[count.index].id
}

# ── data: 바깥으로 가는 경로 없음 ───────────────────────────────────
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.main.id
  tags   = { Name = "${local.name}-data" }
}

resource "aws_route_table_association" "data" {
  count          = length(var.azs)
  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# ── VPC 엔드포인트 ───────────────────────────────────────────────
# S3 게이트웨이 엔드포인트는 무료다. backend의 S3 접근과 ECR 이미지 층(S3에 저장됨)이 NAT를
# 거치지 않아 NAT 처리 요금이 줄고, 환경을 막 켠 직후에도 S3에는 닿는다.
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = aws_route_table.app[*].id

  tags = { Name = "${local.name}-s3" }
}
