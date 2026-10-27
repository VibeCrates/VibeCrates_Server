// 좋아요→취소 짝이 고를 보드. run-sweep.sh가 측정 직전에 DB에서 뽑아 BOARD_IDS_FILE로 넘긴다.
//
// 지워질 수 있는 보드는 뺀다. 좋아요 0개가 된 AI·검색 보드는 완전히 지워진다. 무작위로 고른 보드가 그 계정이
// 이미 좋아요한 보드면 좋아요는 아무것도 바꾸지 않고 취소만 기존 좋아요를 지운다 — 그 좋아요가 유일했다면 보드가
// 사라져 데이터가 측정마다 줄고, 그 id를 다시 고르면 실패로 센다. 그래서 다음만 고른다.
//   사용자 보드  취소로 0개가 돼도 지워지지 않는다(주인이 지울 때만 소프트 삭제)
//   좋아요 2개 이상인 보드  이미 좋아요한 계정이 취소해도 1개 이상 남는다
// 좋아요·취소 쿼리는 어느 종류든 id로 행 하나를 고치는 같은 경로라, 고르는 범위를 좁혀도 측정값은 같다.
//
// k6를 직접 돌릴 때:
//   docker exec ... psql -tAc "SELECT json_agg(id) FROM board WHERE ..." > /tmp/boards.json   (run-sweep.sh의 쿼리)
//   BOARD_IDS_FILE=/tmp/boards.json ...

import { SharedArray } from 'k6/data';

const BOARD_IDS = new SharedArray('boards', () => {
  if (!__ENV.BOARD_IDS_FILE) {
    throw new Error('BOARD_IDS_FILE이 없습니다. run-sweep.sh로 돌리거나 머리말대로 보드 목록을 먼저 만드세요.');
  }
  return JSON.parse(open(__ENV.BOARD_IDS_FILE));
});

/** 좋아요→취소 짝을 보내도 지워지지 않는 보드 하나. */
export function pickBoardId() {
  return BOARD_IDS[Math.floor(Math.random() * BOARD_IDS.length)];
}
