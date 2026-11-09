"""전체 스윕·재실행의 소모 자원과 원본 배정을 측정 전에 검증한다."""
from collections import Counter, defaultdict
import json
import math
import os
import random
import time
from urllib.parse import quote

from common import request, rows, write_json
from keywords import KEYWORD_COUNT, Keywords
from model import counts

STABLE_SQL = """SELECT b.id, b.title, b.content_signature FROM board b JOIN users u ON u.id=b.user_id
 WHERE b.board_type='USER_CUSTOM' AND b.deleted_at IS NULL AND u.login_id ~ '^loadtest_u[0-9]+$'
 AND substring(u.login_id from '[0-9]+$')::int % 2=0 ORDER BY b.id"""
OWNED_SQL = """SELECT b.id, b.user_id, b.title, b.content_signature FROM board b JOIN users u ON u.id=b.user_id
 WHERE b.board_type='USER_CUSTOM' AND b.deleted_at IS NULL AND u.login_id ~ '^loadtest_u[0-9]+$'
 AND substring(u.login_id from '[0-9]+$')::int % 2=1 ORDER BY b.id"""


def signature(board):
    return board.get('content_signature') or ','.join(str(n) for n in sorted(c['id'] for c in board['contents']))


def identity(board):
    return board['title'] + '|' + signature(board)


def create_source(i):
    return (i // 600 + i) % 2 == 0


def unlike_candidates(liked, actors, protected, reserved):
    """종류별 실제 쌍 비중을 유지하면서 각 종류 안에서는 사용자를 순회한다."""
    rng = random.Random(20261108 + sum(k.startswith('unlike|') for k in reserved))
    pools = defaultdict(lambda: defaultdict(list))
    actor_ids = {u['id'] for u in actors}
    for r in liked:
        if (r['user_id'] in actor_ids and r['owner_id'] != r['user_id']
                and r['board_id'] not in protected
                and f"unlike|{r['user_id']}|{r['board_id']}" not in reserved):
            pools[r['board_type']][r['user_id']].append(r)
    queues = {}
    for kind, by_actor in sorted(pools.items()):
        users = sorted(by_actor)
        rng.shuffle(users)
        for items in by_actor.values():
            rng.shuffle(items)
        queues[kind] = [by_actor[uid][j] for j in range(max(map(len, by_actor.values())))
                        for uid in users if j < len(by_actor[uid])]
    served = Counter()
    result = []
    while queues:
        # 가중 공정 순회: 종류를 1:1로 강제하지 않고 남은 실제 좋아요 분포를 따른다.
        kind = min(queues, key=lambda k: ((served[k]+.5)/len(queues[k]), k))
        result.append(queues[kind][served[kind]])
        served[kind] += 1
        if served[kind] == len(queues[kind]):
            del queues[kind]
    return result


def sweep_plan(rates, seconds, warmup, repeats, skip_ai=False):
    specs = [(r, s) for r in list(rates) + [max(rates)] * (3 * repeats)
             for s in (warmup, seconds) if s]
    stages = [{k: v + 1 for k, v in counts(r, s, skip_ai).items()} for r, s in specs]
    return stages


def demands(stages):
    total = Counter()
    per_actor = {k: Counter() for k in ('stored_like', 'recommend_like', 'search_like', 'save_recommend', 'save_stored')}
    for stage in stages:
        total.update(stage)
        for name in ('stored_like', 'recommend_like', 'search_like'):
            for i in range(stage.get(name, 0)):
                per_actor[name][i % 600] += 1
        for i in range(stage['save_board']):
            per_actor['save_recommend' if create_source(i) else 'save_stored'][i % 600] += 1
    return total, per_actor


def content_sample(size=6000):
    source = rows('SELECT id, dtype AS "contentType" FROM content ORDER BY id')
    if len(source) < size:
        raise RuntimeError(f'전체 콘텐츠 표본 부족: {len(source)} < {size}')
    by_type = defaultdict(list)
    for c in source:
        by_type[c['contentType']].append(c)
    exact = {k: size * len(v) / len(source) for k, v in by_type.items()}
    quota = {k: math.floor(v) for k, v in exact.items()}
    for k in sorted(quota, key=lambda k: (-(exact[k]-quota[k]), k))[:size-sum(quota.values())]:
        quota[k] += 1
    rng = random.Random(20261108)
    sample = [c for k in sorted(by_type) for c in rng.sample(by_type[k], quota[k])]
    rng.shuffle(sample)
    return sample


class Resources:
    def __init__(self, directory, data):
        self.directory, self.data = directory, data
        self.actors = data['actors']
        self.reserved_file = directory / 'reserved.json'
        self.reserved = set(json.loads(self.reserved_file.read_text())) if self.reserved_file.exists() else set()
        self.catalog_file = directory / 'catalog.json'
        self.catalog = json.loads(self.catalog_file.read_text()) if self.catalog_file.exists() else {'recommend': [], 'search': []}
        self.keywords = Keywords(directory)
        self.public = rows(STABLE_SQL)
        random.Random(20261108).shuffle(self.public)
        if len(self.public) < 200:
            raise RuntimeError(f'안정적인 공개 원본 부족: {len(self.public)} < 200')
        self.owned = rows(OWNED_SQL)
        self.own_pairs = {f"{b['user_id']}|{identity(b)}" for b in self.owned}
        liked = rows("""SELECT f.user_id, f.board_id, b.user_id AS owner_id, b.board_type, b.title,
 b.content_signature, b.like_count, b.deleted_at FROM board_feedback f
 JOIN users u ON u.id=f.user_id JOIN board b ON b.id=f.board_id
 WHERE f.rating='LIKE' AND u.login_id ~ '^loadtest_u[0-9]+$'
 AND substring(u.login_id from '[0-9]+$')::int % 2=1 ORDER BY f.user_id, f.board_id""")
        self.liked = {(r['user_id'], r['board_id']) for r in liked}
        self.generated = {f"{r['user_id']}|{r['board_type']}|{identity(r)}" for r in liked}
        # 살아 있는 화면 소유 USER_CUSTOM은 취소로 like_count만 바뀐다.
        # 0이어도 삭제되지 않으며 stored_like·save_stored·capture는 좋아요 수에 의존하지 않는다.
        # 행동 풀 소유 보드는 edit/delete와 충돌할 수 있어 전체 수정 풀을 보호한다.
        stable_ids = {b['id'] for b in self.public}
        protected = {b['id'] for b in self.owned}
        capture_file = directory / 'capture-boards.json'
        if capture_file.exists():
            protected.update(b['boardId'] for b in json.loads(capture_file.read_text())
                             if b['boardId'] not in stable_ids)
        self.unlikes = unlike_candidates(liked, self.actors, protected, self.reserved)
        self.unlike_pool = {'by_type': dict(Counter(r['board_type'] for r in self.unlikes)),
                            'actors': len({r['user_id'] for r in self.unlikes}),
                            'protected_boards': len(protected), 'safe_shared_boards': len(stable_ids)}
        self.detail_cache = {}
        self.stable_capacity = {}

    def persist(self):
        write_json(self.reserved_file, sorted(self.reserved))
        write_json(self.catalog_file, self.catalog)

    def detail(self, bid):
        if bid not in self.detail_cache:
            self.detail_cache[bid] = request(f'/api/boards/list?boardId={bid}', self.actors[0]['token'])
        return self.detail_cache[bid]

    def like_key(self, user, board, category):
        kind = {'recommend': 'TASTE_RECOMMEND', 'search': 'SEARCH_RECOMMEND'}[category]
        return f"{user['id']}|{kind}|{identity(board)}"

    def can_like(self, user, board, category):
        key = self.like_key(user, board, category)
        return key not in self.reserved and key not in self.generated

    def can_save(self, user, board):
        pair = f"{user['id']}|{identity(board)}"
        return pair not in self.own_pairs and 'save|' + pair not in self.reserved

    def remaining(self, per_actor, name):
        result = {}
        category = 'recommend' if name in ('recommend_like', 'save_recommend') else 'search'
        pool = self.public if name in ('stored_like', 'save_stored') else self.catalog[category]
        for idx, need in per_actor[name].items():
            u = self.actors[idx]
            cache_key = (name, idx)
            if cache_key in self.stable_capacity:
                result[idx] = (need, self.stable_capacity[cache_key])
                continue
            if name == 'stored_like':
                n = sum((u['id'], b['id']) not in self.liked and f"{u['id']}|id|{b['id']}" not in self.reserved for b in pool)
            elif name.startswith('save_'):
                n = sum(self.can_save(u, b) for b in pool if name != 'save_recommend' or b['boardId'] is None)
            else:
                n = sum(self.can_like(u, b, category) for b in pool)
            result[idx] = (need, n)
            if name in ('stored_like', 'save_stored'):
                self.stable_capacity[cache_key] = n
        return result

    def prepare_tokens(self, per_actor, search_needed, report_path):
        started = time.monotonic()
        report = {'seconds': 0, 'requests': {}, 'shared_actors': len(self.actors), 'categories': {}}
        try:
            for category, names, setting in (
                    ('recommend', ('recommend_like', 'save_recommend'), 'RECOMMEND_PREP_INTERVAL'),
                    ('search', ('search_like',), 'SEARCH_PREP_INTERVAL')):
                began = time.monotonic()
                interval = float(os.environ.get(setting, '1'))
                if not math.isfinite(interval) or interval < 0:
                    raise ValueError(f'{setting}은 0 이상의 유한한 초여야 합니다')
                count, added, last_end = 0, 0, None
                initial = len(self.catalog[category])
                known = {identity(b) for b in self.catalog[category]}
                while True:
                    deficit = max((need-left for name in names
                                   for need, left in self.remaining(per_actor, name).values()), default=0)
                    if deficit <= 0:
                        break
                    # 한 후보를 600명에게 나눠 쓴다. 매 요청마다 전체 풀을 다시 세지 않는다.
                    before = len(known)
                    for _ in range(deficit if category == 'search' else math.ceil(deficit/4)):
                        if category == 'recommend':
                            idx = self.catalog.get('recommend_next_user', 0)
                            if idx >= len(self.actors):
                                break
                            path, user = '/api/boards/recommendation/user', self.actors[idx]
                        else:
                            if self.keywords.next + 1 + search_needed > KEYWORD_COUNT:
                                break
                            term = self.keywords.take(1)[0]
                            path, user = '/api/search/board?keyword='+quote(term['keyword']), self.actors[0]
                        if last_end is not None:
                            time.sleep(max(0, interval-(time.monotonic()-last_end)))
                        # 측정 밖 준비 요청이다. 학교 AI 서버가 가끔 연결 시간 초과(502)를 내도 준비 전체를 버리지 않게 몇 번 다시 묻는다.
                        for attempt in range(4):
                            try:
                                extra = request(path, user['token'])
                                break
                            except RuntimeError:
                                if attempt == 3:
                                    raise
                                time.sleep(5 * (attempt + 1))
                        last_end = time.monotonic()
                        count += 1
                        if category == 'recommend':
                            self.catalog['recommend_next_user'] = idx + 1
                        for b in extra if category == 'recommend' else [extra]:
                            if identity(b) not in known:
                                known.add(identity(b))
                                self.catalog[category].append(b)
                                added += 1
                        self.persist()
                        if count % 25 == 0:
                            print(f'{category} 토큰 준비: 요청 {count}, 고유 후보 +{added}, '
                                  f'{time.monotonic()-began:.1f}초 (측정 밖)', flush=True)
                    if len(known) == before and (category == 'search' or
                            self.catalog.get('recommend_next_user', 0) >= len(self.actors)):
                        break
                report['requests'][category] = count
                report['categories'][category] = {'seconds': time.monotonic()-began,
                    'interval_seconds': interval, 'initial_boards': initial,
                    'added_boards': added, 'boards': len(self.catalog[category])}
        finally:
            report['seconds'] = time.monotonic()-started
            write_json(report_path, report)
        print(f"전체 토큰 준비 {report['seconds']:.1f}초: "
              f"홈 요청 {report['requests'].get('recommend', 0)}, 검색 요청 {report['requests'].get('search', 0)} (측정 밖)", flush=True)
        return report

    def preflight(self, stages, report_path, expand=True):
        total, per_actor = demands(stages)
        fresh = rows("SELECT u.id FROM users u WHERE login_id ~ '^loadtest_new[0-9]+$' AND NOT EXISTS (SELECT 1 FROM user_vector v WHERE v.user_id=u.id)")
        available = sum(f"onboarding|{u['id']}" not in self.reserved for u in fresh)
        owned = [b for b in self.owned if f"owned|{b['id']}" not in self.reserved]
        report = {'stages': len(stages), 'tasks': dict(total), 'resources': {
            'onboarding': {'needed': total['onboarding'], 'available': available},
            'unlike': {'needed': total['unlike'], 'available': len(self.unlikes)},
            'edit_delete': {'needed': total['edit_board']+total['delete_board'], 'available': len(owned)},
            'search_keywords': {'needed': total['search_results'], 'available': KEYWORD_COUNT-self.keywords.next},
        }, 'stable_boards': len(self.public), 'tokens_expanded': False,
            'unlike_pool': getattr(self, 'unlike_pool', {})}

        def pair_report():
            for name in per_actor:
                values = self.remaining(per_actor, name)
                report['resources'][name] = {'needed': sum(x[0] for x in values.values()),
                    'available': sum(x[1] for x in values.values()),
                    'actors_short': sum(need > left for need, left in values.values()),
                    'max_needed_per_actor': max((x[0] for x in values.values()), default=0)}

        def failures():
            return [k for k,v in report['resources'].items() if v['needed'] > v['available'] or v.get('actors_short', 0)]

        pair_report()
        # DB 잔량부터 검사한다. 부족이 확정됐으면 AI 토큰을 만들며 스윕을 시작하지 않는다.
        basic = [k for k in failures() if k not in ('recommend_like', 'search_like', 'save_recommend')]
        if expand and not basic:
            report['token_preparation'] = self.prepare_tokens(per_actor, total['search_results'],
                                                             report_path.with_name('token-preparation.json'))
            report['tokens_expanded'] = True
            report['resources']['search_keywords']['available'] = KEYWORD_COUNT-self.keywords.next
            pair_report()
        report['shortages'] = failures()
        report['sufficient'] = not report['shortages']
        write_json(report_path, report)
        if not report['sufficient']:
            details = ', '.join(f"{k}(필요 {report['resources'][k]['needed']}, 잔량 {report['resources'][k]['available']}, "
                                f"부족 사용자 {report['resources'][k].get('actors_short', 0)})" for k in report['shortages'])
            raise RuntimeError('전체 실행 예산 부족: ' + details + f' — {report_path}')
        return report
