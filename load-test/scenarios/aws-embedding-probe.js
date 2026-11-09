"use k6 = 2.3.0";
"use k6 with k6/x/redis = 0.4.5";
// 서버의 실제 검색 경로·앱 카운터·부하 시나리오와 같은 Redis DEL을 함께 검증한다.
import http from 'k6/http';
import redis from 'k6/x/redis';
import { check } from 'k6';

const probe = JSON.parse(open(__ENV.AWS_CACHE_PROBE_FILE));
const base = __ENV.BASE_URL || 'http://localhost:8080';
const client = new redis.Client(__ENV.REDIS_URL || 'redis://localhost:6379/0');
const params = { headers: { Authorization: `Bearer ${probe.user.token}` }, timeout: '35s' };
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
function counters() {
  const r = http.get(`${base}/actuator/prometheus`, params);
  const values = { hit: 0, miss: 0, error: 0 };
  for (const line of r.body.split('\n')) {
    const m = line.match(/^crates_ai_embedding_cache_total\{result="(hit|miss|error)"\}\s+([\d.eE+-]+)/);
    if (m) values[m[1]] = Number(m[2]);
  }
  return values;
}
export default async function () {
  await client.del(probe.embeddingKey);
  for (const [i, expected] of ['miss', 'hit', 'miss', 'miss'].entries()) {
    let deleted = null;
    if (i >= 2) deleted = await client.del(probe.embeddingKey);
    const before = counters();
    const r = http.get(`${base}/api/search/board?keyword=${encodeURIComponent(probe.keyword)}`, params);
    const after = counters();
    const delta = Object.fromEntries(Object.keys(before).map((k) => [k, after[k]-before[k]]));
    const exists = await client.exists(probe.embeddingKey);
    check(r, { '검색 성공': (r) => r.status === 200 && r.json('success') === true });
    check(delta, { '앱 캐시 카운터 일치': (d) => d[expected] === 1 && d.error === 0 && d[expected === 'hit' ? 'miss' : 'hit'] === 0 });
    check(exists, { '정규화된 키에 캐시 존재': (n) => n === 1 });
    if (i >= 2) check(deleted, { 'DEL로 실제 캐시 삭제': (n) => n === 1 });
    console.log('EMBEDDING_PROBE '+JSON.stringify({ request: i+1, beforeRequestDel: i>=2, expected, delta, deleted, exists }));
  }
}
