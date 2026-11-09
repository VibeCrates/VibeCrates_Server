#!/usr/bin/env bash
# 시스템 k6를 덮어쓰지 않는다. 같은 릴리스를 macOS·EC2 ARM/x86에서 내려받아 확장은 스크립트가 고정한다.
set -euo pipefail
cd "$(dirname "$0")/.."
K6_RELEASE=2.3.0
case "$(uname -s)" in Darwin) platform=macos; suffix=zip ;; Linux) platform=linux; suffix=tar.gz ;; *) echo 'macOS/Linux만 지원합니다' >&2; exit 1 ;; esac
case "$(uname -m)" in arm64|aarch64) arch=arm64 ;; x86_64) arch=amd64 ;; *) echo 'ARM64/AMD64만 지원합니다' >&2; exit 1 ;; esac
dest="$PWD/load-test/results/tools"
mkdir -p "$dest"
temporary=$(mktemp -d)
trap 'rm -rf "$temporary"' EXIT
asset="k6-v${K6_RELEASE}-${platform}-${arch}.${suffix}"
release="https://github.com/grafana/k6/releases/download/v${K6_RELEASE}"
curl -fsSL "$release/$asset" -o "$temporary/$asset"
curl -fsSL "$release/k6-v${K6_RELEASE}-checksums.txt" -o "$temporary/checksums.txt"
python3 - "$temporary" "$asset" <<'PY'
import hashlib, pathlib, sys
p, asset = pathlib.Path(sys.argv[1]), sys.argv[2]
expected = next(line.split()[0] for line in (p/'checksums.txt').read_text().splitlines() if line.split()[-1].lstrip('*') == asset)
if hashlib.sha256((p/asset).read_bytes()).hexdigest() != expected:
    raise SystemExit('k6 공식 체크섬 불일치')
PY
if [ "$suffix" = zip ]; then unzip -q "$temporary/$asset" -d "$temporary"; else tar -xzf "$temporary/$asset" -C "$temporary"; fi
cp "$temporary/k6-v${K6_RELEASE}-${platform}-${arch}/k6" "$dest/k6"
chmod +x "$dest/k6"
"$dest/k6" version
K6_AUTO_EXTENSION_RESOLUTION=true "$dest/k6" run --no-usage-report load-test/scenarios/aws-redis-smoke.js
echo "설치 완료: K6_BIN=$dest/k6 (xk6-redis v0.4.5는 스크립트 지시문으로 고정)"
