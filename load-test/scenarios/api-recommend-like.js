// 홈 추천 보드의 좋아요 → 취소. 아직 저장되지 않은 추천 보드에 좋아요를 누르면 그때 AI 보드로 저장되고
// (INSERT ... ON CONFLICT DO NOTHING), 취소로 좋아요가 0개가 되면 완전히 지워진다(콘텐츠 → 보드).
// 그래서 한 쌍이 끝나면 데이터가 원래대로 돌아온다. 응답 시간은 좋아요(recommend_like)와 취소(unlike)를 따로 판정한다.
//
// 추천은 setup에서 미리 받는다. 측정 구간에 추천 요청을 섞으면 Qdrant 계산이 함께 잡혀 저장·삭제 비용이 가려진다
// (추천 자체는 api-recommend로 잰다). 계정마다 아직 좋아요하지 않은 보드를 모아 두고, 반복 번호 순서로 하나씩 쓴다 —
// 같은 계정·같은 보드의 짝이 동시에 돌면 한쪽 취소가 다른 쪽이 저장한 보드를 지워 실패로 센다.
//
// 좋아요·취소마다 취향 벡터 재계산이 비동기로 돈다. 추천을 setup에서 받아 두므로 측정 중 벡터가 바뀌어도
// 고른 보드는 그대로 유효하다.
import exec from 'k6/execution';
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { TOKENS } from '../lib/users.js';
import { recommendation, likeRecommendedBoard, unlikeBoard } from '../lib/api.js';

export const options = arrival({ targets: { recommend_like: 500, unlike: 500 } });

export function setup() {
  const boards = [];
  for (const token of TOKENS) {
    const res = recommendation(token);
    if (res.status !== 200) {
      throw new Error(`추천 조회 실패: ${res.status} ${String(res.body).slice(0, 200)}`);
    }
    for (const board of res.json('data')) {
      if (board.liked) continue;
      boards.push({ token, title: board.title, contentIds: board.contents.map((c) => c.id) });
    }
  }
  if (boards.length === 0) throw new Error('좋아요할 추천 보드가 없습니다');
  return { boards };
}

export default function ({ boards }) {
  const board = boards[exec.scenario.iterationInTest % boards.length];
  const liked = record(likeRecommendedBoard(board.token, board.title, board.contentIds), { name: 'recommend_like' });
  const boardId = liked.status === 200 ? liked.json('data.boardId') : null;
  if (boardId !== null) record(unlikeBoard(board.token, boardId), { name: 'unlike' });
}
