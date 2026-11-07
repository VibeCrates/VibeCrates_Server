"""한 단계의 일감을 미리 배정한다. 측정 중 로그인·조회·토큰 발급을 섞지 않는다."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import time
import random

from common import BASE, ROOT, login, pg, rows, write_json
from model import counts


def seed(n):
    sql = (ROOT / 'load-test/seed-aws-onboarding.sql').read_text()
    # Python 경로에서도 같은 SQL을 쓰되 psql 메타 명령 대신 검증한 정수를 넣는다.
    pg('\n'.join(l for l in sql.splitlines() if not l.startswith('\\')).replace(':new_users', str(n)))
    if int(pg("SELECT count(*) FROM users WHERE login_id ~ '^loadtest_new[0-9]+$'")) < n:
        raise RuntimeError('신규 계정 생성 실패 — seed.sh를 먼저 실행하세요')


def users(directory):
    path = directory / 'users.json'
    if path.exists():
        data = json.loads(path.read_text())
        if time.time() - data['at'] < 1800 and data.get('base') == BASE:
            return data
    source = rows("SELECT id, login_id FROM users WHERE login_id ~ '^loadtest_u[0-9]+$' ORDER BY substring(login_id from '[0-9]+$')::int")
    if len(source) != 1200:
        raise RuntimeError('화면 600명·행동 600명이 필요합니다. 시딩 v2를 확인하세요')
    with ThreadPoolExecutor(max_workers=4) as pool:
        authenticated = list(pool.map(login, source))
    # 번호의 짝/홀로 나누면 두 풀 모두 가입 시기 분포가 같다. 행동의 재계산이 홈 캐시를 무효화하지 않는다.
    data = {'at': time.time(), 'base': BASE, 'screens': authenticated[::2], 'actors': authenticated[1::2]}
    # 낮은 RATE의 짧은 검증도 앞 코호트 계정만 보지 않게 고정 난수로 순서를 섞는다.
    rng = random.Random(20261107)
    rng.shuffle(data['screens'])
    rng.shuffle(data['actors'])
    write_json(path, data)
    return data


def prepare(directory, rate, seconds, skip_ai=False):
    from planner import Resources, create_source, content_sample, identity
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    data = users(directory)
    resources = Resources(directory, data)
    actors = data['actors']
    actor_by_id = {u['id']: u for u in actors}
    expected = counts(rate, seconds, skip_ai)
    sizes = {name: n + 1 for name, n in expected.items()}
    resources.preflight([sizes], directory / 'stage-budget.json')
    tasks = {}
    for name in ('home', 'archive', 'search_results', 'content_detail', 'content_search', 'capture'):
        if name in sizes:
            tasks[name] = [{'user': data['screens' if name in ('home', 'archive', 'search_results') else 'actors'][i % 600]}
                           for i in range(sizes[name])]
    if 'search_results' in tasks:
        for task, term in zip(tasks['search_results'], resources.keywords.take(sizes['search_results'])):
            task.update(term)

    fresh = rows("SELECT u.id, u.login_id FROM users u WHERE login_id ~ '^loadtest_new[0-9]+$' AND NOT EXISTS (SELECT 1 FROM user_vector v WHERE v.user_id=u.id) ORDER BY u.id")
    fresh = [u for u in fresh if f"onboarding|{u['id']}" not in resources.reserved][:sizes['onboarding']]
    resources.reserved.update(f"onboarding|{u['id']}" for u in fresh)
    resources.persist()
    with ThreadPoolExecutor(max_workers=4) as pool:
        tasks['onboarding'] = [{'user': u} for u in pool.map(login, fresh)]

    tasks['stored_like'] = []
    for i in range(sizes['stored_like']):
        u = actors[i % 600]
        for b in resources.public:
            key = f"{u['id']}|id|{b['id']}"
            if (u['id'], b['id']) not in resources.liked and key not in resources.reserved:
                resources.reserved.add(key)
                tasks['stored_like'].append({'user': u, 'boardId': b['id']})
                break
        else:
            raise RuntimeError('저장 보드의 중복 없는 좋아요 쌍 부족')

    for name, category in [('recommend_like', 'recommend'), ('search_like', 'search')]:
        if name not in sizes:
            continue
        tasks[name] = []
        for i in range(sizes[name]):
            u = actors[i % 600]
            selected = next((b for b in resources.catalog[category] if resources.can_like(u, b, category)), None)
            if selected is None:
                raise RuntimeError(f'{name} 중복 없는 토큰 쌍 부족')
            resources.reserved.add(resources.like_key(u, selected, category))
            tasks[name].append({'user': u, 'likeToken': selected['likeToken']})

    tasks['unlike'] = []
    for r in resources.unlikes[:sizes['unlike']]:
        resources.reserved.add(f"unlike|{r['user_id']}|{r['board_id']}")
        tasks['unlike'].append({'user': actor_by_id[r['user_id']], 'boardId': r['board_id'],
                                'boardType': r['board_type'], 'likeCountBefore': r['like_count'],
                                'softDeleted': r['deleted_at'] is not None})

    owned_by_actor = {u['id']: [] for u in actors}
    for b in resources.owned:
        if f"owned|{b['id']}" not in resources.reserved:
            owned_by_actor[b['user_id']].append(b)
    owned = [items[j] for j in range(max(map(len, owned_by_actor.values()), default=0))
             for items in owned_by_actor.values() if j < len(items)]
    needed = sizes['edit_board'] + sizes['delete_board']
    for name, chunk in [('edit_board', owned[:sizes['edit_board']]), ('delete_board', owned[sizes['edit_board']:needed])]:
        tasks[name] = []
        for b in chunk:
            resources.reserved.add(f"owned|{b['id']}")
            tasks[name].append({'user': actor_by_id[b['user_id']], 'board': resources.detail(b['id']) if name == 'edit_board' else {'boardId': b['id']}})

    contents_file = directory / 'contents.json'
    contents = json.loads(contents_file.read_text()) if contents_file.exists() else content_sample()
    write_json(contents_file, contents)
    tasks['save_board'] = []
    for i in range(sizes['save_board']):
        u = actors[i % 600]
        create = create_source(i)
        pool = resources.catalog['recommend'] if create else resources.public
        for b in pool:
            if (create and b['boardId'] is not None) or not resources.can_save(u, b):
                continue
            board = b if create else resources.detail(b['id'])
            ids = [c['id'] for c in board['contents']]
            # JS payload()와 같은 교체다. 서로 다른 원본도 같은 저장 결과로 수렴하지 않게 예약한다.
            if i % 5 == 4:
                ids[i % 8] = next(c['id'] for c in contents if c['id'] not in ids)
            target = board['title'] + '|' + ','.join(map(str, sorted(ids)))
            pair = f"{u['id']}|{target}"
            if i % 5 != 3 and (pair in resources.own_pairs or 'save|' + pair in resources.reserved):
                continue
            resources.reserved.add(f"save|{u['id']}|{identity(b)}")
            resources.reserved.add('save|' + pair)
            tasks['save_board'].append({'user': u, 'board': board, 'create': create})
            break
        else:
            raise RuntimeError('저장 원본의 중복 없는 (행동 사용자, 원본) 쌍 부족')

    # 단일 인기 보드에 몰리지 않도록 256개를 준비 때 상세 조회하고 단계 사이에도 순회를 잇는다.
    capture_file = directory / 'capture-boards.json'
    capture_boards = json.loads(capture_file.read_text()) if capture_file.exists() else None
    if capture_boards is None:
        with ThreadPoolExecutor(max_workers=4) as pool:
            capture_boards = list(pool.map(resources.detail, [b['id'] for b in resources.public[:256]]))
        write_json(capture_file, capture_boards)
    position_file = directory / 'read-cursors.json'
    position = json.loads(position_file.read_text()) if position_file.exists() else {'capture': 0, 'content_detail': 0}
    for i, t in enumerate(tasks['capture']):
        t['board'] = capture_boards[(position['capture']+i) % len(capture_boards)]
    for i, t in enumerate(tasks['content_detail']):
        t['content'] = contents[(position['content_detail']+i) % len(contents)]
    for name in position:
        position[name] += sizes[name]
    write_json(position_file, position)
    manifest = {'rate': rate, 'seconds': seconds, 'skip_ai': skip_ai, 'tasks': tasks, 'expected': expected,
                'screens': data['screens'], 'contents': contents, 'run': f'{time.time_ns():x}'}
    resources.persist()
    write_json(directory / 'stage.json', manifest)
    print('일감 준비: ' + ', '.join(f'{k}={len(v)}' for k, v in tasks.items()), flush=True)
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='AWS 화면 측정의 신규 계정·토큰·행동 일감을 준비한다')
    parser.add_argument('--directory', default='load-test/results/aws-prepared')
    parser.add_argument('--new-users', type=int, default=20000)
    parser.add_argument('--rate', type=float, default=2)
    parser.add_argument('--seconds', type=int, default=30)
    parser.add_argument('--skip-ai', action='store_true')
    args = parser.parse_args()
    if args.new_users < 1 or args.rate <= 0 or args.seconds <= 0:
        parser.error('계정 수·RATE·초는 양수여야 합니다')
    seed(args.new_users)
    prepare(args.directory, args.rate, args.seconds, args.skip_ai)
