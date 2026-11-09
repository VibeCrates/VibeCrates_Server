#!/bin/sh
# ECS Exec는 앱의 localhost를 읽는다. JWT는 컨테이너 안에서만 만들고 출력·명령 인자로 내보내지 않는다.
# 한 세션에서 로그인 한 번만 하여 2초 샘플마다 BCrypt가 서버 CPU를 쓰지 않게 한다.
set -eu
token=$(curl -fsS -X POST http://localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"loginId":"loadtest_u0","pwd":"Loadtest!234"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[ -n "$token" ] || exit 1
while :; do
  echo "AWS_SAMPLE $(date +%s)"
  curl -fsS -H "Authorization: Bearer $token" http://localhost:8080/actuator/prometheus |
    sed -n '/^crates_recommendation_cache_total/p; /^crates_ai_embedding_/p; /^crates_user_vector_/p; /^hikaricp_connections_/p; /^process_cpu_usage/p; /^jvm_memory_used_bytes/p'
  echo AWS_SAMPLE_END
  [ "${1:-loop}" = once ] && break
  sleep 2
done
