#!/usr/bin/env bash
# 기존 prepare.sh를 바꾸지 않고 신규 계정·실행 예산·토큰을 별도로 준비한다.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [ "${RESOURCE_MODE:-local}" = local ]; then set -a; . ./crates_server.env; set +a; fi
export PYTHONDONTWRITEBYTECODE=1
exec python3 load-test/aws/prepare.py "$@"
