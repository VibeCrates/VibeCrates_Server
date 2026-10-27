# 인프라 (Terraform)

AWS 운영 환경을 코드로 관리한다. 설계와 결정 이유는 노션 「배포 계획」에 있고, 여기에는 **어떻게 쓰는지**를 적는다.

```
infra/terraform/
  bootstrap/   Terraform 상태를 보관할 S3 버킷(계정마다 한 번)
  prod/        운영 환경
    network.tf          VPC, 서브넷 3계층(public / app / data) × AZ 2, NAT(AZ마다), S3 엔드포인트
    security_groups.tf  CloudFront → ALB → backend → RDS·Redis·Qdrant·중계
    data_stores.tf      RDS PostgreSQL 15, ElastiCache Redis 7.1
    instances.tf        Qdrant EC2(+ 데이터용 EBS), Tailscale 중계 EC2
    storage.tf          S3(frontend, images), ECR
    ecs.tf              Fargate 작업 정의·서비스, 실행·작업 역할
    alb.tf              ALB(CloudFront에서만 받음)
    dns.tf              Route 53, ACM 인증서
    cloudfront.tf       CloudFront(경로별 오리진, OAC, SPA·이미지 경로 함수)
    github_oidc.tf      GitHub Actions 배포 역할(키 없이 OIDC)
    tests/              terraform test (도메인 유무 × 켜기/끄기 조합)
  scripts/     env-up.sh, env-down.sh
```

## 두 개의 스위치

| 변수 | 의미 |
|---|---|
| `domain_name` (`terraform.tfvars`) | 비어 있으면 인증서·ALB·ECS 서비스·CloudFront·DNS를 만들지 않는다. 도메인 없이도 네트워크·데이터 계층을 먼저 올려 데이터를 옮겨 둘 수 있다 |
| `active` (`env.auto.tfvars`, 스크립트가 씀) | false면 시간 요금이 드는 것을 내린다. NAT·ALB·ECS 서비스·ElastiCache 삭제, EC2 정지. EIP·EBS·RDS·S3·CloudFront·ECR은 남는다 |

## 0. 준비 (한 번)

1. 도구 설치. Xcode Command Line Tools가 오래되면 Homebrew가 설치를 거부한다.
   ```bash
   sudo xcode-select --install          # 또는 소프트웨어 업데이트에서 Command Line Tools 갱신
   brew tap hashicorp/tap
   brew install hashicorp/tap/terraform awscli session-manager-plugin
   ```
2. AWS 로그인. 루트 계정 키를 만들지 않는다. IAM Identity Center(SSO) 사용자를 만들어 로그인한다.
   ```bash
   aws configure sso          # 프로필 이름 예: crates
   export AWS_PROFILE=crates
   aws sts get-caller-identity
   ```
3. 결제 알람: AWS Budgets에서 월 예산 알람을 먼저 건다.

## 1. 상태 버킷 (한 번)

```bash
cd infra/terraform/bootstrap
terraform init && terraform apply
cp ../prod/backend.hcl.example ../prod/backend.hcl   # bucket에 출력값을 적는다
```

## 2. 비밀값 등록 (한 번, 값이 바뀔 때)

비밀값은 Terraform이 만들지 않는다(상태 파일에 평문으로 남는다). SSM Parameter Store에 SecureString으로 넣는다.
DB 비밀번호는 넣지 않는다 — RDS가 Secrets Manager에 만들고 관리한다.

```bash
P=/crates/prod
put() { aws ssm put-parameter --name "$1" --type SecureString --overwrite --value "$2" >/dev/null; }
put $P/jwt-secret-key                "$(openssl rand -base64 48)"
put $P/search-like-token-secret      "$(openssl rand -base64 48)"   # 검색 보드 좋아요 토큰 서명 키
put $P/qdrant-api-key                "$(openssl rand -hex 32)"
put $P/oauth/google-client-id        '...'
put $P/oauth/google-client-secret    '...'
put $P/oauth/kakao-client-id         '...'
put $P/oauth/kakao-client-secret     '...'
put $P/oauth/naver-client-id         '...'
put $P/oauth/naver-client-secret     '...'
put $P/tailscale-auth-key            'tskey-auth-...'   # Tailscale 관리 화면에서 발급(아래 6절)
```

JWT 키를 로컬(`crates_server.env`)과 같은 값으로 두지 않는다. 운영 토큰을 로컬에서 위조할 수 있게 된다.

## 3. 첫 적용 — 도메인 없이 (네트워크·데이터 계층)

```bash
cd infra/terraform/prod
terraform init -backend-config=backend.hcl
echo 'active = true' > env.auto.tfvars    # 첫 적용은 켠 상태로(EC2 첫 기동 스크립트가 끝나야 한다)
terraform plan
terraform apply
```

이때 만들어지는 것: VPC·서브넷·NAT, RDS, ElastiCache, Qdrant·중계 EC2, S3, ECR, IAM 역할, `backend-image-tag` 파라미터("unset").

계정에 GitHub OIDC 공급자가 이미 있으면 만들기가 실패한다. 그때는 가져온다:
`terraform import aws_iam_openid_connect_provider.github arn:aws:iam::<계정>:oidc-provider/token.actions.githubusercontent.com`

## 4. 데이터 이관 (한 번)

운영 데이터는 로컬에서 옮긴다. 부하 측정 데이터(`loadtest_` 계정 1,200개 · 보드 25만 개)는 옮기지 않는다.
RDS·Qdrant는 공개되지 않으므로 **Qdrant EC2를 거치는 SSM 포트 포워딩**으로 붙는다(SSH 포트를 열지 않는다).

```bash
QDRANT_ID=$(terraform output -raw qdrant_instance_id)
RDS_HOST=$(terraform output -raw rds_endpoint)
```

### PostgreSQL

```bash
# (1) 로컬에서 부하 측정 데이터를 지우고 덤프 — load-test/cleanup.sql
docker exec -i -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  psql -U $POSTGRESQL_USERNAME -d crates -v ON_ERROR_STOP=1 < load-test/cleanup.sql
# 남은 것을 눈으로 확인한다. cleanup.sql은 이름이 loadtest로 시작하는 계정·보드만 지운다.
# 측정 중 다른 이름으로 생긴 보드(예: smoke 보드)가 남아 있으면 따로 지운다.
docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server psql -U $POSTGRESQL_USERNAME -d crates -c "
  SELECT (SELECT count(*) FROM users) users, (SELECT count(*) FROM board) boards,
         (SELECT count(*) FROM board_feedback) likes, (SELECT count(*) FROM content) contents"
docker exec -e PGPASSWORD=$POSTGRESQL_PASSWORD postgres_server \
  pg_dump -U $POSTGRESQL_USERNAME -d crates -Fc --no-owner --no-privileges -f /tmp/crates.dump
docker cp postgres_server:/tmp/crates.dump ./crates.dump

# (2) 다른 터미널: 노트북 15432 → (Qdrant EC2) → RDS 5432
aws ssm start-session --target $QDRANT_ID \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$RDS_HOST\"],\"portNumber\":[\"5432\"],\"localPortNumber\":[\"15432\"]}"

# (3) 복원 — 마스터 비밀번호는 Secrets Manager에서 꺼낸다. 버전을 맞추려고 같은 postgres:15 이미지로 실행한다
PGPASS=$(aws secretsmanager get-secret-value --secret-id "$(terraform output -raw rds_master_secret_arn)" \
  --query SecretString --output text | python3 -c "import json,sys;print(json.load(sys.stdin)['password'])")
docker run --rm -v "$PWD/crates.dump:/crates.dump" -e PGPASSWORD="$PGPASS" -e PGSSLMODE=require postgres:15 \
  pg_restore -h host.docker.internal -p 15432 -U crates_admin -d crates --no-owner --no-privileges /crates.dump
```

Flyway 이력 테이블도 함께 옮겨지므로 backend가 기동할 때 마이그레이션이 다시 돌지 않는다(검증만 한다).

### Qdrant

query 벡터 CSV는 git에 없으므로(번역해 채운 행 포함) 스냅샷으로 옮긴다. 서버 버전(v1.14.1)이 같아야 한다.

```bash
# (1) 로컬에서 컬렉션별 스냅샷
for c in content_vector query_vector; do
  NAME=$(curl -s -X POST localhost:6333/collections/$c/snapshots | python3 -c "import json,sys;print(json.load(sys.stdin)['result']['name'])")
  curl -s -o $c.snapshot localhost:6333/collections/$c/snapshots/$NAME
done

# (2) 다른 터미널: 노트북 16333 → Qdrant EC2 6333(REST)
aws ssm start-session --target $QDRANT_ID \
  --document-name AWS-StartPortForwardingSession \
  --parameters '{"portNumber":["6333"],"localPortNumber":["16333"]}'

# (3) 올려서 복원(API 키 필요)
KEY=$(aws ssm get-parameter --name /crates/prod/qdrant-api-key --with-decryption --query Parameter.Value --output text)
for c in content_vector query_vector; do
  curl -s -X POST "localhost:16333/collections/$c/snapshots/upload?priority=snapshot" \
    -H "api-key: $KEY" -F "snapshot=@$c.snapshot"
done
curl -s -H "api-key: $KEY" localhost:16333/collections   # 두 컬렉션이 보이면 끝
```

### 이미지

객체 키는 로컬과 같다(`book/123.jpg`). CloudFront가 `/images` 접두사를 떼고 버킷을 읽는다.

```bash
docker run --rm --network host -v "$PWD/images:/out" minio/mc sh -c \
  "mc alias set local http://localhost:9000 $STORAGE_ACCESS_KEY $STORAGE_SECRET_KEY && mc mirror local/content-bucket /out"
aws s3 sync ./images "s3://$(terraform output -raw images_bucket)/"
```

## 5. 첫 backend 이미지

배포 자동화(GitHub Actions)를 붙이기 전에는 손으로 한 번 올린다. **git에서 새로 받은 소스로 빌드한다.**
로컬 작업 폴더에는 벡터 CSV(`src/main/resources/data/`, 수 GB)가 있어 그대로 빌드하면 이미지에 들어간다.
운영 프로파일(`application-prod.yaml`)과 S3Client 통일이 `main`에 병합된 뒤여야 한다.

```bash
REPO=$(terraform output -raw ecr_repository_url)
TAG=$(git rev-parse --short HEAD)
aws ecr get-login-password | docker login --username AWS --password-stdin "${REPO%%/*}"
git clone --depth 1 https://github.com/VibeCrates/VibeCrates_Server.git /tmp/crates-src
docker buildx build --platform linux/arm64 -t "$REPO:$TAG" --push /tmp/crates-src
aws ssm put-parameter --name /crates/prod/backend-image-tag --type String --overwrite --value "$TAG"
```

## 6. Tailscale 중계 (AI 서버가 노트북에 있는 동안)

- Tailscale 관리 화면 → Settings → Keys에서 인증 키를 만든다(1회용, 미리 승인). 2절의 `tailscale-auth-key`에 넣는다.
- 중계 EC2는 처음 켤 때 이 키로 망에 들어가고, VPC에서 오는 AI 서버행 트래픽의 출발지를 자기 Tailscale 주소로 바꿔 보낸다.
  AI 서버 쪽에서 VPC 경로를 받을 필요는 없지만, Tailscale ACL이 중계 → AI 서버 8000 포트를 허용해야 한다.
- AI 서버가 AWS로 오면 `tailscale_relay_enabled = false`, `ai_server_base_url`을 새 주소로 바꿔 apply한다.

## 7. 도메인을 산 뒤

```bash
# terraform.tfvars
domain_name = "<산 도메인>"
```

`terraform apply` → 인증서(서울·us-east-1) 발급·검증, CloudFront, ALB, ECS 서비스, Route 53 레코드가 만들어진다.

- 카카오·네이버 개발자 콘솔: 사이트 도메인 `https://<도메인>`, Redirect URI `https://<도메인>/login/oauth2/code/kakao`(naver)를 등록한다.
- 구글 콘솔: 승인된 리디렉션 URI `https://<도메인>/login/oauth2/code/google`.
- 프론트: `.env.production`의 `VITE_AUTH_ORIGIN`을 비우고 빌드 → `aws s3 sync dist/ s3://<frontend 버킷>/ --delete` → CloudFront 무효화.

## 8. 켜기 · 끄기

```bash
infra/terraform/scripts/env-up.sh     # RDS 시작 → active=true apply
infra/terraform/scripts/env-down.sh   # active=false apply → RDS 정지
```

- NAT 게이트웨이는 정지가 없어 지웠다 다시 만든다. EIP는 남아 나가는 IP가 같다.
- **RDS는 정지해도 7일 뒤 AWS가 자동으로 다시 켠다.** 오래 끌 때는 7일마다 `env-down.sh`를 다시 돌린다.
- 꺼져 있는 동안 사이트(프론트)는 열리지만 API는 502다.

## 테스트

```bash
cd infra/terraform/prod
terraform init -backend=false
terraform validate
terraform test     # AWS에 붙지 않는다(mock). 도메인 유무 × 켜기/끄기에서 리소스가 의도대로 생기고 사라지는지
```

## 아직 하지 않은 것

- GitHub Actions 워크플로(backend 이미지 빌드·배포, 프론트 배포). 역할은 `github_oidc.tf`에 있다.
- backend 전용 DB 계정. 지금은 RDS 마스터 계정(Secrets Manager)을 쓴다. 최소 권한으로 나누려면 앱 계정을 만들어 비밀번호를 SSM에 넣는다.
- WAF, RDS Multi-AZ, ElastiCache 복제본 — 비용 때문에 뺐다.
