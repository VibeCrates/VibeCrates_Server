# ── CloudFront: 사용자가 만나는 유일한 입구 ───────────────────────
#
#   /api/*, /oauth2/*, /login/oauth2/*  → ALB(origin.<도메인>)   캐시 끔, 헤더·쿠키·쿼리 모두 전달
#   /images/*                           → images 버킷(OAC)      캐시, /images 접두사 제거
#   그 밖(*)                             → frontend 버킷(OAC)    캐시, 화면 주소는 index.html로
#
# 프론트와 API가 한 도메인이라 CORS와 쿠키 문제가 없다. 환경이 꺼져 ALB가 없으면 API 경로는 502가 난다.

data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

data "aws_cloudfront_cache_policy" "caching_optimized" {
  name = "Managed-CachingOptimized"
}

# Authorization·Host·쿠키·쿼리를 모두 ALB로 넘긴다. Host를 넘겨야 backend가 OAuth 콜백 주소를
# https://<도메인>으로 만든다(application-prod.yaml의 forward-headers-strategy).
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# HSTS, X-Content-Type-Options, X-Frame-Options 등 보안 응답 헤더.
data "aws_cloudfront_response_headers_policy" "security_headers" {
  name = "Managed-SecurityHeadersPolicy"
}

resource "aws_cloudfront_origin_access_control" "s3" {
  count = local.has_domain ? 1 : 0

  name                              = "${local.name}-s3"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_function" "spa_rewrite" {
  count = local.has_domain ? 1 : 0

  name    = "${local.name}-spa-rewrite"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/templates/spa_rewrite.js")
}

resource "aws_cloudfront_function" "strip_images_prefix" {
  count = local.has_domain ? 1 : 0

  name    = "${local.name}-strip-images-prefix"
  runtime = "cloudfront-js-2.0"
  publish = true
  code    = file("${path.module}/templates/strip_images_prefix.js")
}

locals {
  # ALB로 보내는 경로. 순서대로 먼저 맞는 것이 이긴다.
  api_path_patterns = ["/api/*", "/oauth2/*", "/login/oauth2/*"]
}

resource "aws_cloudfront_distribution" "main" {
  count = local.has_domain ? 1 : 0

  enabled             = true
  is_ipv6_enabled     = true
  http_version        = "http2and3"
  price_class         = "PriceClass_200" # 한국 포함 아시아 엣지
  aliases             = [var.domain_name]
  default_root_object = "index.html"
  comment             = local.name

  origin {
    origin_id                = "frontend"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3[0].id
  }

  origin {
    origin_id                = "images"
    domain_name              = aws_s3_bucket.images.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.s3[0].id
  }

  origin {
    origin_id   = "backend"
    domain_name = local.origin_domain

    custom_origin_config {
      http_port              = 80
      https_port             = 443
      origin_protocol_policy = "https-only"
      origin_ssl_protocols   = ["TLSv1.2"]
      origin_read_timeout    = 30
    }

    # ALB 리스너 규칙이 이 값을 확인한다(alb.tf).
    custom_header {
      name  = "X-Origin-Verify"
      value = random_password.origin_verify.result
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.api_path_patterns
    content {
      path_pattern             = ordered_cache_behavior.value
      target_origin_id         = "backend"
      viewer_protocol_policy   = "redirect-to-https"
      allowed_methods          = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods           = ["GET", "HEAD"]
      compress                 = true
      cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    }
  }

  ordered_cache_behavior {
    path_pattern           = "/images/*"
    target_origin_id       = "images"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true
    cache_policy_id        = data.aws_cloudfront_cache_policy.caching_optimized.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.strip_images_prefix[0].arn
    }
  }

  default_cache_behavior {
    target_origin_id           = "frontend"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    compress                   = true
    cache_policy_id            = data.aws_cloudfront_cache_policy.caching_optimized.id
    response_headers_policy_id = data.aws_cloudfront_response_headers_policy.security_headers.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_rewrite[0].arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.cloudfront[0].certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# 두 버킷은 이 배포만 읽을 수 있다(OAC). 다른 CloudFront 배포나 공개 접근은 막힌다.
data "aws_iam_policy_document" "cloudfront_read" {
  for_each = local.has_domain ? local.buckets : {}

  statement {
    sid       = "CloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${each.value}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.main[0].arn]
    }
  }
}

resource "aws_s3_bucket_policy" "cloudfront_read" {
  for_each = local.has_domain ? local.buckets : {}

  bucket = each.value
  policy = data.aws_iam_policy_document.cloudfront_read[each.key].json

  depends_on = [aws_s3_bucket_public_access_block.this]
}
