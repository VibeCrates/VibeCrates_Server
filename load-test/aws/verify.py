"""요청별 저장 id와 실제 BOARD_EDIT 행을 대응해 배치 저장의 무로그 경로를 검증한다."""
from collections import Counter
import json
import re

from common import rows, write_json


def records(log, marker):
    result = []
    for line in log.read_text().splitlines():
        try:
            message = json.loads(line).get('msg', '')
        except (ValueError, AttributeError):
            match = re.search(r'msg=("(?:\\.|[^"\\])*")', line)
            message = json.loads(match[1]) if match else ''
        if message.startswith(marker + ' '):
            result.append(json.loads(message[len(marker)+1:]))
    return result


def mutations(log, after_id, path):
    requests = records(log, 'MUTATION_VERIFICATION')
    events = rows(f"SELECT user_id, payload->>'savedBoardId' AS board_id FROM user_event_log WHERE event_type='BOARD_EDIT' AND id>{int(after_id)}")
    actual = Counter((r['user_id'], int(r['board_id'])) for r in events)
    expected = Counter((r['userId'], r['savedBoardId']) for r in requests if r['mode'] != 'layout')
    report = {'requests': len(requests), 'by_operation_mode': dict(Counter(r['operation']+':'+r['mode'] for r in requests)),
              'expected_board_edit': sum(expected.values()), 'actual_board_edit': len(events),
              'layout_requests': sum(r['mode']=='layout' for r in requests),
              'layout_events': sum(actual[(r['userId'],r['savedBoardId'])] for r in requests if r['mode']=='layout'),
              'titles_preserved': all(r['titlePreserved'] for r in requests),
              'passed': bool(requests) and actual == expected and all(r['savedBoardId'] and r['titlePreserved'] for r in requests)}
    write_json(path, report)
    if not report['passed']:
        raise RuntimeError(f'BOARD_EDIT 검증 실패: {path}')
    return report


def unlikes(log, manifest, after_id, path):
    requests = records(log, 'UNLIKE_VERIFICATION')
    assigned = {(t['user']['id'], t['boardId']): t for t in manifest['tasks']['unlike']}
    ids = ','.join(str(int(r['boardId'])) for r in requests) or 'NULL'
    remaining = rows(f'SELECT id, like_count FROM board WHERE id IN ({ids})')
    present = {b['id']: b['like_count'] for b in remaining}
    feedback = {(r['user_id'], r['board_id']) for r in rows(
        f"SELECT user_id, board_id FROM board_feedback WHERE rating='LIKE' AND board_id IN ({ids})")}
    events = Counter((r['user_id'], int(r['board_id'])) for r in rows(
        f"SELECT user_id, payload->'board'->>'boardId' AS board_id FROM user_event_log "
        f"WHERE event_type='BOARD_UNLIKE' AND id>{int(after_id)} "
        f"AND (payload->'board'->>'boardId')::bigint IN ({ids})"))
    expected = Counter((r['userId'], r['boardId']) for r in requests)
    deleted = {r['boardId'] for r in requests if r['deleted']}
    deleted_ids = ','.join(map(str, deleted)) or 'NULL'
    items = rows(f'SELECT board_id FROM board_item WHERE board_id IN ({deleted_ids})')
    by_board = Counter(r['boardId'] for r in requests)
    zero_expected = {bid for (uid, bid), t in assigned.items()
                     if bid in by_board and t['likeCountBefore'] == by_board[bid]
                     and (t['boardType'] != 'USER_CUSTOM' or t['softDeleted'])}
    checks = [r['ok'] and (r['userId'], r['boardId']) in assigned
              and (r['userId'], r['boardId']) not in feedback
              and r['boardId'] in (present.keys() | deleted)
              and (not r['deleted'] or (r['likeCount'] == 0 and
                   (assigned[(r['userId'], r['boardId'])]['boardType'] != 'USER_CUSTOM'
                    or assigned[(r['userId'], r['boardId'])]['softDeleted'])))
              and (r['deleted'] or (r['returnedBoardId'] == r['boardId'] and r['likeCount'] >= 0))
              for r in requests]
    report = {'requests': len(requests), 'by_type': dict(Counter(r['boardType'] for r in requests)),
              'deleted_by_type': dict(Counter(r['boardType'] for r in requests if r['deleted'])),
              'zero_like_expected': len(zero_expected), 'deleted_boards': len(deleted),
              'board_unlike_events': sum(events.values()),
              'feedback_remaining': sum((r['userId'], r['boardId']) in feedback for r in requests),
              'deleted_boards_remaining': len(deleted & present.keys()), 'deleted_items_remaining': len(items),
              'passed': bool(requests) and all(checks) and not (deleted & present.keys())
                        and not items and zero_expected <= deleted and events == expected}
    write_json(path, report)
    if not report['passed']:
        raise RuntimeError(f'unlike·완전 삭제 검증 실패: {path}')
    return report
