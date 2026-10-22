# ── GitHub Actions 배포 역할 (OIDC) ──────────────────────────────
# GitHub에 AWS 액세스 키를 저장하지 않는다. 워크플로가 GitHub의 OIDC 토큰을 내밀면 AWS가 짧은 임시
# 자격 증명을 준다. 신뢰 조건으로 "이 저장소의 이 브랜치"만 받아, 다른 브랜치·포크의 워크플로는 역할을 못 받는다.
#
# 계정에 같은 OIDC 공급자가 이미 있으면 만들기가 실패한다. 그때는 import한다(README).

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_assume" {
  for_each = {
    backend  = var.github_backend_repo
    frontend = var.github_frontend_repo
  }

  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_org}/${each.value}:ref:refs/heads/${var.github_deploy_branch}"]
    }
  }
}

# backend: 이미지를 올리고, 작업 정의의 이미지만 바꿔 서비스를 갱신하고, 배포한 태그를 기록한다.
resource "aws_iam_role" "github_backend_deploy" {
  name               = "${local.name}-github-backend-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume["backend"].json
}

resource "aws_iam_role_policy" "github_backend_deploy" {
  name = "deploy-backend"
  role = aws_iam_role.github_backend_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrLogin"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload", "ecr:PutImage", "ecr:BatchGetImage", "ecr:DescribeImages"
        ]
        Resource = aws_ecr_repository.backend.arn
      },
      {
        # 작업 정의 조회·등록은 리소스 단위로 좁힐 수 없다.
        Sid      = "TaskDefinition"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition", "ecs:RegisterTaskDefinition"]
        Resource = "*"
      },
      {
        Sid      = "UpdateService"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService", "ecs:DescribeServices"]
        Resource = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${aws_ecs_cluster.main.name}/backend"
      },
      {
        # 새 작업 정의에 같은 역할을 붙이려면 그 역할을 넘길 권한이 필요하다.
        Sid      = "PassTaskRoles"
        Effect   = "Allow"
        Action   = ["iam:PassRole"]
        Resource = [aws_iam_role.ecs_execution.arn, aws_iam_role.ecs_task.arn]
      },
      {
        Sid      = "RecordImageTag"
        Effect   = "Allow"
        Action   = ["ssm:PutParameter", "ssm:GetParameter"]
        Resource = aws_ssm_parameter.backend_image_tag.arn
      }
    ]
  })
}

# frontend: 빌드 결과를 버킷에 올리고 CloudFront 캐시를 비운다.
resource "aws_iam_role" "github_frontend_deploy" {
  name               = "${local.name}-github-frontend-deploy"
  assume_role_policy = data.aws_iam_policy_document.github_assume["frontend"].json
}

resource "aws_iam_role_policy" "github_frontend_deploy" {
  name = "deploy-frontend"
  role = aws_iam_role.github_frontend_deploy.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [
        {
          Sid      = "SyncBucket"
          Effect   = "Allow"
          Action   = ["s3:ListBucket"]
          Resource = aws_s3_bucket.frontend.arn
        },
        {
          Sid      = "SyncObjects"
          Effect   = "Allow"
          Action   = ["s3:PutObject", "s3:DeleteObject", "s3:GetObject"]
          Resource = "${aws_s3_bucket.frontend.arn}/*"
        }
      ],
      local.has_domain ? [{
        Sid      = "Invalidate"
        Effect   = "Allow"
        Action   = ["cloudfront:CreateInvalidation"]
        Resource = aws_cloudfront_distribution.main[0].arn
      }] : []
    )
  })
}
