"""AWS 좋아요→취소 스윕. 서버 포화까지 올리고 주변 세 지점을 두 번 반복한다."""
import argparse,itertools,json,math,os,random,subprocess,time
from pathlib import Path
from common import ROOT,rows,write_json
from prepare import users
from collect import Metrics,cloudwatch,pg_reset,pg_snapshot
from sweep import measurement_start

def wait_for_idle(metrics,out,timeout=300):
    started=time.monotonic();deadline=started+timeout;samples=[];quiet_since=None;last_count=None
    while True:
        snapshot=metrics.snapshot()
        def total(prefix):
            return sum(value for task in snapshot.values() for key,value in task.items() if key.startswith(prefix))
        active=total('hikaricp_connections_active')
        pending=total('hikaricp_connections_pending')
        queued=total('crates_user_vector_recalculation_pending')
        count=total('crates_user_vector_recalculation_seconds_count')
        quiet=active==0 and pending==0 and queued==0 and count==last_count
        now=time.monotonic()
        quiet_since=(quiet_since if quiet_since is not None else now) if quiet else None
        samples.append({'at':time.time(),'hikari_active':active,'hikari_pending':pending,'recalculation_pending':queued,'recalculation_count':count})
        settled=quiet_since is not None and now-quiet_since>=10
        write_json(out/'settle.json',{'wait_seconds':now-started,'settled':settled,'samples':samples,
          'method':'Hikari active/pending and recalculation pending zero, completion counter stable for 10 seconds'})
        if settled:return
        if now>=deadline:raise RuntimeError('이전 단계 재계산이 5분 안에 가라앉지 않았습니다')
        last_count=count
        time.sleep(2)

def prepare(directory,rate,seconds):
    data=users(directory)
    boards=rows("""SELECT b.id FROM board b JOIN users u ON u.id=b.user_id
      WHERE b.board_type='USER_CUSTOM' AND b.deleted_at IS NULL
      AND u.login_id ~ '^loadtest_u[0-9]+$'
      AND substring(u.login_id from '[0-9]+$')::int % 2=0 ORDER BY b.id LIMIT 1000""")
    liked=rows("SELECT user_id,board_id FROM board_feedback WHERE rating='LIKE'")
    existing={(r['user_id'],r['board_id']) for r in liked}
    needed=math.ceil(rate*seconds/600)+2
    actors=[]
    for user in data['actors']:
        eligible=[b['id'] for b in boards if (user['id'],b['id']) not in existing]
        if len(eligible)<needed: raise RuntimeError('안정적인 보드의 좋아요 쌍 부족')
        allowed=random.Random(20261108+user['id']).sample(eligible,needed)
        actors.append({**user,'boards':allowed})
    return {'actors':actors,'readers':data['screens']}

def stage(out,prepared,rate,seconds):
    out,prepared=out.resolve(),prepared.resolve()
    out.mkdir(parents=True)
    metrics=Metrics(None,out)
    try:
        metrics.start();wait_for_idle(metrics,out)
        manifest=prepare(prepared,rate,seconds);write_json(out/'stage.json',manifest)
        before=metrics.snapshot();write_json(out/'cache-before.json',before);pg_reset()
        env=dict(os.environ,RATE=str(rate),AWS_SECONDS=str(seconds),AWS_STAGE_FILE=str(out/'stage.json'),K6_NO_USAGE_REPORT='true')
        with open(out/'k6.log','w') as log:
            p=subprocess.run([os.environ.get('K6_BIN','k6'),'run','--quiet','--log-format','json','--no-usage-report','--summary-trend-stats','avg,med,p(95),p(99),count','--summary-export',str(out/'summary.json'),str(ROOT/'load-test/scenarios/aws-like-toggle.js')],env=env,stdout=log,stderr=subprocess.STDOUT)
        after=metrics.snapshot();write_json(out/'cache-after.json',after)
    finally: metrics.close()
    if p.returncode not in (0,99): raise RuntimeError('k6 쌍 실행 오류')
    start=measurement_start(out/'k6.log')
    if start is None: raise RuntimeError('측정 시작 시각 누락')
    write_json(out/'window.json',{'start':start,'end':start+seconds,'rate':rate,'seconds':seconds})
    pg_snapshot(out/'pgss.json');cloudwatch(out,start,start+seconds)
    m=json.loads((out/'summary.json').read_text())['metrics']
    def get(key,field,default=0): return m.get(key,{}).get(field,default)
    if get('toggle_workload_errors','count')>0: raise RuntimeError('일감 오류')
    count=get('toggle_completed_on_time','count');drop=get('dropped_iterations','count')
    expected=rate*seconds;completion=count/expected
    apis={name:{**m.get(f'toggle_duration{{name:{name}}}',{}),'failure_rate':get(f'toggle_failed{{name:{name}}}','value',1)} for name in ('like','unlike','read')}
    if any(x.get('count',0)==0 for x in apis.values()): raise RuntimeError('시나리오 표본 누락')
    failed=max(apis['like']['failure_rate'],apis['unlike']['failure_rate'])
    result={'label':out.name,'rate':rate,'apis':apis,'failure_rate':failed,'dropped_iterations':drop,'completion_ratio':completion,'actual_pairs_per_second':count/seconds,'successful_pairs_per_second':get('toggle_success_on_time','count')/seconds,'saturated':failed>=.05 or drop>0 or completion<.9,'goal_pass':all(x['failure_rate']<.01 and x['p(95)']<500 for x in apis.values())}
    write_json(out/'result.json',result)
    print(json.dumps(result,ensure_ascii=False),flush=True);return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True);p.add_argument('--prepared-directory',required=True);p.add_argument('--seconds',type=int,default=120);p.add_argument('--warmup-seconds',type=int,default=30);p.add_argument('--repeats',type=int,default=2);p.add_argument('rates',nargs='*',type=int,default=[5,10,20,30,40,60,80,120]);a=p.parse_args()
    if os.environ.get('RESOURCE_MODE')!='aws':p.error('RESOURCE_MODE=aws 필요')
    out=Path(a.output);out.mkdir(parents=True,exist_ok=False);prepared=Path(a.prepared_directory);prepared.mkdir(parents=True,exist_ok=True)
    write_json(out/'config.json',vars(a));results=[]
    def measure(rate,label):
        stage(out/(label+'-warmup'),prepared,rate,a.warmup_seconds)
        r=stage(out/label,prepared,rate,a.seconds);results.append(r);write_json(out/'results.json',results);return r
    prev=max(1,a.rates[0]//2)
    for n,rate in enumerate(itertools.chain(a.rates, (a.rates[-1]*2**power for power in itertools.count(1))),1):
        r=measure(rate,f'step-{n}-rate-{rate}')
        if r['saturated']:
            for repeat in range(1,a.repeats+1):
                for j,finer in enumerate(sorted(set([prev,(prev+rate)//2,rate])),1):measure(finer,f'refine-{repeat}-{j}-rate-{finer}')
            break
        prev=rate
if __name__=='__main__':main()
