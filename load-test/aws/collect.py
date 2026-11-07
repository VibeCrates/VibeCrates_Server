"""단계 경계 캐시 카운터, 부하 중 풀 대기, 단계 후 CloudWatch·SQL을 남긴다."""
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import threading
import time

from common import ROOT, pg, request, write_json


def aws(*args):
    p = subprocess.run(['aws', *args, '--no-cli-pager'], capture_output=True, text=True, timeout=90)
    if p.returncode:
        raise RuntimeError('AWS 수집 명령 실패: ' + ' '.join(args[:2]))
    return p.stdout


def ecs_targets():
    cluster, service = os.environ['ECS_CLUSTER'], os.environ.get('ECS_SERVICE', 'backend')
    tasks = json.loads(aws('ecs', 'list-tasks', '--cluster', cluster, '--service-name', service))['taskArns']
    if not tasks:
        raise RuntimeError('수집할 ECS 작업이 없습니다')
    return tasks


def ecs_command(task, once=False):
    script = (ROOT / 'load-test/aws/ecs-metrics.sh').read_text()
    command = '/bin/sh -c ' + shlex.quote(script) + ' metrics ' + ('once' if once else 'loop')
    return ['aws', 'ecs', 'execute-command', '--cluster', os.environ['ECS_CLUSTER'], '--task', task,
            '--container', os.environ.get('ECS_CONTAINER', 'backend'), '--interactive', '--command', command,
            '--no-cli-pager']


def parse_prom(text):
    values = {}
    for line in text.replace('\r', '').splitlines():
        match = re.match(r'((?:crates_recommendation_cache_total|crates_ai_embedding_[\w]+|crates_user_vector_[\w]+|hikaricp_connections_[\w]+|process_cpu_usage|jvm_memory_used_bytes)(?:\{[^}]*\})?)\s+([\d.eE+\-]+)', line)
        if match:
            values[match[1]] = float(match[2])
    return values


def latest_sample(text):
    samples = re.findall(r'AWS_SAMPLE (\d+)\n(.*?)AWS_SAMPLE_END', text.replace('\r', ''), re.S)
    if not samples:
        return None
    timestamp, body = samples[-1]
    return int(timestamp), parse_prom(body)


class Metrics:
    def __init__(self, token, directory):
        self.token, self.directory = token, Path(directory)
        self.mode = os.environ.get('RESOURCE_MODE', 'local')
        self.targets = ecs_targets() if self.mode == 'aws' else ['local']
        self.stop = threading.Event()
        self.processes = []
        self.files = []
        self.thread = None
        self.error = None

    def snapshot(self):
        if self.mode == 'local':
            return {'local': parse_prom(request('/actuator/prometheus', self.token, text=True))}
        if set(ecs_targets()) != set(self.targets):
            raise RuntimeError('단계 중 ECS 작업 목록이 바뀌었습니다 — 측정 무효')
        boundary = time.time()
        def one(task):
            if self.processes:
                index = self.targets.index(task)
                path = self.directory / f'ecs-task-{index}.promlog'
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    sample = latest_sample(path.read_text())
                    if sample and sample[0] >= boundary:
                        values = sample[1]
                        break
                    if self.processes[index].poll() is not None:
                        raise RuntimeError('ECS Exec 연속 수집기가 종료됐습니다')
                    time.sleep(.1)
                else:
                    raise RuntimeError('ECS Exec 새 지표 샘플 시간초과')
            else:
                p = subprocess.run(ecs_command(task, True), capture_output=True, text=True, timeout=45)
                if p.returncode:
                    raise RuntimeError('ECS Exec 지표 수집 실패')
                values = parse_prom(p.stdout)
            if not any(key.startswith('hikaricp_connections_') for key in values):
                raise RuntimeError('ECS Exec 앱 지표가 비어 있습니다')
            return task, values
        with ThreadPoolExecutor(max_workers=len(self.targets)) as pool:
            return dict(pool.map(one, self.targets))

    def start(self):
        if self.mode == 'aws':
            for i, task in enumerate(self.targets):
                f = open(self.directory / f'ecs-task-{i}.promlog', 'w')
                self.files.append(f)
                self.processes.append(subprocess.Popen(ecs_command(task), stdin=subprocess.PIPE,
                    stdout=f, stderr=subprocess.STDOUT, start_new_session=True))
            return
        def sample():
            try:
                with open(self.directory / 'app.jsonl', 'w') as f:
                    while not self.stop.is_set():
                        f.write(json.dumps({'time': time.time(), 'metrics': self.snapshot()}) + '\n')
                        f.flush()
                        self.stop.wait(2)
            except Exception as e:
                self.error = str(e)
        self.thread = threading.Thread(target=sample, daemon=True)
        self.thread.start()

    def close(self):
        self.stop.set()
        if self.thread:
            self.thread.join(timeout=40)
        for p in self.processes:
            # Session Manager 자식 프로세스도 종료한다.
            import signal
            if p.poll() is None:
                os.killpg(p.pid, signal.SIGTERM)
            p.wait(timeout=10)
            p.stdin.close()
        for f in self.files:
            f.close()
        if self.error:
            raise RuntimeError('앱 지표 샘플러 실패: ' + self.error)
        if self.mode == 'aws':
            for i in range(len(self.targets)):
                if 'AWS_SAMPLE_END' not in (self.directory / f'ecs-task-{i}.promlog').read_text():
                    raise RuntimeError('ECS Exec 샘플이 없습니다 — 수집기 오류로 측정 무효')


def cache_delta(before, after):
    if set(before) != set(after):
        raise RuntimeError('단계 중 ECS 작업 구성이 바뀌었습니다 — 캐시 카운터 비교 무효')
    result = {}
    for label, prefix in [('recommendation', 'crates_recommendation_cache_total'), ('embedding', 'crates_ai_embedding_cache_total')]:
        totals = {k: 0 for k in ('hit', 'miss', 'error')}
        for task in before:
            for key, end in after[task].items():
                if not key.startswith(prefix):
                    continue
                match = re.search(r'result="(hit|miss|error)"', key)
                delta = end - before[task].get(key, 0)
                if delta < 0:
                    raise RuntimeError('앱 캐시 카운터가 초기화됐습니다 — 재시작 여부를 확인하세요')
                if match:
                    totals[match[1]] += delta
        denominator = totals['hit'] + totals['miss']
        result[label] = {**totals, 'hit_rate': totals['hit'] / denominator if denominator else None}
    return result


def pg_reset():
    pg('SELECT pg_stat_statements_reset();')


def pg_snapshot(path):
    raw = pg("""SELECT coalesce(json_agg(t),'[]'::json) FROM (
        SELECT calls, total_exec_time, mean_exec_time, rows, shared_blks_hit, shared_blks_read,
               left(regexp_replace(query, '\\s+', ' ', 'g'), 1000) AS query
          FROM pg_stat_statements WHERE query NOT LIKE '%pg_stat_statements%'
         ORDER BY total_exec_time DESC LIMIT 20) t;""")
    Path(path).write_text(raw)


def cloudwatch(directory, start, end):
    dimensions = {
        'ecs': ('AWS/ECS', {'ClusterName': os.environ['ECS_CLUSTER'], 'ServiceName': os.environ.get('ECS_SERVICE', 'backend')}, ['CPUUtilization', 'MemoryUtilization']),
        'rds': ('AWS/RDS', {'DBInstanceIdentifier': os.environ['RDS_INSTANCE_ID']}, ['CPUUtilization', 'DatabaseConnections', 'ReadIOPS']),
        'redis': ('AWS/ElastiCache', {'CacheClusterId': os.environ['REDIS_CLUSTER_ID'], 'CacheNodeId': os.environ.get('REDIS_NODE_ID', '0001')}, ['CPUUtilization', 'EngineCPUUtilization']),
        'qdrant': ('AWS/EC2', {'InstanceId': os.environ['QDRANT_INSTANCE_ID']}, ['CPUUtilization']),
    }
    queries = []
    for group, (namespace, dims, metrics) in dimensions.items():
        for name in metrics:
            for stat in ('Average', 'Maximum'):
                queries.append({'Id': f'm{len(queries)}', 'Label': f'{group}/{name}/{stat}',
                    'MetricStat': {'Metric': {'Namespace': namespace, 'MetricName': name,
                    'Dimensions': [{'Name': k, 'Value': v} for k, v in dims.items()]}, 'Period': 60, 'Stat': stat}, 'ReturnData': True})
    query_file = Path(directory) / 'cloudwatch-query.json'
    write_json(query_file, queries)
    # CloudWatch 지연 때문에 스윕 뒤 --collect-cloudwatch로 같은 시각을 다시 조회할 수 있다.
    stamp = lambda t: dt.datetime.fromtimestamp(t, dt.timezone.utc).isoformat()
    # 분 경계를 포함한 조회 창을 명시한다. 인접 단계와 겹치는 표본을 단계 전용 값으로 오해하지 않게 한다.
    raw = aws('cloudwatch', 'get-metric-data', '--metric-data-queries', f'file://{query_file}',
              '--start-time', stamp(int(start // 60) * 60), '--end-time', stamp((int(end // 60) + 1) * 60))
    (Path(directory) / 'cloudwatch.json').write_text(raw)
