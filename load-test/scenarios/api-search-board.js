// 검색 보드. AI 서버 임베딩(Tailscale 너머) → Qdrant 콘텐츠 검색 → 같은 제목·구성의 저장된 검색 보드 조회 → 좋아요 토큰 서명.
// 제목은 검색어 그대로라 query 매칭은 없다. 처리율이 막히는데 backend CPU가 낮으면 우리 서버가 아니라 AI 서버가
// 먼저 찬 것이다 — 결과를 해석할 때 구분할 것.
//
// 목표 p95 1.5초는 AI 서버 단독 왕복이 0.2~0.4초이던 때(이전 서버) 정한 값이다. 2026-10-27에 바꾼 서버는 단독 약 30ms라
// 이번 측정 결과를 보고 다시 정한다.
//
// 캐시(검색어 임베딩)를 끄고 잰다(CACHE_ENABLED=false). 검색어는 조합으로 만든다(lib/search-keywords.js).
// SEARCH_KEYWORD_MODE=unique(기본, 거의 안 겹침) | zipf(인기 검색어 몰림 — 캐시 효과는 mixed에서 본다).
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { nextSearchKeyword } from '../lib/search-keywords.js';
import { searchBoard } from '../lib/api.js';

export const options = arrival({ p95: 1500 });

export default function () {
  record(searchBoard(pick(), nextSearchKeyword()));
}
