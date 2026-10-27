// 홈 추천 보드 4개. 취향 벡터 조회 → Qdrant 제목 후보 200개(벡터 포함) → MMR로 서로 다른 제목 4개 →
// 보드 4장 × 종류 3개의 콘텐츠 묶음 검색(Qdrant 한 번) → 보드마다 같은 구성의 저장된 AI 보드 조회.
//
// 캐시를 끄고 잰다(CACHE_ENABLED=false로 띄운 backend, run-sweep.sh가 확인한다). 켜 두면 계정마다 첫 요청 뒤로는
// Redis에서 나가 추천 계산의 비용이 보이지 않는다. 캐시를 거치는 모습은 mixed로 본다.
//
//   RATE=20 k6 run load-test/scenarios/api-recommend.js      (보통은 run-sweep.sh로)
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { recommendation } from '../lib/api.js';

export const options = arrival({ p95: 500 });

export default function () {
  record(recommendation(pick()));
}
