terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }

  # 상태는 bootstrap이 만든 S3 버킷에 둔다. 버킷 이름은 계정마다 달라 코드에 박지 않고
  # backend.hcl(backend.hcl.example 참고)로 넘긴다: terraform init -backend-config=backend.hcl
  backend "s3" {}
}

provider "aws" {
  region = var.region

  default_tags {
    tags = local.tags
  }
}

# CloudFront에 붙이는 인증서는 반드시 us-east-1에서 발급해야 한다.
provider "aws" {
  alias  = "us_east_1"
  region = "us-east-1"

  default_tags {
    tags = local.tags
  }
}
