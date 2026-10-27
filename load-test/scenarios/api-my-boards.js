// 홈 "내 보드" 8개 = 보관함 ALL 첫 페이지. 만든 보드와 좋아요한 보드를 UNION ALL로 합친다.
// 계정마다 좋아요 수(가입 시기)가 달라 비용이 다르다 — 1개월차 가입자는 좋아요 720 / 만든 보드 180.
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { homeMyBoards } from '../lib/api.js';

export const options = arrival({ p95: 500 });

export default function () {
  record(homeMyBoards(pick()));
}
