# ── 도메인 · 인증서 · DNS (domain_name이 있을 때만) ──────────────
# Route 53에서 도메인을 사면 호스팅 영역이 자동으로 생긴다. 여기서는 그 영역을 찾아 쓴다.

data "aws_route53_zone" "main" {
  count = local.has_domain ? 1 : 0

  name         = var.domain_name
  private_zone = false
}

# ALB용(서울). CloudFront가 Host 헤더(원래 도메인)를 그대로 넘기므로 원래 도메인도 덮어야 하고,
# CloudFront가 붙는 주소(origin.<도메인>)는 와일드카드로 덮는다.
resource "aws_acm_certificate" "regional" {
  count = local.has_domain ? 1 : 0

  domain_name               = var.domain_name
  subject_alternative_names = ["*.${var.domain_name}"]
  validation_method         = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# CloudFront용. 반드시 us-east-1에서 발급한다.
resource "aws_acm_certificate" "cloudfront" {
  count    = local.has_domain ? 1 : 0
  provider = aws.us_east_1

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# ACM의 검증 레코드는 같은 계정·같은 도메인 이름이면 인증서가 달라도 같다. 그래서 서울 인증서의 레코드
# (원래 도메인 + 와일드카드 = 같은 레코드 하나)만 만들고 두 인증서 검증에 같이 쓴다.
resource "aws_route53_record" "cert_validation" {
  for_each = local.has_domain ? {
    for dvo in aws_acm_certificate.regional[0].domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      type   = dvo.resource_record_type
      record = dvo.resource_record_value
    }
  } : {}

  zone_id         = data.aws_route53_zone.main[0].zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 300
  allow_overwrite = true # 원래 도메인과 와일드카드가 같은 레코드를 가리킨다
}

resource "aws_acm_certificate_validation" "regional" {
  count = local.has_domain ? 1 : 0

  certificate_arn         = aws_acm_certificate.regional[0].arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

resource "aws_acm_certificate_validation" "cloudfront" {
  count    = local.has_domain ? 1 : 0
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.cloudfront[0].arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

# 사용자 주소 → CloudFront
resource "aws_route53_record" "site" {
  for_each = local.has_domain ? toset(["A", "AAAA"]) : toset([])

  zone_id = data.aws_route53_zone.main[0].zone_id
  name    = var.domain_name
  type    = each.value

  alias {
    name                   = aws_cloudfront_distribution.main[0].domain_name
    zone_id                = aws_cloudfront_distribution.main[0].hosted_zone_id
    evaluate_target_health = false
  }
}

# CloudFront가 붙는 주소 → ALB. 환경이 꺼져 ALB가 없으면 레코드도 없다.
resource "aws_route53_record" "origin" {
  count = local.serving ? 1 : 0

  zone_id = data.aws_route53_zone.main[0].zone_id
  name    = local.origin_domain
  type    = "A"

  alias {
    name                   = aws_lb.main[0].dns_name
    zone_id                = aws_lb.main[0].zone_id
    evaluate_target_health = false
  }
}
