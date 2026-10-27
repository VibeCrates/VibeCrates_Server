// 모든 시나리오가 공유하는 k6 옵션.
//
// 워밍업은 이 파일이 아니라 실행 스크립트(run-measure.sh)가 담당한다. k6를 한 번 버리고
// 한 번 측정하는 방식이다. 한 프로세스 안에서 두 구간을 나누는 방법도 있지만,
// shared-iterations는 끝나는 시각을 알 수 없어 measure 구간의 startTime을 정할 수 없다.
// (추정치를 넣으면 두 구간이 겹쳐 측정 초반이 다시 차가워진다.)
//
// 워밍업이 왜 필요한가 — 같은 조건 7회 반복 실측:
//
//   1회 104.0 TPS / 2회 154.9 / 3회 173.2 / 4회 197.2 / 5회 197.3 / 6회 206.7 / 7회 209.7
//
// 첫 회가 정상 상태보다 50% 느리다. 4회차부터 수렴하고 그 뒤 변동은 ±6%다.
// 앱을 재시작하면 다시 식는다(재시작 직후 113.3 TPS). JVM JIT 워밍업이라 DB를 비워도 안 풀린다.
//
// 이걸 모르고 1회 측정으로 비교하면 워밍업 차이를 개선 효과로 잘못 읽는다. 실제로 그랬다.

/**
 * 처리율은 http_reqs가 아니라 iterations로 본다.
 *
 * http_reqs에는 setup()의 회원가입·로그인이 섞인다. 실측: iterations 200건인 실행에서
 * http_reqs는 220건이고 rate가 134.6 vs 122.4로 10% 부풀려진다.
 * setup에 걸린 시간도 분모에 들어간다.
 */
export const RATE_METRIC = 'iterations';

/**
 * @param vus         동시 사용자 수
 * @param iterations  측정할 반복 수
 * @param p95         p95 상한(ms). 넘으면 threshold 실패로 드러난다
 */
export function standard({ vus, iterations, p95 = 2000 }) {
  return {
    scenarios: {
      main: {
        executor: 'shared-iterations',   // 총량 고정. 실행 간 비교가 성립하려면 일감이 같아야 한다.
        vus,
        iterations,
        maxDuration: '10m',
        gracefulStop: '10s',
      },
    },
    thresholds: {
      // 실패가 섞이면 처리율 비교가 무의미해진다. 먼저 막는다.
      checks: ['rate==1.0'],
      http_req_duration: [`p(95)<${p95}`],
    },
    summaryTrendStats: ['avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  };
}

/**
 * 도착률 고정 프로파일. 자원 상한을 찾는 측정(run-sweep.sh)이 쓴다.
 *
 * standard()와 무엇이 다른가 — standard는 VU 수를 고정해 "주어진 일감을 얼마나 빨리 끝내나"를 잰다(닫힌 모델).
 * 서버가 느려지면 요청도 같이 줄어 느려짐이 처리율 숫자 뒤에 숨는다. 여기서는 초당 RATE건이 서버 상태와
 * 상관없이 도착한다(열린 모델). 실제 사용자는 서버가 느리다고 덜 오지 않으므로, "이 사양이 초당 N건을
 * 목표 시간 안에 받아내는가"는 이 모델로만 답할 수 있다.
 *
 * RATE·DURATION은 환경변수로 받는다. run-sweep.sh가 단계마다 RATE를 바꿔 k6를 다시 띄운다.
 *
 * VU가 모자라 제때 시작하지 못한 요청은 dropped_iterations로 센다. 0이 아니면 그 단계의 도착률은
 * 지켜지지 않은 것이다 — 서버가 느려 VU가 다 묶였거나(maxVUs 도달), k6 자신이 모자란 것이다.
 *
 * 응답 시간은 http_req_duration이 아니라 lib/metrics.js의 api_duration으로 본다. setup에서 준비용으로
 * 보내는 요청(api-content-detail의 콘텐츠 요약 300번 등)이 http_req_duration에 섞이기 때문이다.
 *
 * 반복 한 번에 요청을 여러 개 보내는 시나리오(좋아요→취소)는 targets로 요청마다 목표를 따로 건다.
 * 요청을 record(res, { name })로 기록하면 run-sweep.sh가 이름별 p95를 따로 판정한다. 도착률(RATE)은 반복 수다.
 *
 * @param p95      목표 p95(ms). 넘으면 threshold 실패로 기록된다
 * @param targets  { 이름: 목표 p95(ms) } — 요청마다 따로 판정할 때
 */
export function arrival({ p95, targets }) {
  const rate = Number(__ENV.RATE || 10);
  return {
    scenarios: {
      main: {
        executor: 'constant-arrival-rate',
        rate,
        timeUnit: '1s',
        duration: __ENV.DURATION || '60s',
        preAllocatedVUs: Math.max(10, rate * 2),
        // 응답이 1초까지 늘어도 도착률을 지킬 만큼. 이걸 넘으면 서버가 이미 목표를 한참 넘긴 상태다.
        maxVUs: Math.max(50, rate * 10),
        gracefulStop: '10s',
      },
    },
    thresholds: {
      ...(targets
        ? Object.fromEntries(Object.entries(targets).map(([name, ms]) => [`api_duration{name:${name}}`, [`p(95)<${ms}`]]))
        : { api_duration: [`p(95)<${p95}`] }),
      api_failed: ['rate<0.01'],
    },
    setupTimeout: '180s',
    // count가 있어야 run-sweep.sh가 이름별 요청 수를 적는다.
    summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
  };
}
