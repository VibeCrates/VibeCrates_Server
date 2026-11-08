"""RATE=단일 API 요청/초. 일감 준비는 측정 밖, 교체된 작업의 관측도 보존한다."""
import argparse
import json
import math
import os
from pathlib import Path
import subprocess
import time
from concurrent.futures import ThreadPoolExecutor
from common import ROOT, rows, request, login, write_json
from prepare import users, prepare as screen_prepare, seed
from planner import content_sample
from keywords import Keywords
from collect import cache_delta, pg_reset, pg_snapshot, cloudwatch
from sweep import measurement_start
from toggle import (wait_for_healthy, start_stage_metrics, record_metrics_gap,
                    service_state, wait_for_idle)

APIS = ('home', 'search_results', 'popular', 'mine', 'content_detail', 'content_search',
        'recommend_like', 'stored_like', 'search_like', 'save_board', 'edit_board', 'initial_contents')
WRITES = set(APIS[6:])
LABEL = {'home':'recommend', 'search_results':'search_board', 'mine':'my_boards'}


def prepare(directory, name, rate, seconds):
    directory.mkdir(parents=True, exist_ok=True)
    if name in WRITES - {'initial_contents'}:
        return screen_prepare(directory, rate, seconds, only=name)
    data = users(directory)
    n = math.ceil(rate*seconds)+1
    if name == 'initial_contents':
        fresh = rows("SELECT u.id,u.login_id FROM users u WHERE login_id ~ '^loadtest_new[0-9]+$' AND NOT EXISTS (SELECT 1 FROM user_vector v WHERE v.user_id=u.id) ORDER BY u.id")
        reserved_path = directory/'initial-reserved.json'
        reserved = set(json.loads(reserved_path.read_text())) if reserved_path.exists() else set()
        fresh = [u for u in fresh if u['id'] not in reserved]
        if len(fresh)<n:
            total = int(__import__('common').pg("SELECT count(*) FROM users WHERE login_id ~ '^loadtest_new[0-9]+$'"))
            target = total + n-len(fresh)+100
            if target>100000:raise RuntimeError('신규 계정 예산 100000명 초과')
            seed(target)
            return prepare(directory,name,rate,seconds)
        chosen = fresh[:n]
        write_json(reserved_path, sorted(reserved | {u['id'] for u in chosen}))
        selected = [request('/api/contents/onboarding?type='+kind, data['screens'][0]['token'])[0]['id'] for kind in ('BOOK','MOVIE','MUSIC')]
        with ThreadPoolExecutor(max_workers=4) as pool:
            tasks = [{'user':u,'selected':selected} for u in pool.map(login,chosen)]
    else:
        pool = data['actors'] if name in {'content_detail','content_search'} else data['screens']
        tasks = [{'user':pool[i%len(pool)]} for i in range(n)]
        if name == 'search_results':
            for task, term in zip(tasks, Keywords(directory).take(n)):task.update(term)
        if name == 'content_detail':
            path = directory/'contents.json'
            sample = json.loads(path.read_text()) if path.exists() else content_sample()
            write_json(path,sample)
            cursor = directory/'detail-cursor.json'
            offset = json.loads(cursor.read_text()) if cursor.exists() else 0
            for i,task in enumerate(tasks):task['content'] = sample[(offset+i)%len(sample)]
            write_json(cursor,offset+n)
    return {'tasks':{name:tasks}, 'screens':data['screens'], 'contents':[],
            'expected':{name:math.ceil(rate*seconds)}, 'run':f'{time.time_ns():x}'}


def judge(summary, name, rate, seconds, changed=False):
    m=summary['metrics']
    def get(key, field, default=0):return m.get(key,{}).get(field,default)
    if get('workload_errors','count')>0:raise RuntimeError('일감/캐시 제어 오류')
    label=LABEL.get(name,name)
    duration=m.get(f'api_duration{{name:{label}}}',{})
    if duration.get('count',0)==0:raise RuntimeError('API 표본 누락')
    failed=get(f'api_failed{{name:{label}}}','value',1)
    count=get('aws_api_completed_on_time','count')
    expected=rate*seconds
    drop=get('dropped_iterations','count')
    target=1500 if name=='search_results' else 500
    return {'label':name, 'rate':rate, 'api':label,
        'p50':duration['med'], 'p95':duration['p(95)'], 'p99':duration['p(99)'],
        'count':duration['count'], 'actual_api_per_second':count/seconds,
        'failure_rate':failed, 'dropped_iterations':drop, 'completion_ratio':count/expected,
        'saturated':changed or failed>=.05 or drop>0 or count/expected<.9,
        'goal_pass':failed<.01 and duration['p(95)']<target, 'ecs_task_changed':changed}


def stage(out,prepared,name,rate,seconds,cache_mode='on',settle_before=True,settle_after=True):
    out=out.resolve();out.mkdir(parents=True)
    manifest=prepare(prepared,name,rate,seconds)
    write_json(out/'stage.json',manifest)
    service_before=wait_for_healthy(out)
    write_json(out/'ecs-before.json',service_before)
    ready=prepared/'home-cache-ready.json'
    if name=='home' and cache_mode=='on' and not ready.exists():
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda u:request('/api/boards/recommendation/user',u['token']),manifest['screens']))
        write_json(ready,{'prewarmed_users':len(manifest['screens']),'at':time.time()})
    metrics,before=start_stage_metrics(out,settle_before)
    changed=False;censored=False
    try:
        write_json(out/'cache-before.json',before);pg_reset()
        env=dict(os.environ,RATE=str(rate),AWS_SECONDS=str(seconds),DURATION=f'{seconds}s',
            AWS_API_ONLY=name,AWS_STAGE_FILE=str(out/'stage.json'),AWS_RUN_ID=manifest['run'],
            CACHE_MODE=cache_mode,K6_NO_USAGE_REPORT='true',K6_AUTO_EXTENSION_RESOLUTION='true')
        with open(out/'k6.log','w') as log:
            p=subprocess.run([os.environ.get('K6_BIN','k6'),'run','--quiet','--log-format','json',
                '--no-usage-report','--summary-export',str(out/'summary.json'),
                str(ROOT/'load-test/scenarios/aws-api.js')],env=env,stdout=log,stderr=subprocess.STDOUT)
        try:after=metrics.snapshot()
        except RuntimeError as error:
            after,changed=record_metrics_gap(metrics,out,error,True);censored=True
        write_json(out/'cache-after.json',after)
        if settle_after and name in WRITES and not censored:
            try:wait_for_idle(metrics,out,filename='post-settle.json')
            except RuntimeError as error:
                _,transition=record_metrics_gap(metrics,out,error,False);changed |= transition
    finally:metrics.close()
    service_after=service_state();write_json(out/'ecs-after.json',service_after)
    previous={e['id'] for e in service_before.get('events',[])}
    events=[e for e in service_after.get('events',[]) if e['id'] not in previous and ('unhealthy' in e['message'] or 'replaced' in e['message'])]
    write_json(out/'ecs-replacement-events.json',events)
    changed |= bool(events)
    if p.returncode not in (0,99):raise RuntimeError(f'k6 실행 실패 {p.returncode}')
    start=measurement_start(out/'k6.log')
    if start is None:raise RuntimeError('측정 시작 시각 누락')
    write_json(out/'window.json',{'start':start,'end':start+seconds,'rate':rate,'seconds':seconds})
    pg_snapshot(out/'pgss.json');cloudwatch(out,start,start+seconds)
    r=judge(json.loads((out/'summary.json').read_text()),name,rate,seconds,changed)
    r.update(ecs_metrics_censored=censored,cache=cache_delta(before,after))
    # 교체 때 마지막 샘플까지만 비교한다. 카운터 중단을 서버 오류로 바꾸지 않는다.
    cache=r['cache']
    if cache_mode=='on':
        if any(v['error'] for v in cache.values()):raise RuntimeError('Redis 캐시 오류')
        if name=='search_results' and cache['embedding']['hit']>0:raise RuntimeError('고유 검색어 캐시 적중')
        if not censored and name=='home' and cache['recommendation']['hit_rate'] is None:raise RuntimeError('추천 캐시 관측 누락')
    elif any(v[k] for v in cache.values() for k in ('hit','miss','error')):raise RuntimeError('캐시 off 카운터 증가')
    write_json(out/'result.json',r)
    print(json.dumps(r,ensure_ascii=False),flush=True)
    return r


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('name',choices=APIS);p.add_argument('--rate',type=int,required=True)
    p.add_argument('--seconds',type=int,default=60);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--prepared',type=Path,required=True);p.add_argument('--cache-mode',choices=['on','off'],default='on')
    a=p.parse_args();stage(a.output,a.prepared,a.name,a.rate,a.seconds,a.cache_mode)
