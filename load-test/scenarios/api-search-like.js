// 검색 보드의 좋아요 → 취소. 검색 응답의 likeToken(서버가 서명한 검색어·구성)으로 좋아요를 누르면 그때
// 검색 보드로 저장되고(INSERT ... ON CONFLICT DO NOTHING), 취소로 좋아요가 0개가 되면 완전히 지워진다.
// 한 쌍이 끝나면 데이터가 원래대로 돌아온다. 응답 시간은 좋아요(search_like)와 취소(unlike)를 따로 판정한다.
//
// 좋아요에는 검색 응답의 토큰이 필요해 단독으로 보낼 수 없다. 검색은 setup에서 미리 해 토큰을 모아 둔다 —
// 측정 구간에 검색을 섞으면 AI 서버 왕복이 함께 잡힌다(검색 자체는 api-search-board로 잰다).
// 토큰에는 사용자 정보가 없어 어느 계정이든 쓸 수 있다. 계정은 돌아가며 붙이고, 반복 번호 순서로 하나씩 쓴다 —
// 같은 계정·같은 보드의 짝이 동시에 돌면 한쪽 취소가 다른 쪽이 저장한 보드를 지워 실패로 센다.
//
//   SEARCH_POOL=300   setup에서 검색할 수(서로 다른 조합 검색어)
import exec from 'k6/execution';
import { arrival } from '../lib/options.js';
import { record } from '../lib/metrics.js';
import { TOKENS } from '../lib/users.js';
import { searchKeyword } from '../lib/search-keywords.js';
import { searchBoard, likeSearchBoard, unlikeBoard } from '../lib/api.js';

const POOL = Number(__ENV.SEARCH_POOL || 300);

export const options = arrival({ targets: { search_like: 500, unlike: 500 } });

export function setup() {
  const boards = [];
  for (let i = 0; i < POOL; i++) {
    const token = TOKENS[i % TOKENS.length];
    const res = searchBoard(token, searchKeyword(i));
    if (res.status !== 200) {
      throw new Error(`검색 실패: ${res.status} ${String(res.body).slice(0, 200)}`);
    }
    if (res.json('data.liked')) continue;
    boards.push({ token, likeToken: res.json('data.likeToken') });
  }
  return { boards };
}

export default function ({ boards }) {
  const board = boards[exec.scenario.iterationInTest % boards.length];
  const liked = record(likeSearchBoard(board.token, board.likeToken), { name: 'search_like' });
  const boardId = liked.status === 200 ? liked.json('data.boardId') : null;
  if (boardId !== null) record(unlikeBoard(board.token, boardId), { name: 'unlike' });
}
