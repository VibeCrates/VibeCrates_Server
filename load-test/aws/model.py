"""화면 비율과 도착 수를 준비·실행·판정에서 같은 계산으로 쓴다."""
import math
import json
from pathlib import Path

SHARES = json.loads(Path(__file__).with_name('shares.json').read_text())
SCREENS = {'home', 'search_results', 'archive', 'onboarding'}


def counts(rate, seconds, skip_ai=False):
    # 검색을 뺀 로컬 검증은 남은 비율을 늘리지 않는다. 원래 화면 RATE의 의미를 유지한다.
    return {k: math.ceil(math.floor(rate * v * 10000 + .5) * seconds / 10000 - 1e-10)
            for k, v in SHARES.items() if not (skip_ai and k in {'search_results', 'search_like'})}
