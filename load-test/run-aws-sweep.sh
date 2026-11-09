#!/usr/bin/env bash
# AWS를 생성하거나 배포하지 않는다. 로컬/이미 실행 중인 환경에 화면 도착률만 건다.
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
if [ "${RESOURCE_MODE:-local}" = local ]; then
  set -a; . ./crates_server.env; set +a
  # health만 보고 시작하면 Qdrant 인덱싱과 부하가 CPU를 다툰다. 기존 실행기와 같은 준비 판정을 쓴다.
  . load-test/lib/common.sh
  wait_ready
fi
export PYTHONDONTWRITEBYTECODE=1
exec python3 load-test/aws/sweep.py "$@"
