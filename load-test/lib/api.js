// Crates API 호출 묶음. 시나리오는 이 파일만 알면 된다.
//
// 부하 생성기는 "밖에서 본 시간"만 잰다. 앱 내부(재계산 소요, 커넥션 풀 대기)는
// /actuator/prometheus 에서, DB 쪽은 pg_stat_statements 에서 따로 본다.
// 세 층을 한 도구로 보려 하지 말 것 — docs/load-test-and-metrics.md 참고.

import http from 'k6/http';

export const BASE = __ENV.BASE_URL || 'http://localhost:8080';

/** content.id 상한. 시딩 결과가 바뀌면 같이 바꿀 것 (기본: 벡터 있는 콘텐츠만, 중복 제거 — BOOK 107,225 + MOVIE 39,237 + MUSIC 36,674). */
export const MAX_CONTENT_ID = Number(__ENV.MAX_CONTENT_ID || 183136);


/** 테스트로 만든 데이터를 나중에 골라내기 위한 접두사. cleanup.sql이 이 값을 쓴다. */
export const PREFIX = 'loadtest';

const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json' };

function auth(token) {
  return { headers: { ...JSON_HEADERS, Authorization: `Bearer ${token}` } };
}

/**
 * 미리 준비된 테스트 계정으로 로그인한다.
 *
 * **계정을 만들지 않는다.** 데이터 준비는 load-test/seed.sh 의 몫이다.
 * 예전에는 여기서 회원가입까지 했는데, 그러면 부하 생성기가 상태를 만들게 되어
 * 실행마다 출발점이 달라지고 비교가 깨진다(docs 4-9). 없으면 바로 실패시킨다 —
 * 조용히 만들어내면 같은 문제가 되돌아온다.
 */
export function login(tag) {
  const res = http.post(
    `${BASE}/api/auth/login`,
    JSON.stringify({ loginId: `${PREFIX}_${tag}`, pwd: 'Loadtest!234' }),
    { headers: JSON_HEADERS },
  );
  if (res.status !== 200) {
    throw new Error(
      `계정 ${PREFIX}_${tag} 로그인 실패(${res.status}). ` +
      `먼저 ./load-test/seed.sh 로 데이터를 준비하세요.`,
    );
  }
  return res.json('data.accessToken');
}

/**
 * 홈 추천 보드에 좋아요. 추천 응답의 likeToken(서버가 서명한 제목·구성)을 보낸다.
 *
 * 같은 구성의 AI 보드가 있으면 거기에 좋아요하고, 없으면 저장(INSERT ... ON CONFLICT DO NOTHING)한 뒤 좋아요한다.
 * 저장된 보드는 좋아요를 취소해 0개가 되면 완전히 지워진다 — 좋아요→취소 짝이면 데이터가 원래대로 돌아온다.
 */
export function likeRecommendedBoard(token, likeToken) {
  return http.post(
    `${BASE}/api/boards/likes`,
    JSON.stringify({ likeToken }),
    { ...auth(token), tags: { name: 'POST /api/boards/likes' } },
  );
}

/**
 * 검색 보드에 좋아요. 검색 응답의 likeToken(서버가 서명한 검색어·구성)을 보낸다.
 * 홈 추천 보드와 같이 없으면 저장하고, 취소로 0개가 되면 지워진다.
 */
export function likeSearchBoard(token, likeToken) {
  return http.post(
    `${BASE}/api/search/board/likes`,
    JSON.stringify({ likeToken }),
    { ...auth(token), tags: { name: 'POST /api/search/board/likes' } },
  );
}

/** 이미 저장된 보드에 좋아요. signature 조회가 없는 순수 좋아요 경로. */
export function likeBoard(token, boardId) {
  return http.post(`${BASE}/api/boards/${boardId}/likes`, null, {
    ...auth(token),
    tags: { name: 'POST /api/boards/{id}/likes' },
  });
}

/**
 * 좋아요 취소. 쓰기 시나리오가 좋아요와 짝으로 보내 좋아요 이력을 원래대로 유지한다.
 * 좋아요가 0개가 된 AI·검색 보드는 이때 완전히 지워진다(콘텐츠 → 보드 순).
 */
export function unlikeBoard(token, boardId) {
  return http.del(`${BASE}/api/boards/${boardId}/likes`, null, {
    ...auth(token),
    tags: { name: 'DELETE /api/boards/{id}/likes' },
  });
}

/**
 * 공개 보드 목록에서 boardId만 골라낸다. seed.sh가 만들어둔 보드 풀을 집어올 때 쓴다.
 *
 * 보관함(/api/boards/mine)이 아니라 이 경로를 쓰는 이유: 보관함은 "내가 만들었거나
 * 좋아요한" 보드만 준다. 좋아요 0건으로 시딩한 계정에는 빈 목록이 돌아온다.
 * 인기 보드는 소유와 무관하게 공개 보드를 주므로 풀 전체가 보인다.
 */
export function publicBoardIds(token, count) {
  const res = http.get(`${BASE}/api/boards/liked?n=${count}`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/liked' },
  });
  if (res.status !== 200) {
    throw new Error(`보드 풀 조회 실패: ${res.status} ${String(res.body).slice(0, 200)}`);
  }
  return res.json('data').map((b) => b.boardId).filter((id) => id !== null);
}

/** 내 보관함에서 boardId 목록만. */
export function myBoardIds(token, size) {
  const res = http.get(`${BASE}/api/boards/mine?filter=ALL&page=0&size=${size}`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/mine' },
  });
  if (res.status !== 200) {
    throw new Error(`보관함 조회 실패: ${res.status} ${String(res.body).slice(0, 200)}`);
  }
  return res.json('data.content').map((b) => b.boardId).filter((id) => id !== null);
}

/**
 * 오늘의 추천 보드 4장. 캐시가 없으면 Qdrant를 두 번 부른다 — 취향 근처 제목 후보 200개(벡터 포함),
 * 보드 4장 × 종류 3개의 콘텐츠 묶음 검색. 그다음 보드마다 같은 구성의 저장된 AI 보드를 찾는다(4회).
 * 캐시(Redis)가 켜져 있으면 취향 벡터가 바뀔 때까지 Qdrant를 건너뛴다.
 */
export function recommendation(token) {
  return http.get(`${BASE}/api/boards/recommendation/user`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/recommendation/user' },
  });
}

/** 보관함. filter는 ALL | LIKED | CREATED. */
export function archive(token, filter, page, size) {
  return http.get(`${BASE}/api/boards/mine?filter=${filter}&page=${page}&size=${size}`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/mine' },
  });
}

// ── 도착률 시나리오용 ─────────────────────────────────────────────
// 파라미터는 프론트(crates-frontend)가 실제로 보내는 값에 맞춘다. 바뀌면 같이 바꿀 것.

/** 홈 인기 보드. 프론트 POPULAR_BOARD_COUNT = 8. */
export function popular(token) {
  return http.get(`${BASE}/api/boards/liked?n=8`, {
    ...auth(token),
    tags: { name: 'GET /api/boards/liked' },
  });
}

/** 홈 "내 보드" 섹션. 프론트 MY_BOARD_COUNT = 8. 보관함 첫 페이지(20건)와 같은 쿼리다. */
export function homeMyBoards(token) {
  return archive(token, 'ALL', 0, 8);
}

/**
 * 검색 보드. AI 서버 임베딩(캐시가 켜져 있으면 Redis 먼저) → Qdrant 콘텐츠 검색 → 같은 제목·구성의 저장된 검색 보드 조회
 * → 좋아요 토큰 서명. 제목은 검색어 그대로라 query 매칭은 없다.
 */
export function searchBoard(token, keyword) {
  return http.get(`${BASE}/api/search/board?keyword=${encodeURIComponent(keyword)}`, {
    ...auth(token),
    tags: { name: 'GET /api/search/board' },
  });
}

/** 콘텐츠 제목 검색(보드 수정 화면). 프론트 SEARCH_PAGE_SIZE = 20. pg_trgm 인덱스를 탄다. */
export function searchContents(token, keyword) {
  return http.get(`${BASE}/api/contents/search?keyword=${encodeURIComponent(keyword)}&page=0&size=20`, {
    ...auth(token),
    tags: { name: 'GET /api/contents/search' },
  });
}

/** 콘텐츠 상세. dtype은 BOOK | MOVIE | MUSIC. */
export function contentDetail(token, dtype, contentId) {
  return http.get(`${BASE}/api/contents/${dtype}/${contentId}`, {
    ...auth(token),
    tags: { name: 'GET /api/contents/{dtype}/{id}' },
  });
}

/** 콘텐츠 요약. 상세 시나리오의 setup이 id의 종류(dtype)를 알아낼 때만 쓴다. */
export function contentSummary(token, contentId) {
  return http.get(`${BASE}/api/contents/${contentId}`, {
    ...auth(token),
    tags: { name: 'setup content summary' },
  });
}

/** 공통 로그인 가드. 앱을 처음 열 때 상태를 받은 뒤에 본문을 연다(프론트가 세션 동안 캐시). */
export function onboardingStatus(token) {
  return http.get(`${BASE}/api/users/me/onboarding`, {
    ...auth(token),
    tags: { name: 'GET /api/users/me/onboarding' },
  });
}

/** 홈 본문이 함께 보내는 요청. 호출하지 않고 http.batch에 넘길 모양만 만든다. */
export function homeRequests(token) {
  const paths = {
    recommend: ['/api/boards/recommendation/user', 'GET /api/boards/recommendation/user'],
    popular: ['/api/boards/liked?n=8', 'GET /api/boards/liked'],
    my_boards: ['/api/boards/mine?filter=ALL&page=0&size=8', 'GET /api/boards/mine'],
  };
  return Object.fromEntries(Object.entries(paths).map(([name, [path, label]]) => [name, {
    method: 'GET',
    url: `${BASE}${path}`,
    params: { ...auth(token), tags: { name: label } },
  }]));
}
