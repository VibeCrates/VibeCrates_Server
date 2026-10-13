// 실제 화면 흐름의 비율로 API를 섞는다. RATE는 전체 요청 수(/s)이고 엔드포인트마다 비율만큼 나눠 받는다.
//
//   ./load-test/run-sweep.sh mixed 25 50 75 100
//
// 비율의 근거 — 운영 로그가 없어 화면 흐름에서 가정했다. 바꾸면 결과도 달라지므로 SHARES만 고친다.
//   홈 진입 한 번에 추천·인기·내 보드 3개가 같이 나간다           → 각 14% (합 42%)
//   보드를 열어 콘텐츠 상세를 본다                                → 16%
//   보관함 페이지(20건)                                          → 8%
//   보드 수정 화면의 콘텐츠 제목 검색                              → 8%
//   검색 보드(AI 서버 경유)                                       → 6%
//   좋아요·취소(요청 2개 = 한 쌍)                                 → 20% (쌍으로는 10%)
//
// 판정 — 엔드포인트마다 목표 p95를 따로 건다. 검색 보드만 1.5초, 나머지는 500ms.
// 하나라도 넘으면 그 단계는 실패다. 엔드포인트별 값은 run-sweep.sh가 rateN-endpoints.txt로 남긴다.
import { record } from '../lib/metrics.js';
import { anyToken, pick } from '../lib/users.js';
import { KEYWORDS } from '../lib/keywords.js';
// 검색 보드만 조합 검색어를 쓴다(SEARCH_KEYWORD_MODE). 콘텐츠 제목 검색은 keywords.js 그대로다.
import { nextSearchKeyword } from '../lib/search-keywords.js';
// 시나리오 함수 이름(popular, archive)과 겹치지 않게 api로 묶어 가져온다. k6의 exec는 내보낸 함수 이름을 찾는다.
import * as api from '../lib/api.js';

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const MAX_CONTENT_ID = api.MAX_CONTENT_ID;
// 보드 id 범위는 run-sweep.sh가 DB에서 읽어 넘긴다. prepare.sh로 다시 만들면 시퀀스가 이어져 1부터 시작하지 않는다.
const MIN_BOARD_ID = Number(__ENV.MIN_BOARD_ID || 1);
const MAX_BOARD_ID = Number(__ENV.MAX_BOARD_ID || 250800);
const LONG_KEYWORDS = KEYWORDS.filter((k) => [...k].length >= 3);

// 이름: [요청 비율, 목표 p95(ms), 한 번에 보내는 요청 수]
const SHARES = {
  recommend:       [0.14, 500, 1],
  popular:         [0.14, 500, 1],
  my_boards:       [0.14, 500, 1],
  content_detail:  [0.16, 500, 1],
  archive:         [0.08, 500, 1],
  content_search:  [0.08, 500, 1],
  search_board:    [0.06, 1500, 1],
  like_toggle:     [0.20, 500, 2],
};

// AI 서버가 꺼져 있으면 SKIP_AI=1로 검색 보드를 뺀다. 나머지 비율을 합이 1이 되게 늘려 전체 RATE는 그대로 둔다.
// (2026-10-04 AI 서버가 오프라인이라 이렇게 쟀다. 검색 보드의 몫은 단독 측정 api-search-board로 따로 본다.)
if (__ENV.SKIP_AI === '1') delete SHARES.search_board;
const SHARE_SUM = Object.values(SHARES).reduce((sum, [share]) => sum + share, 0);

function scenario(name, [rawShare, , requests]) {
  const share = rawShare / SHARE_SUM;
  // 도착률은 소수가 안 되므로 1분 단위로 맞춘다. 0.6/s는 36/min이다.
  const perMinute = Math.max(1, Math.round((RATE * share / requests) * 60));
  return {
    executor: 'constant-arrival-rate',
    exec: name,
    rate: perMinute,
    timeUnit: '1m',
    duration: DURATION,
    preAllocatedVUs: Math.max(5, Math.ceil(perMinute / 60) * 2),
    maxVUs: Math.max(30, Math.ceil(perMinute / 60) * 10),
    gracefulStop: '10s',
  };
}

const thresholds = { api_failed: ['rate<0.01'] };
for (const [name, [, p95]] of Object.entries(SHARES)) {
  thresholds[`api_duration{name:${name}}`] = [`p(95)<${p95}`];
}

export const options = {
  scenarios: Object.fromEntries(Object.entries(SHARES).map(([n, s]) => [n, scenario(n, s)])),
  thresholds,
  setupTimeout: '180s',
  // count가 있어야 run-sweep.sh가 엔드포인트별 요청 수를 적는다.
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // 콘텐츠 상세는 경로에 종류가 들어가서 미리 알아 둔다(api-content-detail과 같은 방식).
  const contents = [];
  for (let i = 0; i < 300; i++) {
    const id = 1 + Math.floor(Math.random() * MAX_CONTENT_ID);
    const res = api.contentSummary(anyToken(), id);
    if (res.status === 200) contents.push({ id, dtype: res.json('data.contentType') });
  }
  return { contents };
}

const tag = (name) => ({ name });

export function recommend() { record(api.recommendation(pick()), tag('recommend')); }
export function popular() { record(api.popular(pick()), tag('popular')); }
export function my_boards() { record(api.homeMyBoards(pick()), tag('my_boards')); }
export function archive() { record(api.archive(pick(), 'ALL', 0, 20), tag('archive')); }
export function content_search() {
  const k = LONG_KEYWORDS[Math.floor(Math.random() * LONG_KEYWORDS.length)];
  record(api.searchContents(pick(), k), tag('content_search'));
}
export function search_board() { record(api.searchBoard(pick(), nextSearchKeyword()), tag('search_board')); }
export function content_detail({ contents }) {
  const c = contents[Math.floor(Math.random() * contents.length)];
  record(api.contentDetail(pick(), c.dtype, c.id), tag('content_detail'));
}
export function like_toggle() {
  const token = pick();
  const boardId = MIN_BOARD_ID + Math.floor(Math.random() * (MAX_BOARD_ID - MIN_BOARD_ID + 1));
  record(api.likeBoard(token, boardId), tag('like_toggle'));
  record(api.unlikeBoard(token, boardId), tag('like_toggle'));
}
