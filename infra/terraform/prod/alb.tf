# ── ALB: CloudFront에서만 받는다 ─────────────────────────────────
# 두 겹으로 막는다.
#   1. 보안 그룹: CloudFront 오리진 IP 대역(관리형 접두사 목록)에서 오는 443만
#   2. 리스너 규칙: CloudFront가 붙이는 비밀 헤더(X-Origin-Verify)가 맞을 때만 backend로 보낸다
# 1만 있으면 다른 사람의 CloudFront 배포도 같은 IP 대역이라 ALB에 닿을 수 있다.
# 환경을 끄면(active = false) 시간 요금을 줄이려고 ALB를 지운다. CloudFront는 origin.<도메인>으로 붙으므로
# 다시 켜면 레코드만 새 ALB를 가리키고 CloudFront 설정은 그대로다.

resource "random_password" "origin_verify" {
  length  = 40
  special = false
}

resource "aws_lb" "main" {
  count = local.serving ? 1 : 0

  name               = local.name
  load_balancer_type = "application"
  internal           = false
  security_groups    = [aws_security_group.alb.id]
  subnets            = aws_subnet.public[*].id

  drop_invalid_header_fields = true
  idle_timeout               = 60 # 검색 보드는 최대 8초(대기 상한) 걸린다
}

resource "aws_lb_target_group" "backend" {
  count = local.serving ? 1 : 0

  name                 = "${local.name}-backend"
  port                 = 8080
  protocol             = "HTTP"
  target_type          = "ip" # Fargate(awsvpc)
  vpc_id               = aws_vpc.main.id
  deregistration_delay = 30 # 롤링 배포 때 처리 중인 요청을 끝낼 시간. graceful shutdown보다 짧지 않게

  # readiness만 본다. 전체 health는 Redis까지 포함해, 캐시가 죽었을 때 멀쩡한 backend를 교체해 버린다.
  health_check {
    path                = "/actuator/health/readiness"
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  count = local.serving ? 1 : 0

  load_balancer_arn = aws_lb.main[0].arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.regional[0].certificate_arn

  # 비밀 헤더가 없는 요청은 여기서 끝난다.
  default_action {
    type = "fixed-response"
    fixed_response {
      content_type = "text/plain"
      message_body = "forbidden"
      status_code  = "403"
    }
  }
}

resource "aws_lb_listener_rule" "from_cloudfront" {
  count = local.serving ? 1 : 0

  listener_arn = aws_lb_listener.https[0].arn
  priority     = 10

  condition {
    http_header {
      http_header_name = "X-Origin-Verify"
      values           = [random_password.origin_verify.result]
    }
  }

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.backend[0].arn
  }
}
