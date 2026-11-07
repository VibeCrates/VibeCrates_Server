"use k6 = 2.3.0";
"use k6 with k6/x/redis = 0.4.5";
// 운영 키를 건드리기 전에 별도 키로 확장 로딩과 DEL 왕복을 확인한다.
import redis from 'k6/x/redis';
import { check } from 'k6';

const client = new redis.Client(__ENV.REDIS_URL || 'redis://localhost:6379/0');
export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1'] } };
export default async function () {
  const key = `loadtest:redis-smoke:${Date.now()}`;
  await client.set(key, 'ok', 30);
  check(await client.del(key), { 'DEL이 키 하나를 지움': (n) => n === 1 });
  check(await client.exists(key), { 'DEL 뒤 키 없음': (n) => n === 0 });
}
