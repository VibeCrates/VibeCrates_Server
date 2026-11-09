# AWS 부하 테스트 후속 로컬 검증 (v4)

2026-11-08, `/Users/kyj/dev/java/crates`, 브랜치 `test/aws-load-test`.
커밋·push·AWS 작업, `git reset/stash/checkout --`, env 값 출력 없이 수행했다.
이번 후속 결과는 v4 기준이다. 이전 준비 장부의 예약·커서를 v4로 이어 받아 중복 배정을 막았다.
이후 재실행은 `load-test/results/aws-local-validation-v4/prepared`를 사용한다.
이전 v3 문서는 `results/aws-local-validation-v4/aws-validation.before.md`에 보존했다.

## 결과

**기본 스윕의 모든 일감 예산이 충분하다.** unlike 잔량은 **334,364쌍**으로 필요 65,112쌍을 넘는다.
검색 토큰은 최종 **287개**이며, 기존 좋아요·예약을 뺀 **172,117쌍**이 필요 162,653쌍을 넘는다.
총량뿐 아니라 행동 사용자별 검사도 모두 통과했다. 최종 `budget.json`은 `sufficient=true`, `shortages=[]`다.
기본 RATE 10→640의 실제 부하는 실행하지 않았고, 전체 준비·예산 검사와 로컬 RATE 2만 실행했다.

로컬 RATE 2, 워밍업 30초 + 측정 30초에서 **API 실패 0, drop 0**을 확인했다.
측정 중 TASTE 2건·SEARCH 2건 취소가 모두 성공했고, 마지막 좋아요 취소로 TASTE 2개·SEARCH 1개가 완전히 삭제됐다.

## unlike 후보와 다른 일감의 의존성

실제 LIKE가 있는 행동 사용자 600명의 모든 보드 종류를 조회하고, 자기 소유 보드와 이미 예약한 취소 쌍을 제외한다.
좋아요가 0인 추천·검색 보드는 `BoardService.unlike → deleteIfUnliked`에서 board_item을 먼저 지운 뒤 board를 지운다.
살아 있는 USER_CUSTOM은 좋아요가 0이어도 남는다. 서버 코드는 변경하지 않았다.

| 다른 일감 / 참조 | 취소 영향과 처리 |
|---|---|
| stored_like 원본 | 화면 풀 소유의 살아 있는 USER_CUSTOM 57,000개. 취소는 좋아요 수만 바꾸며, 새 LIKE 쌍과 겹치지 않아 안전하므로 유지 |
| save_board의 PUT 원본 | 같은 USER_CUSTOM 풀. 제목·구성·소유자·공개 상태가 바뀌지 않아 안전하므로 유지 |
| save_board의 POST 원본 | 서명 토큰의 제목·콘텐츠를 사용한다. 토큰은 DB 원본 id에 의존하지 않아 삭제 후에도 저장 가능 |
| capture 대상 | 현재 256개는 모두 위 안정 원본. 다른 준비 장부의 capture 보드가 안정 원본이 아니면 그 id를 취소 후보에서 제외 |
| 화면 풀 소유 USER_CUSTOM | 소유자는 행동 풀과 분리돼 수정·삭제하지 않는다. 취소로 삭제되지 않으므로 유지 |
| 행동 풀 소유 수정·삭제 대상 | 다른 행동 사용자의 좋아요 취소와 수정·삭제가 경합할 수 있어 전체 57,088개를 보호 |
| 추천·검색 토큰 좋아요 | 서명 토큰으로 제목·구성을 검증하고 DB 보드가 없으면 다시 만든다. 토큰에는 사용자 id가 없음 |

검증 전후 안정 원본 57,000개의 id·소유자·종류·제목·콘텐츠 구성·삭제 시각·공개 상태의 집계 해시가 같았다.
capture 256개도 모두 남아 있다. 원본: `dependency-verification.json` (`passed=true`).

종류별 실제 후보 쌍 비중에 따라 가중 순회하고, 각 종류 안에서 섞인 사용자를 순회한다.
예약 진행량으로 난수 시작값도 바꿔 단계마다 같은 앞 사용자에게 몰리지 않게 했다. 종류를 같은 비율로 강제하지 않는다.

| 보드 종류 | 현재 취소 잔량 | 실제 후보 비중 | 기본 예산 앞 65,112쌍의 배정 |
|---|---:|---:|---:|
| TASTE_RECOMMEND | 169,922 | 50.8195% | 33,089 |
| SEARCH_RECOMMEND | 145,674 | 43.5675% | 28,368 |
| USER_CUSTOM | 18,768 | 5.6130% | 3,655 |
| 합계 | 334,364 | 100% | 65,112 |

앞 65,112쌍은 행동 사용자 **600명 전원**, 사용자당 **106~111쌍**으로 분산됐다.
자기 보드 쌍 0, 수정·삭제 풀과의 교집합 0이다. 안전한 capture 원본과 겹치는 185쌍은 유지했다.
원본: `candidate-verification.json`.

## 측정 밖 토큰 준비와 시간

전체 기본 스윕의 사용자별 예산을 먼저 계산해 부족한 고유 후보만 추가한다.
검색 보드 하나의 서명 토큰을 행동 사용자 최대 600명에게 나눠 쓰는 기존 배정을 유지했다.
홈 추천도 같은 방식으로 추천 좋아요와 POST 저장 예산을 모두 확보한다. 충분하면 추가 요청하지 않는다.
두 수집은 직전 응답 이후 기본 1초 대기한다(`SEARCH_PREP_INTERVAL`, `RECOMMEND_PREP_INTERVAL`).

| 준비 | 홈 추천 | 검색 | 토큰 준비 시간 | 로그인·DB 검사 등을 포함한 전체 준비 |
|---|---|---|---:|---:|
| 최초 기본 스윕 준비 | 기존 1,922개 재사용, 요청 0 | 4→285개, 요청 281 | 308.6초 | 약 352.2초 |
| RATE 2 검증 뒤 보충 | 기존 풀 재사용, 요청 0 | 285→287개, 요청 2 | 5.1초 | 39.4초 |
| 합계 | 추가 요청 0 | 추가 요청 283 | 313.7초 | 약 391.6초 |

최초 전체 시간은 실행 로그 파일 생성부터 준비 완료 config 저장까지의 파일 시각으로 계산했으며 wrapper 준비 확인도 포함한다.
`full-preparation/wall-time.json`, 각 `token-preparation.json`에 근거를 남겼다.
이후 실행기는 `preparation.json`에 로그인·DB 검사·토큰 준비 시간을 직접 기록한다.
실제 RATE 2 스윕의 시작 전 준비와 두 구간 준비에서는 토큰 API 추가 요청이 모두 0이었다.
보충 뒤 최종 읽기 전용 예산 검사에는 별도로 30.8초가 걸렸다.

RATE 2 검증 후 재검사에서는 검색 총량 170,917쌍이 충분해도 방금 예약을 사용한 **11명**이 부족하다고 차단됐다.
사용자별 부족분을 검색 후보 2개(최대 1,200쌍)로 보충한 뒤 최종 예산을 다시 통과했다.
따라서 최종 상태에 남은 부족 종류는 없다. 다음 실행도 같은 장부를 사용해 검사를 다시 거치면 된다.

## 기본 스윕 전체 예산

RATE 10→20→40→80→160→320→640, 측정 120초 + 워밍업 30초, 정밀 3점×3회다.
포화 위치를 모르므로 정밀 반복의 세 점을 모두 RATE 640으로 잡은 보수적 상한이다.
총 32구간이며 모든 종류에 구간별 예비 일감 1개를 포함한다. 잔량은 이번 RATE 2 검증과 보충 이후 기준이다.
읽기 풀은 요청마다 소모되지 않으므로 순환 재사용한다.

| 일감 종류 | 전체 필요 | 현재 잔량 / 재사용 풀 | 판정 |
|---|---:|---|---|
| home | 717,092 | 화면 사용자 600명 순환 | 충분 |
| search_results | 221,477 | 고유 자연어 검색어 499,603개 | 충분 |
| archive | 105,482 | 화면 사용자 600명 순환 | 충분 |
| onboarding | 10,577 | 미선택·미예약 계정 19,980명 | 충분 |
| content_detail | 316,382 | 층화 콘텐츠 표본 6,000개 순환 | 충분 |
| recommend_like | 195,115 | 1,153,095쌍 | 충분 |
| stored_like | 292,657 | 34,181,202쌍 | 충분 |
| search_like | 162,653 | 172,117쌍 | 충분 |
| unlike | 65,112 | 334,364쌍 | 충분 |
| save_board | 105,482 | 아래 원본별 쌍 검사 | 충분 |
| edit_board | 35,161 | 수정·삭제 공유 잔량 57,060개 | 충분 |
| content_search | 56,251 | 3글자 이상 기존 검색어 71개 순환 | 충분 |
| capture | 21,122 | 미리 상세를 받은 안정 원본 256개 순환 | 충분 |
| delete_board | 5,305 | 수정·삭제 합산 필요 40,466개 < 57,060개 | 충분 |

| 사용자별 쌍 자원 | 전체 필요 | 잔량 | 사용자별 최대 필요 | 부족 사용자 |
|---|---:|---:|---:|---:|
| stored_like | 292,657 | 34,181,202 | 501 | 0 |
| recommend_like | 195,115 | 1,153,095 | 334 | 0 |
| search_like | 162,653 | 172,117 | 281 | 0 |
| save_recommend (POST) | 52,744 | 1,152,571 | 106 | 0 |
| save_stored (PUT) | 52,738 | 34,199,975 | 106 | 0 |

원본: `default-budget-final/budget.json`. 읽기 풀은 실제 준비 파일과 원본 상태 검증으로 확인했다.
기본 스윕에는 신규 계정, 취소 쌍, 저장 쌍, 수정·삭제 대상의 추가 시딩이 필요하지 않다.

## RATE 2, 30초 실측

```bash
K6_BIN="$PWD/load-test/results/tools/k6" REDIS_URL=redis://localhost:6379/0 \
  ./load-test/run-aws-sweep.sh 2 --seconds 30 --warmup-seconds 30 --refine-repeats 0 \
  --verify-mutations --verify-unlikes \
  --prepared-directory load-test/results/aws-local-validation-v4/prepared \
  --output load-test/results/aws-local-validation-v4/sweep
```

| 구간 | API 건수 | 실패율 | drop | 구간 내 완료율 | TASTE / SEARCH 취소 | TASTE / SEARCH 완전 삭제 |
|---|---:|---:|---:|---:|---:|---:|
| 워밍업 30초 | 266 | 0.00% | 0 | 97.86% | 2 / 2 | 1 / 0 |
| 측정 30초 | 266 | 0.00% | 0 | 97.86% | 2 / 2 | 2 / 1 |

측정 API/s는 8.87이다. API 20종의 실패율이 모두 0이며 화면·API p95 목표를 모두 통과했다.
측정 home p95 202.0ms, search_results 196.4ms, unlike 42.2ms다.
홈 캐시는 hit 22 / miss 19 = 53.66%, 검색 캐시는 hit 0 / miss 13 / error 0이다.

각 구간에서 다음을 DB와 직접 대조했다.

- 취소 성공 요청 4개와 BOARD_UNLIKE 4행이 사용자·원본 보드별로 일치했다.
- 취소한 피드백 잔존 0, 완전 삭제 응답을 받은 board 잔존 0, 해당 board_item 잔존 0이었다.
- 측정에서 좋아요 0이 될 것으로 예상한 3개가 모두 `boardId=null, likeCount=0`으로 응답하고 실제 삭제됐다.
- BOARD_EDIT는 구간마다 제목·콘텐츠 변경 2건 = 실제 로그 2행, 배치 요청 7건 = 로그 0행이었다.

각 구간의 `unlike-verification.json`, `mutation-verification.json`은 모두 `passed=true`다.
낮은 RATE의 짧은 계약 검증이며 AWS 용량 상한을 추정하는 결과는 아니다.

## 실행 명령과 산출물

전체 토큰을 확보하되 부하를 실행하지 않는 옵션을 추가했다.

```bash
./load-test/run-aws-sweep.sh --prepare-only \
  --prepared-directory load-test/results/aws-local-validation-v4/prepared \
  --output load-test/results/aws-local-validation-v4/full-preparation

# 위 RATE 2 실행 뒤 같은 옵션으로 post-validation-preparation 디렉터리에 부족분을 보충했다.
./load-test/run-aws-sweep.sh --budget-only \
  --prepared-directory load-test/results/aws-local-validation-v4/prepared \
  --output load-test/results/aws-local-validation-v4/default-budget-final
```

`--prepare-only`는 전체 토큰 준비 후 종료하고, `--budget-only`는 토큰 추가 없이 검사만 한다.
최종 예산 검사 종료 코드는 0이며 해당 디렉터리에 워밍업·부하 단계·k6 로그는 생성되지 않았다.
산출물은 git에서 제외된 `load-test/results/aws-local-validation-v4/`에 있다.
로그인·서명 토큰을 담은 파일은 권한 600을 유지했다.

Python 단위 검사 **17개 통과**, 변경 JS의 `node --check`, 실행 wrapper의 `bash -n` 검사를 통과했다.
실제 k6에서 워밍업·측정 각 30초를 실행했고 API/HTTP 실패율 모두 0이었다.

## 이번 후속 변경 파일

| 파일 | 요약 |
|---|---|
| `load-test/aws/planner.py` | 모든 종류의 실제 LIKE 취소 후보, 위험 참조 보호, 사용자·실제 종류 비중 순회, 전체 토큰 준비와 간격·시간 기록 |
| `load-test/aws/prepare.py` | 취소 일감에 원본 종류·이전 좋아요 수·삭제 상태 배정 |
| `load-test/aws/sweep.py` | `--prepare-only`, `--verify-unlikes`, 전체 준비 시간과 취소 검증 결과 저장 |
| `load-test/aws/verify.py` | 취소 응답·BOARD_UNLIKE·피드백 제거·board/board_item 완전 삭제 대조 |
| `load-test/aws/test_tools.py` | 후보 제외·안전 원본 유지·분포·사용자 분산·검색/홈 토큰 공유·실제 삭제 회귀 검사 |
| `load-test/scenarios/aws-screens.js` | 취소 종류 태그와 선택적 요청별 응답 기록 |
| `load-test/README-aws.md` | 후보·의존성·전체 준비·시간·검증 옵션 설명 |
| `load-test/aws-validation.md` | 이번 예산·토큰 준비·로컬 실측·변경 파일 기록 |
