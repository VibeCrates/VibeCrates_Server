// RATE는 저장된 보드의 좋아요→취소 쌍/s. 별도 화면 계정은 인기 보드를 5회/s 읽는다.
import http from 'k6/http';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Trend, Rate, Counter } from 'k6/metrics';
const actors = new SharedArray('toggle-actors', () => JSON.parse(open(__ENV.AWS_STAGE_FILE)).actors);
const readers = new SharedArray('toggle-readers', () => JSON.parse(open(__ENV.AWS_STAGE_FILE)).readers);
const duration = Number(__ENV.AWS_SECONDS || 120);
const rate = Number(__ENV.RATE || 5);
const latency = new Trend('toggle_duration', true);
const failed = new Rate('toggle_failed');
const completed = new Counter('toggle_completed_on_time');
const successful = new Counter('toggle_success_on_time');
const apiCompleted = new Counter('toggle_api_on_time');
const workload = new Counter('toggle_workload_errors');
const thresholds = {toggle_failed: ['rate<0.01'],toggle_workload_errors:['count==0']};
for (const name of ['like','unlike','read']) {
  thresholds[`toggle_duration{name:${name}}`] = ['p(95)<500'];
  thresholds[`toggle_failed{name:${name}}`] = ['rate<0.01'];
}
export const options = {
  scenarios: {
    pairs: {executor:'constant-arrival-rate',exec:'pair',rate,timeUnit:'1s',duration:`${duration}s`,gracefulStop:'30s',preAllocatedVUs:Math.max(20,rate*10),maxVUs:Math.max(20,rate*10)},
    reads: {executor:'constant-arrival-rate',exec:'read',rate:5,timeUnit:'1s',duration:`${duration}s`,gracefulStop:'30s',preAllocatedVUs:150,maxVUs:150},
  }, thresholds,
};
function params(token) {return {headers:{Authorization:`Bearer ${token}`},timeout:'25s'};}
function record(response,name) {
  let ok=response.status>=200&&response.status<300;
  try {ok=ok&&response.json('success')===true;} catch (_) {ok=false;}
  latency.add(response.timings.duration,{name});failed.add(!ok,{name});
  if(Date.now()<exec.scenario.startTime+duration*1000) apiCompleted.add(1,{name});
  if(!ok) console.error(`${name} HTTP ${response.status}`);
  return ok;
}
export function pair() {
  const i=exec.scenario.iterationInTest;
  if(i===0) console.log(`AWS_STAGE_START ${exec.scenario.startTime}`);
  const actor=actors[i%actors.length];
  const board=actor.boards[Math.floor(i/actors.length)];
  if(!board) {workload.add(1);exec.test.abort('좋아요 쌍의 사전 배정 부족');return;}
  const url=`${__ENV.BASE_URL}/api/boards/${board}/likes`;
  const liked=record(http.post(url,null,params(actor.token)),'like');
  const unliked=record(http.del(url,null,params(actor.token)),'unlike');
  if(Date.now()<exec.scenario.startTime+duration*1000) {
    completed.add(1);if(liked&&unliked)successful.add(1);
  }
}
export function read() {
  const user=readers[exec.scenario.iterationInTest%readers.length];
  record(http.get(`${__ENV.BASE_URL}/api/boards/liked?n=8`,params(user.token)),'read');
}
