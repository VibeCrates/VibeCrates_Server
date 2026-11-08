#!/usr/bin/env bash
# Amazon Linux 생성기의 Python 단위 검사는 payload 검증에 Node.js도 쓴다.
set -euo pipefail
dnf install -y nodejs
node --version
