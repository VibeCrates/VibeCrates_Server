"""기존 자연어 목록을 읽고, 준비 디렉터리의 단조 증가 번호로 중복 없이 조합한다."""
import ast
import re
import unicodedata

from common import ROOT, write_json

SOURCE = (ROOT / 'load-test/lib/search-keywords.js').read_text()


def words(name):
    return ast.literal_eval(re.search(r'const ' + name + r' = (\[.*?\]);', SOURCE, re.S)[1])


SITUATIONS, MOODS, TOPICS = (words(n) for n in ('SITUATIONS', 'MOODS', 'TOPICS'))
N = len(SITUATIONS) * len(MOODS) * len(TOPICS)
KEYWORD_COUNT = N * 4


def keyword(index):
    if not 0 <= index < KEYWORD_COUNT:
        raise RuntimeError(f'자연어 검색 조합 소진: {index} / {KEYWORD_COUNT}')
    # 앞부분은 기존 4:1:1:1 비율. 빈 꼬리 N개가 소진되면 남은 종류 꼬리를 순회한다.
    boundary = N // 4 * 7
    if index < boundary:
        group, slot = divmod(index, 7)
        base, suffix = (group * 4 + slot, '') if slot < 4 else (group, [' 영화', ' 노래', ' 책'][slot - 4])
    else:
        group, slot = divmod(index - boundary, 3)
        base, suffix = (N // 4 + group), [' 영화', ' 노래', ' 책'][slot]
    base = base * 7919 % N
    return f'{SITUATIONS[base % len(SITUATIONS)]} {MOODS[base // len(SITUATIONS) % len(MOODS)]} {TOPICS[base // (len(SITUATIONS) * len(MOODS))]}{suffix}'


def embedding_key(text):
    # SearchService → Board.normalizeTitle(NFC, strip) → BatchingEmbeddingClient.
    # Java의 기본 \\s는 ASCII 6종이다. 임의의 Unicode 공백을 내부에서 줄이지 않는다.
    java_whitespace = '\t\n\x0b\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000'
    return 'emb:v1:' + re.sub(r'[ \t\n\x0b\f\r]+', ' ', unicodedata.normalize('NFC', text).strip(java_whitespace))


class Keywords:
    def __init__(self, directory):
        import json
        self.path = directory / 'search-cursor.json'
        self.next = json.loads(self.path.read_text())['next'] if self.path.exists() else 0

    def take(self, size):
        if self.next + size > KEYWORD_COUNT:
            raise RuntimeError(f'자연어 검색 조합 부족: 필요 {size}, 잔량 {KEYWORD_COUNT-self.next}')
        start = self.next
        self.next += size
        # API 준비 실패·중단에도 번호를 돌려 쓰지 않는다.
        write_json(self.path, {'next': self.next, 'total': KEYWORD_COUNT})
        return [{'keywordIndex': i, 'keyword': keyword(i), 'embeddingKey': embedding_key(keyword(i))}
                for i in range(start, self.next)]
