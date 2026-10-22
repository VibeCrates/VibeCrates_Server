# Terraform 상태(state)를 보관할 S3 버킷. 계정마다 한 번만 만든다.
#
# 상태 파일에는 리소스 ID와 일부 값(ALB-CloudFront 사이 비밀 헤더 등)이 들어 있다. 그래서
# 노트북이 아니라 암호화·버전 관리·공개 차단을 건 버킷에 두고, 동시에 두 사람이 apply하지 않도록
# S3 잠금 파일(use_lockfile, Terraform 1.10+)을 쓴다. DynamoDB 잠금 테이블은 필요 없다.
#
# 이 디렉터리는 자기 상태를 로컬(terraform.tfstate)에 둔다. 상태 버킷을 만드는 코드가 그 버킷에
# 상태를 둘 수는 없기 때문이다. 버킷이 만들어진 뒤에는 거의 바꿀 일이 없다.
#
#   cd infra/terraform/bootstrap
#   terraform init && terraform apply
#   → 출력된 bucket 이름을 ../prod/backend.hcl에 적는다

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project   = "crates"
      ManagedBy = "terraform"
      Stack     = "bootstrap"
    }
  }
}

variable "region" {
  type    = string
  default = "ap-northeast-2"
}

data "aws_caller_identity" "current" {}

# 버킷 이름은 전 세계에서 하나여야 해서 계정 ID를 붙인다.
resource "aws_s3_bucket" "tfstate" {
  bucket = "crates-tfstate-${data.aws_caller_identity.current.account_id}"

  # 상태를 잃으면 Terraform이 만든 리소스를 더는 관리할 수 없다. 실수로 지우지 못하게 막는다.
  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_versioning" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "tfstate" {
  bucket                  = aws_s3_bucket.tfstate.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# 버전이 계속 쌓이지 않게, 덮어써진 옛 상태는 90일 뒤 지운다.
resource "aws_s3_bucket_lifecycle_configuration" "tfstate" {
  bucket = aws_s3_bucket.tfstate.id
  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"
    filter {}
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }
}

output "bucket" {
  value = aws_s3_bucket.tfstate.bucket
}
