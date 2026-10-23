# EC2 두 대: Qdrant, Tailscale 중계. 둘 다 app 서브넷(공인 IP 없음)에 두고 SSH 대신 SSM Session Manager로 들어간다.
# Graviton(ARM)이라 Amazon Linux 2023 arm64를 쓴다.

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

# ── 인스턴스 역할 ────────────────────────────────────────────────
data "aws_iam_policy_document" "ec2_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "qdrant" {
  name               = "${local.name}-qdrant"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "qdrant_ssm" {
  role       = aws_iam_role.qdrant.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy_attachment" "qdrant_cloudwatch" {
  role       = aws_iam_role.qdrant.name
  policy_arn = "arn:aws:iam::aws:policy/CloudWatchAgentServerPolicy"
}

# Qdrant API 키 하나만 읽는다.
resource "aws_iam_role_policy" "qdrant_read_api_key" {
  name = "read-qdrant-api-key"
  role = aws_iam_role.qdrant.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter"]
      Resource = "${local.ssm_param_arn_prefix}${local.qdrant_api_key_param}"
    }]
  })
}

resource "aws_iam_instance_profile" "qdrant" {
  name = "${local.name}-qdrant"
  role = aws_iam_role.qdrant.name
}

# ── Qdrant ───────────────────────────────────────────────────────
# 데이터는 인스턴스와 따로 만든 볼륨에 둔다. 인스턴스를 다시 만들어도(AMI·사양 변경) 벡터가 남는다.
resource "aws_ebs_volume" "qdrant_data" {
  availability_zone = var.azs[0]
  size              = var.qdrant_volume_size
  type              = "gp3"
  encrypted         = true

  tags = { Name = "${local.name}-qdrant-data" }

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_instance" "qdrant" {
  ami                    = data.aws_ssm_parameter.al2023_arm64.value
  instance_type          = var.qdrant_instance_type
  subnet_id              = aws_subnet.app[0].id # 데이터 볼륨과 같은 AZ
  vpc_security_group_ids = [aws_security_group.qdrant.id]
  iam_instance_profile   = aws_iam_instance_profile.qdrant.name

  user_data = templatefile("${path.module}/templates/qdrant_user_data.sh.tftpl", {
    region         = var.region
    api_key_param  = local.qdrant_api_key_param
    qdrant_version = var.qdrant_version
    volume_serial  = replace(aws_ebs_volume.qdrant_data.id, "-", "")
  })

  # 메타데이터는 토큰 방식(IMDSv2)만 받는다. 컨테이너가 인스턴스 자격 증명을 훔치기 어렵게 한다.
  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 20 # docker 이미지 + OS
    encrypted   = true
  }

  tags = { Name = "${local.name}-qdrant" }

  # 최신 AMI가 나올 때마다 인스턴스가 다시 만들어지지 않게 한다. 올릴 때는 taint로 명시적으로 바꾼다.
  lifecycle {
    ignore_changes = [ami]
  }
}

resource "aws_volume_attachment" "qdrant_data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.qdrant_data.id
  instance_id = aws_instance.qdrant.id

  # 정지 상태에서도 떼지 않고, 인스턴스를 바꿀 때만 뗀다.
  stop_instance_before_detaching = true
}

resource "aws_ec2_instance_state" "qdrant" {
  instance_id = aws_instance.qdrant.id
  state       = var.active ? "running" : "stopped"
}

# ── Tailscale 중계 ───────────────────────────────────────────────
resource "aws_iam_role" "tailscale_relay" {
  count = var.tailscale_relay_enabled ? 1 : 0

  name               = "${local.name}-tailscale-relay"
  assume_role_policy = data.aws_iam_policy_document.ec2_assume.json
}

resource "aws_iam_role_policy_attachment" "relay_ssm" {
  count = var.tailscale_relay_enabled ? 1 : 0

  role       = aws_iam_role.tailscale_relay[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_role_policy" "relay_read_auth_key" {
  count = var.tailscale_relay_enabled ? 1 : 0

  name = "read-tailscale-auth-key"
  role = aws_iam_role.tailscale_relay[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter"]
      Resource = "${local.ssm_param_arn_prefix}${local.tailscale_auth_key_param}"
    }]
  })
}

resource "aws_iam_instance_profile" "tailscale_relay" {
  count = var.tailscale_relay_enabled ? 1 : 0

  name = "${local.name}-tailscale-relay"
  role = aws_iam_role.tailscale_relay[0].name
}

resource "aws_instance" "tailscale_relay" {
  count = var.tailscale_relay_enabled ? 1 : 0

  ami = data.aws_ssm_parameter.al2023_arm64.value
  # nano(512MB)는 첫 기동 때 dnf가 저장소 메타데이터를 읽다 메모리 부족으로 죽는다(2026-10-23 첫 적용에서 확인).
  instance_type          = "t4g.micro"
  subnet_id              = aws_subnet.app[0].id
  vpc_security_group_ids = [aws_security_group.tailscale_relay[0].id]
  iam_instance_profile   = aws_iam_instance_profile.tailscale_relay[0].name

  # 자기 주소가 아닌 패킷(backend → AI 서버)을 받아 넘겨야 하므로 출발지/목적지 확인을 끈다.
  source_dest_check = false

  user_data = templatefile("${path.module}/templates/tailscale_relay_user_data.sh.tftpl", {
    region         = var.region
    auth_key_param = local.tailscale_auth_key_param
    hostname       = "${local.name}-relay"
    vpc_cidr       = var.vpc_cidr
  })

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 8
    encrypted   = true
  }

  tags = { Name = "${local.name}-tailscale-relay" }

  lifecycle {
    ignore_changes = [ami]
  }
}

resource "aws_ec2_instance_state" "tailscale_relay" {
  count = var.tailscale_relay_enabled ? 1 : 0

  instance_id = aws_instance.tailscale_relay[0].id
  state       = var.active ? "running" : "stopped"
}
