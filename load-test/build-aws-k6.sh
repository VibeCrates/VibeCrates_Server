#!/usr/bin/env bash
# 자동 해석을 쓸 수 없는 망의 대안. 호스트에 Go를 설치하지 않고 고정한 Docker Go로 교차 빌드한다.
set -euo pipefail
cd "$(dirname "$0")/.."
target_os="${1:?darwin 또는 linux가 필요합니다}"
target_arch="${2:?arm64 또는 amd64가 필요합니다}"
case "$target_os/$target_arch" in darwin/arm64|darwin/amd64|linux/arm64|linux/amd64) ;; *) exit 1 ;; esac
mkdir -p load-test/results/tools
docker run --rm -v "$PWD/load-test/results/tools:/out" golang:1.26.5 \
  sh -eu -c 'go install go.k6.io/xk6/cmd/xk6@v1.4.5
    GOOS="$1" GOARCH="$2" CGO_ENABLED=0 xk6 build v2.3.0 \
      --with github.com/grafana/xk6-redis@v0.4.5 --output "/out/k6-$1-$2"' builder "$target_os" "$target_arch"
