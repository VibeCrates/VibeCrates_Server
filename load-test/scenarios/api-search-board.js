// 검색 보드. AI 서버 임베딩(Tailscale 너머, 단독 0.2~0.4초) → Qdrant 콘텐츠 검색 → 제목 query 매칭.
// 목표 p95가 다른 API보다 긴 이유는 AI 서버 왕복이 들어 있어서다. 처리율이 막히는데 backend CPU가 낮으면
// 우리 서버가 아니라 AI 서버가 먼저 찬 것이다 — 결과를 해석할 때 구분할 것.
//
// 검색어는 조합으로 만든다(lib/search-keywords.js). SEARCH_KEYWORD_MODE=unique(기본, 거의 안 겹침) | zipf(인기 검색어 몰림).
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { nextSearchKeyword } from '../lib/search-keywords.js';
import { searchBoard } from '../lib/api.js';

export const options = arrival({ p95: 1500 });

export default function () {
  record(searchBoard(pick(), nextSearchKeyword()));
}
