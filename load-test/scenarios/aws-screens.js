"use k6 = 2.3.0";
"use k6 with k6/x/redis = 0.4.5";
// RATE는 화면 진입 수다. 화면 안 행동은 shares.json만큼 별도 도착하며 화면 몫을 깎지 않는다.
// 기존 screens/mixed는 이전 측정 재현용이어서 이 파일에서 계약·계정 풀을 따로 조립한다.
import http from 'k6/http';
import redis from 'k6/x/redis';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter, Rate, Trend } from 'k6/metrics';
import { sleep } from 'k6';
import * as api from '../lib/api.js';
import { apiDuration, apiFailed } from '../lib/metrics.js';
import { KEYWORDS } from '../lib/keywords.js';

const apiOnly = __ENV.AWS_API_ONLY || '';
const shares = apiOnly ? { [apiOnly]: 1 } : JSON.parse(open('../aws/shares.json'));
if (__ENV.SKIP_AI === '1') { delete shares.search_results; delete shares.search_like; }
const tasks = Object.fromEntries(Object.keys(shares).map((name) => [name,
  new SharedArray(`aws-${name}`, () => JSON.parse(open(__ENV.AWS_STAGE_FILE)).tasks[name]),
]));
const screens = new SharedArray('aws-screen-users', () => JSON.parse(open(__ENV.AWS_STAGE_FILE)).screens);
const contents = new SharedArray('aws-contents', () => JSON.parse(open(__ENV.AWS_STAGE_FILE)).contents);
const runId = __ENV.AWS_RUN_ID;
const client = new redis.Client(__ENV.REDIS_URL || 'redis://localhost:6379/0');
// SET NX의 nil 응답은 xk6-redis에서 예외다. 잠금 경합은 정수 0으로 돌려받는다.
const acquireLock = "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) and 1 or 0";
const screenDuration = new Trend('screen_duration', true);
const started = new Counter('aws_started');
const completed = new Counter('aws_completed');
const onTime = new Counter('aws_completed_on_time');
const apiOnTime = new Counter('aws_api_completed_on_time');
const controlDuration = new Trend('cache_control_duration', true);
const forcedMiss = new Rate('home_forced_miss');
const errors = new Counter('workload_errors');
const lockTimeouts = new Counter('home_lock_timeouts');

const screenNames = apiOnly ? [] : ['home', 'search_results', 'archive', 'onboarding'];
const names = apiOnly ? [{home:'recommend', search_results:'search_board', mine:'my_boards'}[apiOnly] || apiOnly] : ['onboarding_status', 'recommend', 'popular', 'my_boards', 'archive',
  'onboarding_BOOK', 'onboarding_MOVIE', 'onboarding_MUSIC', 'initial_contents',
  ...Object.keys(shares).filter((n) => !screenNames.includes(n))];
if (!apiOnly && shares.search_results) names.push('search_board');
const thresholds = { api_failed: ['rate<0.01'], workload_errors: ['count==0'] };
thresholds['aws_completed_on_time{kind:screen}'] = ['count>=0'];
thresholds.aws_api_completed_on_time = ['count>=0'];
for (const name of Object.keys(shares)) {
  thresholds[`aws_completed{name:${name}}`] = ['count>=0'];
}
for (const name of names) {
  thresholds[`api_duration{name:${name}}`] = [`p(95)<${name === 'search_board' ? 1500 : 500}`];
  thresholds[`api_failed{name:${name}}`] = ['rate<0.01'];
}
for (const name of screenNames.filter((n) => n in shares)) {
  thresholds[`screen_duration{screen:${name}}`] = [`p(95)<${name === 'search_results' ? 1500 : 500}`];
}
export const options = {
  scenarios: Object.fromEntries(Object.entries(shares).map(([name, share]) => [name, {
    executor: 'constant-arrival-rate', exec: name,
    // 1만 초 단위면 RATE=2에서도 0.5%를 1/min으로 억지로 올리지 않는다. 준비도 같은 식으로 ceil한다.
    rate: Math.round(Number(__ENV.RATE) * share * 10000), timeUnit: '10000s',
    duration: __ENV.DURATION || '120s', gracefulStop: '30s',
    // 부하 중 VU 초기화가 drop을 만들지 않게 허용한 VU를 측정 전에 모두 만든다.
    // k6 EC2(c7g.xlarge, 8GB)에서 VU 하나가 약 0.6MB라 12,800개(1280/s)는 OOM으로 죽었다. 6,400개면 응답 5초까지 도착률을 지킨다.
    preAllocatedVUs: Math.min(6400, Math.max(10, Math.ceil(Number(__ENV.RATE) * share * 10))),
    maxVUs: Math.min(6400, Math.max(10, Math.ceil(Number(__ENV.RATE) * share * 10))),
  }])),
  thresholds,
  summaryTrendStats: ['count', 'avg', 'min', 'med', 'p(95)', 'p(99)', 'max'],
};

function problem(message) {
  errors.add(1);
  console.error(`WORKLOAD_ERROR ${message}`);
  // 일감 부족·Redis 오류는 서버 포화와 구분한다. 무효인 캐시 비율로 다음 단계를 재지 않는다.
  exec.test.abort(message);
  throw new Error(message);
}
function task() {
  const name = exec.scenario.name;
  if ((apiOnly || name === 'home') && exec.scenario.iterationInTest === 0) {
    console.log(`AWS_STAGE_START ${exec.scenario.startTime}`);
  }
  const value = tasks[name][exec.scenario.iterationInTest];
  if (!value) problem(`준비한 일감 부족: ${name}`);
  started.add(1, { kind: screenNames.includes(name) ? 'screen' : 'action', name });
  return value;
}
function done() {
  const name = exec.scenario.name;
  completed.add(1, { kind: screenNames.includes(name) ? 'screen' : 'action', name });
  if (Date.now() < exec.scenario.startTime + Number(__ENV.AWS_SECONDS) * 1000) {
    onTime.add(1, { kind: screenNames.includes(name) ? 'screen' : 'action', name });
  }
}
function record(res, name, valid = () => true, extra = {}) {
  const tags = { name, ...extra };
  let ok = res.status >= 200 && res.status < 300;
  try { ok = ok && res.json('success') === true && valid(res.json('data')); } catch (_) { ok = false; }
  apiDuration.add(res.timings.duration, tags);
  apiFailed.add(!ok, tags);
  if (Date.now() < exec.scenario.startTime + Number(__ENV.AWS_SECONDS) * 1000) apiOnTime.add(1);
  if (!ok) console.error(`요청 실패: ${name} HTTP ${res.status}`);
  return ok;
}
function params(token, label) {
  return { headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    timeout: '25s', tags: { name: label } };
}
function stoppedByExecutor(error) {
  return String(error).includes('context canceled')
    && Date.now() >= exec.scenario.startTime + (Number(__ENV.AWS_SECONDS) + 30) * 1000;
}
function send(method, path, user, body, name, valid, extra) {
  const res = http.request(method, api.BASE + path, body === null ? null : JSON.stringify(body), params(user.token, name));
  record(res, name, valid, extra);
  return res;
}
function enter(name, work) {
  const t0 = Date.now();
  try { work(); } finally { screenDuration.add(Date.now() - t0, { screen: name }); done(); }
}

export async function home() {
  const { user } = task();
  const screenStart = Date.now();
  const lock = `loadtest:home-lock:${user.id}`;
  let acquired = false;
  let controlMillis = 0;
  try {
    if (!apiOnly) {
      const ready = http.get(`${api.BASE}/api/users/me/onboarding`, params(user.token, 'onboarding_status'));
      if (!record(ready, 'onboarding_status', (d) => d.initialContentsSelected === true)) return;
    }
    // 준비 상태 조회는 캐시에 관여하지 않는다. 같은 사용자의 추천 구간만 직렬화한다.
    const controlStart = Date.now();
    try {
      while (Date.now() - controlStart < 60000) {
        if (await client.sendCommand('EVAL', acquireLock, 1, lock, runId, 60000) === 1) {
          acquired = true;
          break;
        }
        sleep(.01);
      }
      if (!acquired) {
        // 단독 모드의 600명 잠금 큐가 밀린 것은 도착을 처리하지 못한 관측이다.
        // HTTP가 나가지 않은 일감은 API 구간 내 완료율에 포함되지 않는다.
        if (apiOnly) { lockTimeouts.add(1); return; }
        problem('홈 추천 잠금 대기 60초 초과 — 측정 무효');
      }
      const miss = Math.random() < .5;
      if (__ENV.CACHE_MODE !== 'off' && miss) await client.del(`reco:v1:${user.id}`);
      forcedMiss.add(miss);
    } catch (error) {
      if (stoppedByExecutor(error)) return;
      problem('홈 캐시 제어 실패 — Redis 연결과 버전을 확인하세요');
    } finally {
      controlMillis = Date.now() - controlStart;
      controlDuration.add(controlMillis);
    }
    if (apiOnly) {
      const res = http.get(`${api.BASE}/api/boards/recommendation/user`, params(user.token, 'recommend'));
      record(res, 'recommend'); return;
    }
    const requests = api.homeRequests(user.token);
    for (const request of Object.values(requests)) request.params.timeout = '25s';
    const results = http.batch(requests);
    for (const [name, res] of Object.entries(results)) record(res, name);
  } finally {
    if (!apiOnly) screenDuration.add(Date.now() - screenStart - controlMillis, { screen: 'home' });
    done();
    if (acquired) {
      try { await client.del(lock); }
      catch (error) {
        if (!stoppedByExecutor(error)) problem('홈 추천 잠금 해제 실패');
      }
    }
  }
}
export async function teardown() {
  const keys = screens.map((user) => `loadtest:home-lock:${user.id}`);
  const script = `
    local deleted = 0
    for _, key in ipairs(KEYS) do
      if redis.call('GET', key) == ARGV[1] then
        deleted = deleted + redis.call('DEL', key)
      end
    end
    return deleted
  `;
  await client.sendCommand('EVAL', script, keys.length, ...keys, runId);
}
export async function search_results() {
  const { user, keyword, embeddingKey } = task();
  try { await client.del(embeddingKey); }
  catch (_) { problem('검색 캐시 DEL 실패'); }
  enter('search_results', () => record(api.searchBoard(user.token, keyword), 'search_board'));
}
export function archive() {
  const { user } = task();
  enter('archive', () => record(api.archive(user.token, 'ALL', 0, 20), 'archive'));
}
export function onboarding() {
  const { user } = task();
  enter('onboarding', () => {
    const selected = [];
    // 프론트는 활성 탭 하나씩 호출한다(useOnboardingContents). 세 탭 방문을 순차 호출로 재현한다.
    for (const type of ['BOOK', 'MOVIE', 'MUSIC']) {
      const res = http.get(`${api.BASE}/api/contents/onboarding?type=${type}`, params(user.token, `onboarding_${type}`));
      if (!record(res, `onboarding_${type}`, (d) => d.length > 0)) return;
      selected.push(res.json('data')[0].id);
    }
    send('POST', '/api/users/me/initial-contents', user, { contentIds: selected }, 'initial_contents');
  });
}
export function content_detail() {
  const { user, content: c } = task();
  record(api.contentDetail(user.token, c.contentType, c.id), 'content_detail'); done();
}
export function content_search() {
  const { user } = task();
  const keywords = KEYWORDS.filter((k) => [...k].length >= 3);
  record(api.searchContents(user.token, keywords[exec.scenario.iterationInTest % keywords.length]), 'content_search'); done();
}
export function recommend_like() {
  const t = task(); record(api.likeRecommendedBoard(t.user.token, t.likeToken), 'recommend_like', (d) => d.liked); done();
}
export function search_like() {
  const t = task(); record(api.likeSearchBoard(t.user.token, t.likeToken), 'search_like', (d) => d.liked); done();
}
export function stored_like() {
  const t = task(); record(api.likeBoard(t.user.token, t.boardId), 'stored_like', (d) => d.liked); done();
}
export function unlike() {
  const t = task();
  const res = api.unlikeBoard(t.user.token, t.boardId);
  const ok = record(res, 'unlike', (d) => !d.liked, { board_type: t.boardType });
  if (__ENV.AWS_VERIFY_UNLIKES === '1') {
    const data = ok ? res.json('data') : {};
    console.log('UNLIKE_VERIFICATION ' + JSON.stringify({ userId: t.user.id, boardId: t.boardId,
      boardType: t.boardType, ok, returnedBoardId: data.boardId, likeCount: data.likeCount,
      deleted: ok && data.boardId === null }));
  }
  done();
}
function payload(t) {
  const i = exec.scenario.iterationInTest;
  const mode = i % 5 < 3 ? 'layout' : i % 5 === 3 ? 'title' : 'content';
  const b = t.board;
  const contentIds = b.contents.map((c) => c.id);
  // SharedArray가 돌려준 중첩 배치는 읽기 전용이다. 변경할 칸을 복제해 준비 원본을 보존한다.
  const layout = b.layout ? b.layout.map((slot) => ({ ...slot }))
    : contentIds.map((_, j) => ({ x: 15 + (j % 4) * 23, y: 30 + Math.floor(j / 4) * 40, rotation: 0, z: j + 1 }));
  if (mode === 'layout') layout[i % 8] = { ...layout[i % 8], rotation: (i % 121) - 60 };
  if (mode === 'content') contentIds[i % 8] = contents.find((c) => !contentIds.includes(c.id)).id;
  // 저장도 제목 모드만 제목을 바꾼다. 중복 회피는 준비기의 원본 쌍 예약이 맡는다.
  const title = mode === 'title' ? `${b.title.slice(0, 170)} aws-${runId}-${i}` : b.title;
  return [{ title, contentIds, layout, ...(t.create ? { originToken: b.likeToken } : {}) }, mode];
}
function verifyMutation(t, res, mode, operation) {
  if (__ENV.AWS_VERIFY_MUTATIONS !== '1') return;
  let savedBoardId = null;
  let titlePreserved = false;
  try {
    const data = res.json('data');
    savedBoardId = data.boardId;
    titlePreserved = mode === 'title' || data.title === t.board.title;
  } catch (_) { /* 실패 응답은 API 검사에서 잡는다. */ }
  console.log('MUTATION_VERIFICATION ' + JSON.stringify({ userId: t.user.id, savedBoardId,
    sourceBoardId: t.board.boardId, mode, operation, titlePreserved }));
}
export function save_board() {
  const t = task(); const [body, mode] = payload(t);
  const res = send(t.create ? 'POST' : 'PUT', t.create ? '/api/boards' : `/api/boards/${t.board.boardId}`, t.user,
    body, 'save_board', (d) => d.owned && d.boardId !== t.board.boardId, { mutation: mode, source: t.create ? 'recommend' : 'stored' });
  verifyMutation(t, res, mode, 'save_board'); done();
}
export function edit_board() {
  const t = task(); const [body, mode] = payload(t);
  const res = send('PUT', `/api/boards/${t.board.boardId}`, t.user, body, 'edit_board', (d) => d.owned && d.boardId === t.board.boardId, { mutation: mode });
  verifyMutation(t, res, mode, 'edit_board'); done();
}
export function capture() {
  const t = task(); const b = t.board;
  send('POST', '/api/logs/board-capture', t.user, { boardId: b.boardId, title: b.title, contentIds: b.contents.map((c) => c.id) }, 'capture'); done();
}
export function delete_board() {
  const t = task(); send('DELETE', `/api/boards/${t.board.boardId}`, t.user, null, 'delete_board'); done();
}

// 개별 API는 진입 상태·탭별 검색 없이 요청 하나만 보낸다.
export function popular() {
  const {user} = task();
  send('GET', '/api/boards/liked?n=8', user, null, 'popular'); done();
}
export function mine() {
  const {user} = task();
  send('GET', '/api/boards/mine?filter=ALL&page=0&size=20', user, null, 'my_boards'); done();
}
export function initial_contents() {
  const {user, selected} = task();
  send('POST', '/api/users/me/initial-contents', user, {contentIds:selected}, 'initial_contents'); done();
}
