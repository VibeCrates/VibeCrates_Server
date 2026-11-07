"""준비 중의 DB·HTTP 호출. 비밀값·응답 본문은 오류 메시지에도 남기지 않는다."""
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
BASE = os.environ.get('BASE_URL', 'http://localhost:8080').rstrip('/')


def pg(sql):
    if os.environ.get('RESOURCE_MODE', 'local') == 'aws':
        cmd = ['psql', '-X', '-v', 'ON_ERROR_STOP=1', '-qAt']
    else:
        cmd = ['docker', 'exec', '-i', '-e', 'PGPASSWORD=' + os.environ['POSTGRESQL_PASSWORD'],
               'postgres_server', 'psql', '-X', '-U', os.environ['POSTGRESQL_USERNAME'],
               '-d', 'crates', '-v', 'ON_ERROR_STOP=1', '-qAt']
    p = subprocess.run(cmd, input=sql, text=True, capture_output=True)
    if p.returncode:
        raise RuntimeError('DB 명령 실패 — 접속·스키마·pg_stat_statements 권한을 확인하세요')
    return p.stdout.strip()


def rows(sql):
    return json.loads(pg(f'SELECT coalesce(json_agg(t), \'[]\'::json) FROM ({sql}) t;'))


def request(path, token=None, body=None, method=None, text=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=35) as res:
            raw = res.read().decode()
    except (urllib.error.URLError, TimeoutError) as e:
        code = getattr(e, 'code', '연결/시간초과')
        raise RuntimeError(f'API 실패: {path.split("?")[0]} ({code})') from None
    return raw if text else json.loads(raw)['data']


def login(user):
    return {**user, 'token': request('/api/auth/login', body={
        'loginId': user['login_id'], 'pwd': 'Loadtest!234'})['accessToken']}


def write_json(path, data):
    # 로그인·서명 토큰은 검증용 산출물에만 둔다. 저장소에는 results/가 이미 제외돼 있다.
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False))
    path.chmod(0o600)
