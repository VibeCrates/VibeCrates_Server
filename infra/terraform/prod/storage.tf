# ── S3: 프론트 정적 파일, 콘텐츠 이미지 ───────────────────────────
# 둘 다 비공개다. 브라우저는 CloudFront로만 받고, CloudFront는 OAC(서명된 요청)로 읽는다(cloudfront.tf).

resource "aws_s3_bucket" "frontend" {
  bucket = "${local.name}-frontend-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket" "images" {
  bucket = "${local.name}-images-${data.aws_caller_identity.current.account_id}"

  # 원본 이미지 서버에서 다시 받아 올리기 어렵다(일부는 이미 사라졌다). 실수로 지우지 못하게 막는다.
  lifecycle {
    prevent_destroy = true
  }
}

locals {
  buckets = {
    frontend = aws_s3_bucket.frontend.id
    images   = aws_s3_bucket.images.id
  }
}

resource "aws_s3_bucket_public_access_block" "this" {
  for_each = local.buckets

  bucket                  = each.value
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  for_each = local.buckets

  bucket = each.value
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  for_each = local.buckets

  bucket = each.value
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256" # OAC는 SSE-S3를 그대로 읽는다(KMS면 키 정책이 더 필요하다)
    }
  }
}

# 이미지 버킷은 덮어쓰기·삭제를 되돌릴 수 있게 버전을 남긴다. 프론트는 빌드 결과라 다시 올리면 된다.
resource "aws_s3_bucket_versioning" "images" {
  bucket = aws_s3_bucket.images.id
  versioning_configuration {
    status = "Enabled"
  }
}

# ── S3: 운영 작업용(비공개) ──────────────────────────────────────
# 일회성 작업의 입력(예: 이미지 채우기 작업의 원본 CSV)을 둔다. CloudFront에 붙이지 않는다 — 이미지 버킷은
# /images 경로로 외부에 공개되므로 거기에 작업 파일을 두면 밖에서 읽힐 수 있다.
# 위의 local.buckets(CloudFront가 읽는 버킷 목록)에 넣지 않으려고 따로 적는다.
resource "aws_s3_bucket" "ops" {
  bucket = "${local.name}-ops-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_public_access_block" "ops" {
  bucket                  = aws_s3_bucket.ops.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 작업 입력은 한 번 쓰고 버린다. 남겨 두면 원본 CSV(수백 MB)가 계속 쌓인다.
resource "aws_s3_bucket_lifecycle_configuration" "ops" {
  bucket = aws_s3_bucket.ops.id
  rule {
    id     = "expire-job-inputs"
    status = "Enabled"
    filter {
      prefix = "jobs/"
    }
    expiration {
      days = 30
    }
  }
}

# ── ECR: backend 이미지 ──────────────────────────────────────────
resource "aws_ecr_repository" "backend" {
  name = "${var.project}-backend"

  # 같은 태그를 덮어쓰지 못하게 한다. 배포된 이미지가 무엇인지 태그(커밋 SHA)로 항상 추적된다.
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "AES256"
  }
}

# 이미지가 쌓이지 않게 최근 20개만 남긴다(되돌릴 여유는 충분하다).
resource "aws_ecr_lifecycle_policy" "backend" {
  repository = aws_ecr_repository.backend.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "keep last 20 images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 20
      }
      action = { type = "expire" }
    }]
  })
}
