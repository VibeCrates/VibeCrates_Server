"use k6 = 2.3.0";
"use k6 with k6/x/redis = 0.4.5";
// 운영 키를 건드리기 전에 별도 키로 확장 로딩과 DEL 왕복을 확인한다.
import redis from 'k6/x/redis';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const client = new redis.Client(__ENV.REDIS_URL || 'redis://localhost:6379/0');
const completed = new Counter('redis_smoke_completed');
const acquireLock = "return redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) and 1 or 0";
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'], redis_smoke_completed: ['count==1'] } };
export default async function () {
  const key = `loadtest:redis-smoke:${Date.now()}`;
  await client.set(key, 'ok', 30);
  check(await client.del(key), { 'DEL이 키 하나를 지움': (n) => n === 1 });
  check(await client.exists(key), { 'DEL 뒤 키 없음': (n) => n === 0 });
  try {
    check(await client.sendCommand('EVAL', acquireLock, 1, key, 'smoke', 30000), {
      '빈 잠금 획득': (n) => n === 1,
    });
    check(await client.sendCommand('EVAL', acquireLock, 1, key, 'smoke', 30000), {
      '잠금 경합은 예외 없이 반환': (n) => n === 0,
    });
  } finally {
    await client.del(key);
  }
  completed.add(1);
}
