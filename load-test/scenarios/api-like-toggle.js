// 좋아요 → 취소 한 쌍. 쓰기 경로와 그 뒤의 비동기 취향 벡터 재계산을 함께 잰다. 응답 시간은 좋아요(like)와
// 취소(unlike)를 따로 판정한다. 도착률(RATE)은 쌍의 수다 — 요청은 그 두 배다.
//
// 재계산은 좋아요·취소마다 커밋 후 비동기로 돈다. 좋아요한 보드 전부의 콘텐츠 벡터를 Qdrant에서
// 받아 평균을 내므로(1개월차 가입자면 720보드 × 8 = 5,760개) 응답 시간에는 안 보이고 CPU에만 보인다.
// 그래서 이 시나리오는 응답 시간보다 backend·qdrant CPU를 같이 봐야 한다.
//
// 보드는 지워지지 않는 보드 중에서 무작위로 고른다(lib/boards.js). 이미 좋아요한 보드를 고를 확률은 계정당
// 최대 720/364,800 ≈ 0.2%이고, 그때는 시딩한 좋아요 하나가 취소되어 사라진다(보드는 남는다). 측정을 많이 반복하면
// prepare.sh로 다시 만든다.
//
// 상태 — 좋아요와 like_count는 짝으로 원래대로 돌아온다. 다만 두 재계산이 경합하면 취향 벡터가
// "좋아요 포함" 값으로 남을 수 있다(recalc-vectors.sh 머리말). 추천 측정 전에 이 시나리오를 돌렸다면 감안할 것.
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { pick } from '../lib/users.js';
import { pickBoardId } from '../lib/boards.js';
import { likeBoard, unlikeBoard } from '../lib/api.js';

export const options = arrival({ targets: { like: 500, unlike: 500 } });

export default function () {
  const token = pick();
  const boardId = pickBoardId();
  record(likeBoard(token, boardId), { name: 'like' });
  record(unlikeBoard(token, boardId), { name: 'unlike' });
}
