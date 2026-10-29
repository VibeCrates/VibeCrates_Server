// 화면 진입 한 번을 한 반복으로 잰다. RATE는 초당 화면 진입 수이고, 좋아요→취소는 따로 더한다.
//
//   ./load-test/run-sweep.sh screens 5 10 25 50
//
// mixed.js의 요청 비율을 화면 수로 바꾼다. 홈의 각 14%는 같은 진입에서 함께 나가므로 42 / 3 = 14다.
//   화면                 기존 요청 몫       진입 가중치    화면 비율       진입당 요청(공통 가드 포함)
//   home                 42% (14% × 3)     14             14/52 = 26.92%  4
//   content_detail       16%               16             16/52 = 30.77%  1
//   archive               8%                8              8/52 = 15.38%  1
//   content_search        8%                8              8/52 = 15.38%  1
//   search_results        6%                6              6/52 = 11.54%  1
//   like_toggle          20%               화면 비율 밖     11.75쌍/52진입  2 (좋아요 뒤 취소)
//
// 공통 가드(onboarding 상태)는 앱을 처음 열 때만 나간다. 프론트가 이 상태를 Infinity로 캐시해 같은 세션에서
// 보관함·검색 결과로 옮겨 가면 다시 묻지 않는다. 그래서 첫 화면인 홈 진입만 가드 → 본문 순서로 잰다(앱을 막 연
// 사용자). 보관함·검색 결과는 앱 안에서 이동해 온 진입이다. 헤더 자체의 조회는 없다.
// 콘텐츠 상세·제목 검색은 이미 열린 보드 안의 패널이다. 상세는 요약의 id·종류가 있고, 제목 검색은
// 3글자 이상 입력해 결과가 열리는 시점부터 잰다. 검색 결과 페이지의 검색 보드와 함께 나가는 요청이 아니다.
// 온보딩은 신규 가입 화면이고, 보드 확대는 추가 API가 없다. 기존 계정의 mixed 비율에는 넣지 않는다.
// 로그인·세션 복원은 tokens.sh에서 준비한 유효 토큰으로 대신한다. 콘텐츠 요약은 setup에서만 조회한다.
//
// 판정 — API별 p95와 진입부터 마지막 응답까지의 screen_duration을 함께 본다. 검색 결과는 1.5초, 나머지는 500ms다.
import http from 'k6/http';
import { fail } from 'k6';
import { Trend } from 'k6/metrics';
import { record, apiFailed } from '../lib/metrics.js';
import { anyToken, pick } from '../lib/users.js';
import { pickBoardId } from '../lib/boards.js';
import { KEYWORDS } from '../lib/keywords.js';
import { nextSearchKeyword } from '../lib/search-keywords.js';
import * as api from '../lib/api.js';

const RATE = Number(__ENV.RATE || 50);
const DURATION = __ENV.DURATION || '60s';
const LONG_KEYWORDS = KEYWORDS.filter((k) => [...k].length >= 3);
const screenDuration = new Trend('screen_duration', true);

// 이름: [진입 가중치, 화면 목표 p95(ms), 기록할 API 이름]. 공통 가드는 API 목표 500ms로 따로 판정한다.
const SHARES = {
  home:           [0.14, 500, ['onboarding_status', 'recommend', 'popular', 'my_boards']],
  content_detail: [0.16, 500, ['content_detail']],
  archive:        [0.08, 500, ['archive']],
  content_search: [0.08, 500, ['content_search']],
  search_results: [0.06, 1500, ['search_board']],
};

// mixed처럼 AI 서버가 꺼져 있으면 검색 보드를 빼고, 남은 화면의 가중치를 다시 합이 1이 되게 늘린다.
if (__ENV.SKIP_AI === '1') delete SHARES.search_results;
const SHARE_SUM = Object.values(SHARES).reduce((sum, [share]) => sum + share, 0);
const READ_REQUESTS = Object.values(SHARES).reduce((sum, [share, , names]) => sum + share * names.length, 0);
const ORIGINAL_READ_REQUESTS = Object.values(SHARES).reduce(
  (sum, [share, , names]) => sum + share * names.filter((name) => name !== 'onboarding_status').length, 0,
);

// 52진입 = 기존 읽기 80건 + 홈 가드 14건 = 94건. 쓰기 20%는 94 × (20/80) = 23.5건 = 11.75쌍이다.
// 그래서 행동 도착률은 RATE × 11.75/52이며 RATE의 화면 몫을 깎지 않는다. SKIP_AI=1일 때는 mixed와 같이
// 원래 읽기 74 : 쓰기 20을 유지한다. 가드 포함 88건 × (20/74) / 2 = 11.892쌍 / 46진입(쓰기 21.28%)이다.
const LIKE_PAIRS_PER_SCREEN = READ_REQUESTS * (0.20 / ORIGINAL_READ_REQUESTS) / 2 / SHARE_SUM;

function scenario(name, perSecond) {
  // 도착률은 소수가 안 되므로 mixed처럼 1분 단위로 맞춘다. 낮은 RATE에서는 반올림 오차가 남는다.
  const perMinute = Math.max(1, Math.round(perSecond * 60));
  return {
    executor: 'constant-arrival-rate',
    exec: name,
    rate: perMinute,
    timeUnit: '1m',
    duration: DURATION,
    // 캐시가 없는 홈 진입이 몇 초 걸려도 VU를 뒤늦게 늘리느라 도착을 놓치지 않게 한다(lib/options.js와 같은 하한).
    preAllocatedVUs: Math.max(10, Math.ceil(perMinute / 60) * 2),
    maxVUs: Math.max(30, Math.ceil(perMinute / 60) * 10),
    gracefulStop: '10s',
  };
}

const thresholds = { api_failed: ['rate<0.01'] };
for (const [screen, [, p95, names]] of Object.entries(SHARES)) {
  thresholds[`screen_duration{screen:${screen}}`] = [`p(95)<${p95}`];
  for (const name of names) thresholds[`api_duration{name:${name}}`] = [`p(95)<${name === 'search_board' ? 1500 : 500}`];
}
for (const name of ['like', 'unlike']) thresholds[`api_duration{name:${name}}`] = ['p(95)<500'];

export const options = {
  scenarios: {
    ...Object.fromEntries(Object.entries(SHARES).map(([name, [share]]) => [name, scenario(name, RATE * share / SHARE_SUM)])),
    like_toggle: scenario('like_toggle', RATE * LIKE_PAIRS_PER_SCREEN),
  },
  thresholds,
  setupTimeout: '180s',
  // count는 run-sweep.sh가 화면 진입 수와 엔드포인트별 요청 수를 나눠 세는 데 쓴다.
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  // mixed와 같이 id·종류를 미리 알아 둔다. 패널 진입 때는 부모 보드 응답에 이미 있는 정보다.
  const contents = [];
  for (let i = 0; i < 300; i++) {
    const id = 1 + Math.floor(Math.random() * api.MAX_CONTENT_ID);
    const res = api.contentSummary(anyToken(), id);
    if (res.status === 200) contents.push({ id, dtype: res.json('data.contentType') });
  }
  if (!contents.length) throw new Error('콘텐츠 상세에 쓸 콘텐츠가 없습니다. 시딩 상태를 확인하세요.');
  return { contents };
}

const tag = (name) => ({ name });

function enter(screen, visit) {
  const started = Date.now();
  const token = pick();
  try {
    visit(token);
  } finally {
    // batch의 응답 시간을 더하지 않는다. 병렬 요청 중 마지막 응답까지 실제로 기다린 시간이다.
    screenDuration.add(Date.now() - started, { screen });
  }
}

function ready(token) {
  const res = record(api.onboardingStatus(token), tag('onboarding_status'));
  if (res.status !== 200) return false;
  if (!res.json('data.initialContentsSelected')) {
    // 200이어도 홈을 열 수 없는 계정이면 성공한 화면 진입으로 보지 않는다.
    apiFailed.add(true, tag('onboarding_status'));
    fail('온보딩을 마친 부하테스트 계정이 필요합니다.');
  }
  return true;
}

export function home() {
  enter('home', (token) => {
    if (!ready(token)) return;
    const responses = http.batch(api.homeRequests(token));
    for (const [name, res] of Object.entries(responses)) record(res, tag(name));
  });
}

export function archive() {
  enter('archive', (token) => {
    record(api.archive(token, 'ALL', 0, 20), tag('archive'));
  });
}

export function content_detail({ contents }) {
  enter('content_detail', (token) => {
    const c = contents[Math.floor(Math.random() * contents.length)];
    record(api.contentDetail(token, c.dtype, c.id), tag('content_detail'));
  });
}

export function content_search() {
  enter('content_search', (token) => {
    const keyword = LONG_KEYWORDS[Math.floor(Math.random() * LONG_KEYWORDS.length)];
    record(api.searchContents(token, keyword), tag('content_search'));
  });
}

export function search_results() {
  enter('search_results', (token) => {
    record(api.searchBoard(token, nextSearchKeyword()), tag('search_board'));
  });
}

export function like_toggle() {
  const token = pick();
  const boardId = pickBoardId();
  record(api.likeBoard(token, boardId), tag('like'));
  record(api.unlikeBoard(token, boardId), tag('unlike'));
}
