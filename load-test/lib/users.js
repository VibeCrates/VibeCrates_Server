// 측정에 쓸 계정 토큰. lib/tokens.sh가 미리 받아 둔 파일을 읽는다(run-sweep.sh가 TOKENS_FILE로 넘긴다).
//
// 로그인을 k6 setup에서 하지 않는 이유는 tokens.sh 머리말에 있다(BCrypt 200번이 자원 기록에 섞인다).
// k6를 직접 돌릴 때:
//   ./load-test/lib/tokens.sh /tmp/tokens.json && TOKENS_FILE=/tmp/tokens.json RATE=10 k6 run ...

import { SharedArray } from 'k6/data';

export const TOKENS = new SharedArray('tokens', () => {
  if (!__ENV.TOKENS_FILE) {
    throw new Error('TOKENS_FILE이 없습니다. ./load-test/lib/tokens.sh <파일> 로 먼저 토큰을 받으세요.');
  }
  return JSON.parse(open(__ENV.TOKENS_FILE));
});

/** 이번 요청을 보낼 계정. 도착률 실행기는 VU가 요청마다 바뀌므로 무작위로 고른다. */
export function pick() {
  return TOKENS[Math.floor(Math.random() * TOKENS.length)];
}

/** setup처럼 계정이 상관없는 곳에서 쓰는 토큰 하나. */
export function anyToken() {
  return TOKENS[0];
}
