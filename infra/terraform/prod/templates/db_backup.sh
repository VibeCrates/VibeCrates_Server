#!/usr/bin/env bash
# 매일 PostgreSQL 전체를 논리 덤프(pg_dump custom 형식)로 떠서 백업 버킷에 올린다(db_backup.tf).
#
# RDS 자동 백업(스냅샷 + 5분 단위 시점 복구, 7일)이 1차 복구 수단이다. 이 덤프는 그 다음 수단이다.
#   - RDS 밖의 파일이라 RDS 인스턴스를 지워도 남고, 다른 PostgreSQL(로컬 포함)로 옮길 수 있다.
#   - 테이블 하나만 골라 되살릴 수 있다(pg_restore --table).
# 복원 방법은 docs/db-backup.md.
#
# 접속 정보는 libpq 환경 변수(PGHOST·PGPORT·PGDATABASE·PGUSER·PGPASSWORD·PGSSLMODE)로 받는다.
# 올릴 곳은 BACKUP_BUCKET, 성공 지표의 차원은 DB_INSTANCE.
set -euo pipefail

log() { echo "[db-backup] $*"; }

# 서버와 같은 메이저 버전(15)의 클라이언트를 쓴다. 더 새 pg_dump로 뜬 custom 덤프는 15의 pg_restore가 못 읽는다.
dnf install -y -q postgresql15 awscli-2 >/dev/null

stamp=$(date -u +%Y%m%dT%H%M%SZ)
key="postgres/$(date -u +%Y/%m/%d)/${PGDATABASE}-${stamp}.dump"
file=$(mktemp /tmp/backup-XXXXXX.dump)

log "pg_dump 시작: ${PGHOST}/${PGDATABASE}"
started=$(date +%s)
# custom 형식: 압축되고, pg_restore로 테이블을 골라 복원할 수 있다. 한 트랜잭션의 스냅샷이라 덤프 중 쓰기가 있어도 일관된다.
pg_dump --format=custom --compress=6 --file="$file"
seconds=$(( $(date +%s) - started ))

# 올리기 전에 덤프가 온전한지 본다. 목차를 읽지 못하면(잘린 파일 등) 실패로 끝낸다.
tables=$(pg_restore --list "$file" | grep -c ' TABLE DATA ' || true)
if [ "$tables" -eq 0 ]; then
  log "덤프에 테이블 데이터가 없다 — 실패로 처리"
  exit 1
fi
bytes=$(stat -c %s "$file")
sha=$(sha256sum "$file" | cut -d ' ' -f 1)

aws s3 cp "$file" "s3://${BACKUP_BUCKET}/${key}" --only-show-errors \
  --metadata "sha256=${sha},tables=${tables},pg-dump=$(pg_dump --version | awk '{print $3}')"
rm -f "$file"

# 경보(db_backup.tf)는 이 지표가 하루 넘게 없으면 울린다 — 작업이 실패했든 아예 안 돌았든.
aws cloudwatch put-metric-data --namespace Crates/Backup --metric-name PostgresDumpSucceeded \
  --dimensions "DBInstance=${DB_INSTANCE}" --value 1 --unit Count

log "완료: s3://${BACKUP_BUCKET}/${key} (${bytes} bytes, 테이블 ${tables}개, ${seconds}초, sha256 ${sha})"
