// 검색 보드 전용 검색어. 콘텐츠 제목 검색은 keywords.js를 그대로 쓴다(그쪽 측정 조건을 바꾸지 않으려고).
//
// 왜 따로 두나 — keywords.js는 100개뿐이라, 10분에 1만 8천 번 검색하면 같은 검색어가 계속 반복돼 검색어 임베딩 캐시
// 적중률이 99%를 넘는다. 실제 검색어는 잘 겹치지 않으므로 지나치게 좋은 결과가 나온다. 그리고 거의 전부가
// domain=all이라 "한 종류 8개" 경로를 재지 못한다.
//
// 만드는 법 — "상황 + 분위기 + 주제"를 조합하고(50 × 50 × 50 = 125,000가지), 종류 꼬리를 붙인다.
//   꼬리 없음 : 영화 : 노래 : 책 = 4 : 1 : 1 : 1 → AI 서버가 all : movie : music : book으로 판단한다.
//   예) "비 오는 날 잔잔한 우주", "새벽에 쓸쓸한 기억 영화", "퇴근길에 신나는 여행 노래"
// 단어에는 작품 종류 명사(영화·노래·음악·책·소설…)를 넣지 않는다 — 꼬리로만 domain이 정해지게.
//
// 고르는 방식(SEARCH_KEYWORD_MODE)
//   unique (기본) 반복 번호로 매번 다른 조합을 고른다. 검색어가 거의 겹치지 않는 경우 — 캐시가 거의 도움이 안 되는 최악.
//                 k6는 단계마다 따로 실행되므로, 단계(RATE·DURATION)마다 시작 위치를 달리해 단계 사이 겹침을 줄인다.
//   zipf          인기 순위 분포(1/순위)로 고른다. 소수 검색어가 자주 나오는 실제 서비스에 가까운 모양 — 캐시 효과를 본다.
import exec from 'k6/execution';

const SITUATIONS = [
  '비 오는 날', '새벽에', '퇴근길에', '주말 오후에', '잠들기 전에', '여름 밤에', '겨울 아침에', '공부할 때', '운동할 때', '드라이브할 때',
  '혼자 있을 때', '여행 가는 길에', '카페에서', '바닷가에서', '산책할 때', '출근길에', '휴일 아침에', '눈 오는 날', '봄날 오후에', '가을 저녁에',
  '생일에', '이별한 날', '시험 끝난 날', '비행기에서', '기차에서', '캠핑할 때', '청소할 때', '요리할 때', '야근할 때', '우울할 때',
  '설레는 날', '지친 날', '한가한 오후에', '늦은 밤에', '해 질 녘에', '첫눈 오는 날', '장마철에', '연휴에', '명절에', '크리스마스에',
  '새해 첫날에', '졸업식 날', '이사한 날', '비 그친 뒤에', '안개 낀 아침에', '바람 부는 날', '더운 날', '추운 날', '소풍 가는 날', '퇴사한 날',
];
const MOODS = [
  '잔잔한', '신나는', '쓸쓸한', '따뜻한', '몽환적인', '강렬한', '감성적인', '유쾌한', '슬픈', '설레는',
  '차분한', '웅장한', '어두운', '밝은', '포근한', '긴장감 넘치는', '아련한', '경쾌한', '묵직한', '청량한',
  '낭만적인', '그리운', '담담한', '짜릿한', '귀여운', '신비로운', '고요한', '뭉클한', '희망찬', '나른한',
  '서정적인', '통쾌한', '아늑한', '화려한', '소박한', '진지한', '엉뚱한', '기묘한', '섬세한', '거친',
  '부드러운', '시원한', '애틋한', '활기찬', '고독한', '따스한', '은은한', '벅찬', '잔혹한', '평화로운',
];
const TOPICS = [
  '우주', '바다', '사막', '도시', '숲', '기차', '고양이', '강아지', '첫사랑', '이별',
  '성장', '우정', '가족', '복수', '모험', '생존', '시간여행', '로봇', '인공지능', '마법',
  '전쟁', '범죄', '여행', '요리', '청춘', '학교', '회사', '시골', '섬', '별',
  '달', '비밀', '기억', '꿈', '거짓말', '용기', '자유', '외로움', '위로', '행복',
  '운명', '영웅', '괴물', '유령', '탐정', '왕국', '해적', '축구', '음식', '정원',
];
// 7칸 중 4칸은 꼬리 없음(all), 나머지 한 칸씩 영화(movie)·노래(music)·책(book).
const SUFFIXES = ['', '', '', '', ' 영화', ' 노래', ' 책'];

const N = SITUATIONS.length * MOODS.length * TOPICS.length;
// N(125,000 = 2^3 · 5^6)과 서로소인 소수. 반복 번호를 N 안에서 고르게 흩어 놓는다.
const STRIDE = 7919;
const MODE = __ENV.SEARCH_KEYWORD_MODE || 'unique';

function hash(text) {
  let h = 2166136261;
  for (let i = 0; i < text.length; i++) h = Math.imul(h ^ text.charCodeAt(i), 16777619) >>> 0;
  return h;
}

// 단계마다 다른 시작 위치. 워밍업(120s)과 첫 단계(60s)는 도착률이 같아 DURATION까지 넣어야 갈린다.
const SEED = hash(`${__ENV.RATE}|${__ENV.DURATION}`) % N;

function phrase(base, slot) {
  const s = base % SITUATIONS.length;
  const m = Math.floor(base / SITUATIONS.length) % MOODS.length;
  const t = Math.floor(base / (SITUATIONS.length * MOODS.length));
  return `${SITUATIONS[s]} ${MOODS[m]} ${TOPICS[t]}${SUFFIXES[slot]}`;
}

export const SEARCH_KEYWORD_COUNT = N * 4; // 꼬리 4가지(없음·영화·노래·책)

export function nextSearchKeyword() {
  if (MODE === 'zipf') {
    // P(순위 ≤ k) ≈ ln(k+1) / ln(N+1) — 지수 1인 지프 분포의 연속 근사. 같은 순위는 늘 같은 문장이다.
    const rank = Math.floor(Math.pow(N + 1, Math.random())) - 1;
    return phrase((rank * STRIDE) % N, (rank * 31 + 3) % SUFFIXES.length);
  }
  return searchKeyword(exec.scenario.iterationInTest);
}

/** i번째 조합 검색어(unique 방식). setup처럼 반복 번호가 없는 곳에서 검색어를 미리 만들 때 쓴다. */
export function searchKeyword(i) {
  return phrase((SEED + i * STRIDE) % N, i % SUFFIXES.length);
}
