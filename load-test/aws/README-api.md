# AWS 개별 API 측정

`api_sweep.py`는 `RATE`를 해당 API의 초당 요청 수로 해석한다. 화면 실행기의
계정 분리, 원본 예약 장부, 추천 잠금/강제 미스, 검색어 조합, 저장 payload를 공유한다.

실행 예:

```sh
RESOURCE_MODE=aws python3 load-test/aws/api_sweep.py home --rate 10 --seconds 60 \
  --output /private/results/on-home/step-1-rate-10 --prepared /private/prepared-api-on
```

AWS 접속 주소와 DB 인증은 프로세스 환경으로 제공한다. 비밀번호나 AWS 자격을
파일에 쓰지 않는다. 토큰을 포함하는 준비/결과 파일은 저장소 밖 권한 600으로 둔다.
처음에 화면 풀 추천 캐시를 예열하므로 이 준비 시간은 측정에 포함되지 않는다.

전체 스윕 제어기는 10부터 두 배씩 올리며 단계마다 20초 워밍업 후 60초를 측정한다.
포화 주변 앞 단계/중간/최초 포화 세 지점을 한 번씩 추가 측정한다.
각 단계 전에 SSO 자격 만료를 검사하고 40분 정리 예산에 닿으면 AWS 종료를 우선한다.
완료한 단계의 `result.json`은 재실행하지 않으며 API마다 외부 `status.json`을 갱신한다.

실패 5% 이상, drop, 완료율 90% 미만 또는 ECS unhealthy 교체가 포화다.
작업 교체는 완성한 HTTP 관측을 무효화하지 않는다. 기존 작업의 마지막 카운터와
교체 이벤트를 기록하고 다음 단계 전에 healthy를 기다린다.
캐시 off는 기준 정의에서 캐시 스위치 둘만 바꾸며 끝나면 기준 cache on으로 복귀한다.

읽기 후 쓰기 순서를 지킨다. 각 쓰기 API 시작 전에 복원 기준 대비 행 증가량을 기록한다.
좋아요는 취소하지 않고 (사용자,보드) 쌍을 예약한다. 가입 취향 저장은 한 계정당 한 번만
요청하며 신규 계정 예산은 최대 100000명이다. 예산 부족은 서버 포화와 구분한다.

로컬 검사: `python3 -m unittest discover -s load-test/aws -p 'test*.py'`.
Amazon Linux에서 같은 검사를 실행하려면 `install-api-test-deps.sh`로 Node.js도 설치한다.
초기 설치 실패는 수리 가능한 준비 오류로 처리하고 즉시 임시 생성기를 삭제하지 않는다.
전체 중단과 자격 시한에는 결과 보존 후 태그 임시 리소스 정리, 검토한 active=false 적용,
RDS 정지, ElastiCache 완전 삭제 확인을 반드시 수행한다.
