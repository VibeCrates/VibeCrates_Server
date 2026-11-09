"""목표 초과는 기록하고 계속 올린다. 포화와 부하 준비 오류를 구별해 멈춘다."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from collect import Metrics, cache_delta, cloudwatch, pg_reset, pg_snapshot
from common import ROOT, pg, request, write_json
from model import SCREENS, counts
from prepare import prepare, seed, users
from planner import Resources, sweep_plan
from verify import mutations, unlikes


def measurement_start(log):
    for line in Path(log).read_text().splitlines():
        try:
            record = json.loads(line)
        except ValueError:
            continue
        message = record.get('msg', '') if isinstance(record, dict) else ''
        if isinstance(message, str) and message.startswith('AWS_STAGE_START '):
            return float(message.removeprefix('AWS_STAGE_START ')) / 1000
    return None


def judge(summary, manifest, cache, seconds):
    m = summary['metrics']
    value = lambda key, field, default=0: m.get(key, {}).get(field, default)
    failed = value('api_failed', 'value', 1)
    completed = value('aws_completed', 'count')
    expected = sum(manifest.get('expected', {k: len(v) for k, v in manifest['tasks'].items()}).values())
    dropped = value('dropped_iterations', 'count')
    offered = max(expected, value('aws_started', 'count') + dropped)
    on_time = value('aws_completed_on_time', 'count')
    screen_count = value('aws_completed_on_time{kind:screen}', 'count', None)
    api_count = value('aws_api_completed_on_time', 'count', None)
    legacy_throughput = screen_count is None or api_count is None
    if screen_count is None:
        screen_count = sum(value(f'aws_completed{{name:{name}}}', 'count') for name in SCREENS)
    if api_count is None:
        api_count = value('api_failed', 'passes') + value('api_failed', 'fails')
    invalid = value('workload_errors', 'count') > 0
    details = {}
    for kind, prefix in [('screens', 'screen_duration{screen:'), ('apis', 'api_duration{name:')]:
        details[kind] = {}
        for name, metric in m.items():
            if name.startswith(prefix):
                label = name[len(prefix):-1]
                target = 1500 if label in {'search_board', 'search_results'} else 500
                details[kind][label] = {k: metric.get(k, 0) for k in ('count', 'med', 'p(95)', 'p(99)')}
                details[kind][label]['target_ms'] = target
                if kind == 'apis':
                    f = m.get(f'api_failed{{name:{label}}}', {})
                    details[kind][label]['failure_rate'] = f.get('value')
    missing = [name for name in manifest['tasks'] if value(f'aws_completed{{name:{name}}}', 'count') == 0]
    goals = failed < .01 and all(v['count'] > 0 and v['p(95)'] < v['target_ms'] for group in details.values() for v in group.values())
    # 종료 후 drain에서 끝난 요청으로 부하 중 처리량을 부풀리지 않는다.
    saturation = failed >= .05 or dropped > 0 or on_time / max(offered, 1) < .90
    return {**details, 'expected_iterations': expected, 'offered_iterations': offered, 'completed': completed,
            'completed_on_time': on_time, 'completion_ratio': on_time / max(offered, 1),
            'actual_iterations_per_second': on_time / seconds,
            'actual_screens_per_second': screen_count / seconds,
            'actual_api_per_second': api_count / seconds,
            'throughput_includes_drain': legacy_throughput,
            'dropped_iterations': dropped, 'failure_rate': failed, 'goal_pass': goals,
            'saturated': saturation, 'invalid': invalid, 'missing_scenarios': missing, 'cache': cache}


def run_stage(out, prepared, rate, seconds, args):
    out.mkdir(parents=True)
    manifest = prepare(prepared, rate, seconds, args.skip_ai)
    # 어느 단계의 토큰·일감인지 나중에도 확인할 수 있게 복사한다. env 파일 값은 포함하지 않는다.
    write_json(out / 'stage.json', manifest)
    metrics = Metrics(manifest['screens'][0]['token'], out)
    sampler = None
    if args.resources == 'local':
        sampler = subprocess.Popen([str(ROOT / 'load-test/lib/sampler.sh'), str(out / 'resources.csv')], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        # 기존 sampler의 최초 로그인까지 측정 밖에서 끝낸다. CSV 헤더는 로그인 뒤에 쓰인다.
        for _ in range(100):
            if (out / 'resources.csv').exists():
                break
            if sampler.poll() is not None:
                raise RuntimeError('로컬 자원 샘플러 시작 실패')
            time.sleep(.1)
        else:
            sampler.terminate()
            raise RuntimeError('로컬 자원 샘플러 로그인 시간초과')
    try:
        metrics.start()
        before = metrics.snapshot()
        write_json(out / 'cache-before.json', before)
        pg_reset()
        event_before = int(pg('SELECT coalesce(max(id),0) FROM user_event_log')) if args.verify_mutations or args.verify_unlikes else None
        env = dict(os.environ, RATE=str(rate), DURATION=f'{seconds}s', AWS_SECONDS=str(seconds),
            AWS_STAGE_FILE=str(out / 'stage.json'), AWS_RUN_ID=manifest['run'], SKIP_AI='1' if args.skip_ai else '0',
            CACHE_MODE=args.cache_mode, AWS_VERIFY_MUTATIONS='1' if args.verify_mutations else '0',
            AWS_VERIFY_UNLIKES='1' if args.verify_unlikes else '0',
            K6_AUTO_EXTENSION_RESOLUTION='true', K6_NO_USAGE_REPORT='true')
        started = time.time()
        with open(out / 'k6.log', 'w') as log:
            status = subprocess.run([os.environ.get('K6_BIN', 'k6'), 'run', '--quiet', '--log-format', 'json', '--no-usage-report',
                '--summary-export', str(out / 'summary.json'), str(ROOT / 'load-test/scenarios/aws-screens.js')], env=env, stdout=log, stderr=subprocess.STDOUT).returncode
        ended = time.time()
        after = metrics.snapshot()
        write_json(out / 'cache-after.json', after)
    finally:
        metrics.close()
        if sampler:
            import signal
            os.killpg(sampler.pid, signal.SIGTERM)
            sampler.wait(timeout=10)
    pg_snapshot(out / 'pgss.json')
    process_started, process_ended = started, ended
    actual_start = measurement_start(out / 'k6.log')
    if actual_start is not None:
        started, ended = actual_start, min(ended, actual_start + seconds)
    write_json(out / 'window.json', {'start': started, 'end': ended, 'rate': rate, 'seconds': seconds,
        'process_start': process_started, 'process_end': process_ended})
    if args.resources == 'aws':
        cloudwatch(out, started, ended)
    if status not in (0, 99) or not (out / 'summary.json').exists():
        raise RuntimeError(f'k6 실행 오류({status}). {out}/k6.log를 확인하세요')
    if actual_start is None:
        raise RuntimeError('k6 실제 측정 시작 시각이 없습니다 — 측정 무효')
    result = judge(json.loads((out / 'summary.json').read_text()), manifest, cache_delta(before, after), seconds)
    if args.verify_unlikes:
        result['unlikes'] = unlikes(out / 'k6.log', manifest, event_before, out / 'unlike-verification.json')
    if args.verify_mutations:
        result['mutations'] = mutations(out / 'k6.log', event_before, out / 'mutation-verification.json')
    if result['invalid'] or result['missing_scenarios']:
        raise RuntimeError(f'측정 무효: 일감/시나리오 오류. {out}/k6.log를 확인하세요')
    if args.cache_mode == 'on':
        if result['cache']['recommendation']['hit_rate'] is None:
            raise RuntimeError('추천 캐시 카운터가 늘지 않았습니다. backend 캐시 활성화를 확인하세요')
        if not args.skip_ai and result['cache']['embedding']['miss'] == 0:
            raise RuntimeError('검색 캐시 미스가 관측되지 않았습니다')
        if result['cache']['embedding']['hit'] > 0 or any(v['error'] > 0 for v in result['cache'].values()):
            raise RuntimeError('검색 적중 또는 Redis 캐시 오류 관측 — 캐시 제어 측정 무효')
    elif any(v[k] > 0 for v in result['cache'].values() for k in ('hit', 'miss', 'error')):
        raise RuntimeError('캐시 off인데 앱 카운터가 늘었습니다. 두 backend 변수를 모두 false로 배포하세요')
    write_json(out / 'result.json', result)
    hit = result['cache']['recommendation']['hit_rate']
    print(f"RATE {rate:g}: API 실패 {100*result['failure_rate']:.2f}%, 완료 {100*result['completion_ratio']:.1f}%, "
          f"drop {result['dropped_iterations']}, 홈 hit {f'{100*hit:.1f}%' if hit is not None else '꺼짐'}, "
          f"목표 {'통과' if result['goal_pass'] else '초과'}, 포화 {result['saturated']}", flush=True)
    for group in ('screens', 'apis'):
        print('  ' + group + ': ' + ', '.join(f"{k} p95={v['p(95)']:.1f}ms(n={v['count']})" for k, v in result[group].items()), flush=True)
    return result


def main():
    p = argparse.ArgumentParser(description='화면 RATE를 올려 포화와 목표 상한을 따로 찾는다')
    p.add_argument('rates', nargs='*', type=float, default=[10, 20, 40, 80, 160, 320, 640])
    p.add_argument('--seconds', type=int, default=int(os.environ.get('STEP_SECONDS', 120)))
    p.add_argument('--warmup-seconds', type=int, default=30)
    p.add_argument('--refine-repeats', type=int, default=3)
    p.add_argument('--skip-ai', action='store_true')
    p.add_argument('--resources', choices=['local', 'aws', 'none'], default='local')
    p.add_argument('--cache-mode', choices=['on', 'off'], default='on')
    p.add_argument('--new-users', type=int, default=20000)
    p.add_argument('--output', default=f'load-test/results/aws-{time.strftime("%Y%m%d-%H%M%S")}')
    p.add_argument('--prepared-directory', help='같은 환경의 로그인·서명 토큰과 중복 배정 기록을 이어 쓸 디렉터리')
    preparation = p.add_mutually_exclusive_group()
    preparation.add_argument('--budget-only', action='store_true', help='DB·예약 장부의 전체 예산만 검사하고 부하는 걸지 않는다')
    preparation.add_argument('--prepare-only', action='store_true', help='전체 예산의 토큰까지 준비하고 워밍업·측정 없이 끝낸다')
    p.add_argument('--verify-mutations', action='store_true', help='BOARD_EDIT를 요청별 저장 id와 비교한다')
    p.add_argument('--verify-unlikes', action='store_true', help='취소 응답과 DB 좋아요 제거·보드 완전 삭제를 비교한다')
    p.add_argument('--collect-cloudwatch', metavar='RESULT_DIRECTORY')
    args = p.parse_args()
    if args.collect_cloudwatch:
        for window in Path(args.collect_cloudwatch).glob('*/window.json'):
            w = json.loads(window.read_text())
            cloudwatch(window.parent, w['start'], w['end'])
        return
    if not args.rates or any(r <= 0 for r in args.rates) or args.seconds < 1 or args.warmup_seconds < 0 or args.refine_repeats < 0:
        p.error('RATE·단계 초는 양수, 워밍업·반복 수는 0 이상이어야 합니다')
    if args.rates != sorted(set(args.rates)):
        p.error('RATE는 중복 없이 오름차순으로 주세요')
    if args.seconds > 1200 or args.warmup_seconds > 1200:
        p.error('토큰 만료를 피하려고 한 구간은 최대 1,200초로 제한합니다')
    if (args.resources == 'aws') != (os.environ.get('RESOURCE_MODE', 'local') == 'aws'):
        p.error('AWS 실행은 RESOURCE_MODE=aws와 --resources aws를 함께 지정하세요')
    out = Path(args.output).resolve()
    out.mkdir(parents=True, exist_ok=False)
    prepared = Path(args.prepared_directory).resolve() if args.prepared_directory else out / 'prepared'
    preparing = time.monotonic()
    seed(args.new_users)
    data = users(prepared)
    plan = sweep_plan(args.rates, args.seconds, args.warmup_seconds, args.refine_repeats, args.skip_ai)
    budget = Resources(prepared, data).preflight(plan, out / 'budget.json', expand=not args.budget_only)
    preparation_seconds = time.monotonic()-preparing
    write_json(out / 'preparation.json', {'seconds': preparation_seconds,
        'token_preparation': budget.get('token_preparation'), 'measured': False})
    print(f'로그인·DB 검사·토큰을 포함한 전체 준비 {preparation_seconds:.1f}초 (측정 밖)', flush=True)
    write_json(out / 'config.json', {**vars(args), 'budget': budget,
                                    'k6': '2.3.0', 'xk6_redis': '0.4.5'})
    if args.budget_only or args.prepare_only:
        print(f'전체 예산 검사 통과: {out}/budget.json (워밍업·측정 없음)', flush=True)
        return
    if args.resources == 'local':
        inspect = subprocess.run(['docker', 'inspect', 'backend_server', 'postgres_server', 'qdrant_server',
            '--format', '{{.Name}} CPU={{.HostConfig.NanoCpus}} Memory={{.HostConfig.Memory}}'], capture_output=True, text=True, check=True)
        (out / 'limits.txt').write_text(inspect.stdout)
        flags = subprocess.run(['docker', 'inspect', 'backend_server'], capture_output=True, text=True, check=True)
        environment = dict(s.split('=', 1) for s in json.loads(flags.stdout)[0]['Config']['Env'] if '=' in s)
        want = 'true' if args.cache_mode == 'on' else 'false'
        if any(environment.get(k, 'true').lower() != want for k in ('RECOMMENDATION_CACHE_ENABLED', 'AI_EMBEDDING_CACHE_ENABLED')):
            raise RuntimeError('backend 캐시 환경 변수가 측정 모드와 다릅니다. CACHE_ENABLED를 맞춰 Docker backend를 다시 띄우세요')
    if args.cache_mode == 'on':
        print('화면 풀 600명 추천 캐시 워밍업 — 측정 밖', flush=True)
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda u: request('/api/boards/recommendation/user', u['token']), data['screens']))
    print(f'결과: {out}\n전체 실행 예산 검사 통과 (워밍업·정밀 반복·예비 일감 포함)', flush=True)
    stages = []
    def stage(rate, label):
        if args.warmup_seconds:
            run_stage(out / f'{label}-warmup', prepared, rate, args.warmup_seconds, args)
        result = run_stage(out / label, prepared, rate, args.seconds, args)
        stages.append({'label': label, 'rate': rate, **result})
        write_json(out / 'results.json', stages)
        return result
    previous = None
    for i, rate in enumerate(args.rates):
        result = stage(rate, f'step-{i+1}-rate-{rate:g}')
        if result['saturated']:
            lower = previous if previous is not None else rate / 2
            for repeat in range(args.refine_repeats):
                for j, finer in enumerate((lower, (lower + rate) / 2, rate)):
                    stage(finer, f'refine-{repeat+1}-{j+1}-rate-{finer:g}')
            break
        previous = rate
    else:
        print('최대 지정 RATE까지 포화 없음 — 더 높은 RATE 목록으로 추가 측정이 필요합니다', flush=True)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, KeyError, ValueError) as e:
        print(str(e), file=sys.stderr)
        sys.exit(1)
