# DB 백업과 복원

운영 PostgreSQL(RDS `crates-prod`)을 어떻게 백업하고, 장애 때 무엇으로 어떻게 되돌리는지 정리한다.
인프라 코드: `infra/terraform/prod/data_stores.tf`(RDS 자동 백업), `db_backup.tf`(매일 덤프).

- 작성: 2026-11-09
- 덤프·복원 확인: 2026-11-09 로컬 데이터(시딩 v2, 296MB)로 덤프 21초, 새 PostgreSQL 15에 복원 18초, 주요 테이블 행 수 일치

---

## 1. 무엇이 언제 남나

| | RDS 자동 백업 (1차) | 매일 덤프 (2차) |
| --- | --- | --- |
| 방식 | 스냅샷(처음만 전체, 그다음은 바뀐 블록만) + 트랜잭션 로그 | `pg_dump` custom 형식, 매번 DB 전체 |
| 시각 | 매일 03:00~03:30 KST, 트랜잭션 로그는 5분마다 | 매일 03:30 KST (EventBridge 스케줄러 → Fargate 작업) |
| 보관 | 7일 | 30일 (`db_dump_retention_days`), 지운 파일도 7일은 이전 버전으로 남는다 |
| 위치 | RDS 안 | S3 `crates-prod-backups-<계정>` 의 `postgres/YYYY/MM/DD/crates-<UTC시각>.dump` |
| 되돌리는 단위 | 7일 안의 아무 시점(5분 단위), DB 전체 | 그날 03:30 시점, DB 전체 또는 테이블 하나 |
| 복원 시간 | 새 인스턴스를 만드는 데 10~20분 | 덤프 크기에 비례 (296MB에 1분 안쪽) |

둘 다 "그 시점의 DB 전체"를 되살린다. 하루치 변경분만 따로 떠 두는 방식은 쓰지 않는다 — 복원할 때 기준이 되는 전체가 있어야 하고,
변경분 하나만 빠져도 그 뒤가 전부 못 쓰게 된다. RDS 자동 백업이 안에서 변경분(바뀐 블록·트랜잭션 로그)을 쓰는 것은 저장을
아끼려는 것이고, 복원은 언제나 전체 단위다.

### 덤프 작업이 하는 일 (`infra/terraform/prod/templates/db_backup.sh`)

1. Amazon Linux 2023에 PostgreSQL 15 클라이언트와 AWS CLI를 설치한다(서버와 같은 메이저 버전 — 더 새 `pg_dump`로 뜨면 15의 `pg_restore`가 못 읽는다).
2. `pg_dump --format=custom`으로 뜬다. 한 트랜잭션의 스냅샷이라 덤프 중 쓰기가 있어도 일관된다.
3. `pg_restore --list`로 목차를 읽어 테이블 데이터가 있는지 확인한다. 못 읽으면 실패로 끝낸다.
4. S3에 올린다. 객체 메타데이터에 `sha256`, `tables`(테이블 수), `pg-dump`(버전)를 남긴다.
5. CloudWatch 지표 `Crates/Backup / PostgresDumpSucceeded`를 1로 남긴다.

### 알림

`alert_email`(terraform.tfvars)로 메일을 받는다. 처음 적용하면 AWS가 확인 메일을 보내고, 링크를 눌러야 받기 시작한다.

- 작업이 0이 아닌 코드로 끝나면 바로 (EventBridge 규칙 `crates-prod-db-backup-failed`)
- 성공 지표가 26시간 넘게 없으면 (경보 `crates-prod-db-backup-missing`) — 작업이 아예 뜨지 못한 경우도 잡는다

### 주의

- **환경을 끈 동안(`active = false`)은 덤프 스케줄과 경보가 없다.** RDS가 멈춰 있어 바뀌는 데이터가 없고, NAT도 없어 작업이 패키지를 받지 못한다. RDS 자동 백업도 정지 중에는 돌지 않는다.
- RDS 인스턴스를 지우면 마지막 스냅샷(`crates-prod-final`)이 남고, 자동 백업도 보관 기간만큼 남는다(`delete_automated_backups = false`).
- 덤프는 RDS 마스터 계정으로 뜬다. 백업 전용 읽기 계정을 만들면 그 계정으로 바꾼다.

---

## 2. 지금 바로 백업하기

위험한 마이그레이션이나 데이터 수정 직전에:

```bash
infra/terraform/scripts/run-db-backup.sh   # 끝날 때까지 기다려 올린 파일과 종료 코드를 보여 준다
```

RDS 스냅샷이 필요하면(복원이 더 빠르다):

```bash
aws rds create-db-snapshot --db-instance-identifier crates-prod --db-snapshot-identifier crates-prod-before-<작업>
```

---

## 3. 장애별로 무엇을 쓰나

| 상황 | 쓰는 것 | 절 |
| --- | --- | --- |
| 잘못된 배포·쿼리로 데이터가 망가짐(7일 안) | RDS 시점 복구 | 4 |
| 테이블 하나·몇 행만 되살리면 됨 | 덤프에서 그 테이블만 | 5 |
| RDS 인스턴스가 사라짐 | 마지막 스냅샷 또는 남은 자동 백업, 없으면 덤프 | 4, 6 |
| 7일보다 오래전 상태가 필요 | 덤프(30일) | 6 |
| 로컬에서 운영 데이터로 재현 | 덤프 | 7 |

---

## 4. RDS 시점 복구 (새 인스턴스로)

RDS는 기존 인스턴스를 그 자리에서 되돌리지 못한다. 그 시점의 **새 인스턴스**를 만든 뒤 옮겨 탄다.

```bash
cd infra/terraform/prod
SG=$(aws rds describe-db-instances --db-instance-identifier crates-prod \
  --query 'DBInstances[0].VpcSecurityGroups[0].VpcSecurityGroupId' --output text)
PG=$(aws rds describe-db-instances --db-instance-identifier crates-prod \
  --query 'DBInstances[0].DBParameterGroups[0].DBParameterGroupName' --output text)

# 되돌릴 시각(UTC). 복구 가능한 가장 늦은 시각은 describe-db-instances의 LatestRestorableTime
aws rds restore-db-instance-to-point-in-time \
  --source-db-instance-identifier crates-prod \
  --target-db-instance-identifier crates-prod-restore \
  --restore-time 2026-11-09T05:20:00Z \
  --db-subnet-group-name crates-prod --vpc-security-group-ids "$SG" \
  --db-parameter-group-name "$PG" --no-publicly-accessible --no-multi-az
aws rds wait db-instance-available --db-instance-identifier crates-prod-restore
```

- 원본 인스턴스가 지워졌으면 `--source-db-instance-identifier` 대신 남은 자동 백업의 `--source-dbi-resource-id`를 쓴다
  (`aws rds describe-db-instance-automated-backups`). 마지막 스냅샷에서는 `restore-db-instance-from-db-snapshot --db-snapshot-identifier crates-prod-final`.
- 새 인스턴스의 마스터 비밀번호는 원본 비밀(Secrets Manager)과 따로다. 데이터를 확인하려면 `aws rds modify-db-instance --db-instance-identifier crates-prod-restore --manage-master-user-password`로 새 비밀을 받는다.

옮겨 타는 방법은 두 가지다.

- **일부만 되살리면(권장)**: 복구 인스턴스에서 필요한 행만 꺼내(5절처럼 `COPY`) 원본에 넣고, 복구 인스턴스는 지운다. Terraform 상태를 건드리지 않는다.
- **통째로 바꾸면**: 원본 이름을 바꾸고(`crates-prod` → `crates-prod-broken`) 복구 인스턴스를 `crates-prod`로 바꾼다. 엔드포인트 이름이 이름을 따라가므로 backend 설정은 그대로다.
  Terraform은 이 리소스를 내부 ID로 추적하므로 `terraform state rm aws_db_instance.main` 뒤 `terraform import aws_db_instance.main crates-prod`로 새 인스턴스를 다시 연결하고, `terraform plan`이 깨끗한지 확인한다.
  backend를 재시작해 커넥션을 새로 맺는다(`aws ecs update-service --cluster crates-prod --service backend --force-new-deployment`).

---

## 5. 테이블 하나만 되살리기 (덤프)

운영 DB에 바로 `pg_restore --table`을 하면 같은 이름의 테이블과 부딪힌다. 로컬에 복원해서 필요한 행만 꺼낸 뒤 넣는다.

```bash
BUCKET=$(terraform -chdir=infra/terraform/prod output -raw backup_bucket)
aws s3 ls "s3://$BUCKET/postgres/2026/11/09/"
aws s3 cp "s3://$BUCKET/postgres/2026/11/09/crates-20261108T183000Z.dump" ./crates.dump

# 받은 파일이 올린 것과 같은지
aws s3api head-object --bucket "$BUCKET" --key postgres/2026/11/09/crates-20261108T183000Z.dump --query Metadata
shasum -a 256 crates.dump

# 로컬 PostgreSQL 15에 그 테이블만 복원 (스키마 + 데이터)
docker run -d --rm --name restore -e POSTGRES_PASSWORD=restore -e POSTGRES_DB=crates -v "$PWD/crates.dump:/crates.dump" postgres:15
docker exec restore pg_restore -U postgres -d crates --no-owner --no-privileges --table=board_feedback /crates.dump

# 필요한 행만 CSV로 꺼낸다
docker exec restore psql -U postgres -d crates -c "\copy (SELECT * FROM board_feedback WHERE user_id = 42) TO '/tmp/rows.csv' CSV HEADER"
docker cp restore:/tmp/rows.csv ./rows.csv
```

운영에 넣을 때는 `infra/terraform/README.md` 4절의 SSM 포트 포워딩(노트북 15432 → RDS)으로 붙어 `\copy board_feedback FROM 'rows.csv' CSV HEADER`.
외래 키·중복 키에 걸리면 임시 테이블에 먼저 넣고 `INSERT … SELECT … ON CONFLICT DO NOTHING`으로 옮긴다.

---

## 6. 덤프에서 DB 전체를 되살리기

새 RDS(또는 비운 DB)에 복원한다. 포트 포워딩과 비밀번호 꺼내기는 `infra/terraform/README.md` 4절과 같다.

```bash
docker run --rm -v "$PWD/crates.dump:/crates.dump" -e PGPASSWORD="$PGPASS" -e PGSSLMODE=require postgres:15 \
  pg_restore -h host.docker.internal -p 15432 -U crates_admin -d crates --no-owner --no-privileges --jobs=4 /crates.dump
```

- Flyway 이력 테이블도 함께 돌아오므로 backend가 기동할 때 마이그레이션을 다시 돌리지 않는다(검증만 한다).
- 복원 뒤 행 수를 확인한다: `SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM board), (SELECT count(*) FROM board_feedback);`
- 덤프 시점(03:30) 이후의 변경은 없다. 7일 안이면 4절(시점 복구)이 더 최근까지 되돌린다.
- Qdrant(콘텐츠·검색 벡터)는 이 백업에 없다. 사용자 취향 벡터는 PostgreSQL(`user_vector`)에 있어 함께 돌아온다.

---

## 7. 복원 연습 (분기마다 권장)

백업은 복원해 봐야 쓸 수 있는지 안다. 가장 최근 덤프를 로컬에 복원하고 행 수를 본다.

```bash
aws s3 cp "s3://$BUCKET/$(aws s3 ls "s3://$BUCKET/postgres/" --recursive | sort | tail -1 | awk '{print $4}')" ./latest.dump
docker run -d --rm --name restore -e POSTGRES_PASSWORD=restore -e POSTGRES_DB=crates -v "$PWD/latest.dump:/latest.dump" postgres:15
docker exec restore pg_restore -U postgres -d crates --no-owner --no-privileges --jobs=4 /latest.dump
docker exec restore psql -U postgres -d crates -c "SELECT count(*) FROM users; SELECT max(version) FROM flyway_schema_history;"
docker stop restore && rm latest.dump
```

복원한 파일에는 실제 사용자 데이터가 들어 있다. 연습이 끝나면 지우고, 저장소 안에 받지 않는다.
