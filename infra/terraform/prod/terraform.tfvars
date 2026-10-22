# 비밀이 아닌 설정값. 비밀값은 SSM Parameter Store에 둔다(README).

# Route 53에서 산 도메인. 비어 있으면 인증서·ALB·ECS 서비스·CloudFront·DNS를 만들지 않는다.
domain_name = ""

# AI 서버(위치 미정). 지금은 Tailscale 주소다.
ai_server_base_url      = "http://100.77.133.40:8000"
tailscale_relay_enabled = true

# 환경 켜기·끄기(active)는 scripts/env-up.sh·env-down.sh가 env.auto.tfvars에 적는다.
